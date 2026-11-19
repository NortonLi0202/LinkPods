package local.airpods.handoffprobe;

import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Owns MediaSession watch registration, generation fencing, and active playback pausing. */
final class PlaybackWatch {
    interface Logger {
        void write(String message);
    }

    interface Listener {
        void onPlayersSeeded(Map<String, Integer> states, boolean restarted);
        void onPlayerStateChanged(String playerId, String packageName, int state);
        void onPlayerRemoved(String playerId);
        void onSessionsSynced();
        void onWatchStopped(boolean accessLost);
    }

    private static final String TELECOM_PACKAGE = "com.android.server.telecom";

    private static final class TrackedPlayer {
        final MediaController controller;
        final MediaController.Callback callback;
        boolean awaitingBaselineFence;
        long baselineUpdateTime;

        TrackedPlayer(MediaController controller, MediaController.Callback callback) {
            this.controller = controller;
            this.callback = callback;
        }
    }

    private final Context context;
    private final Handler handler;
    private final ComponentName listenerComponent;
    private final Logger logger;
    private final Listener listener;
    private final Map<MediaSession.Token, TrackedPlayer> players = new HashMap<>();
    private MediaSessionManager mediaSessions;
    private MediaSessionManager.OnActiveSessionsChangedListener activeSessionsListener;
    private boolean watching;
    private boolean preservePendingOnRestart;
    private long generation;

    PlaybackWatch(
            Context context,
            Handler handler,
            ComponentName listenerComponent,
            Logger logger,
            Listener listener) {
        this.context = context;
        this.handler = handler;
        this.listenerComponent = listenerComponent;
        this.logger = logger;
        this.listener = listener;
    }

    boolean hasMediaAccess() {
        if (Build.VERSION.SDK_INT < 27) return true;
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        return nm == null || nm.isNotificationListenerAccessGranted(listenerComponent);
    }

    boolean isWatching() {
        return watching;
    }

    boolean isActive() {
        return watching && hasMediaAccess();
    }

    boolean start() {
        if (watching) return false;
        boolean restarted = preservePendingOnRestart;
        boolean started = startInternal(restarted);
        if (started) preservePendingOnRestart = false;
        return started;
    }

    boolean restart() {
        boolean wasWatching = watching || preservePendingOnRestart;
        if (!hasMediaAccess()) {
            preservePendingOnRestart = false;
            if (hasRegistrationState()) onAccessLost();
            else if (wasWatching) listener.onWatchStopped(true);
            return false;
        }
        if (hasRegistrationState()) {
            tearDownRegistration();
        }
        boolean started = startInternal(wasWatching);
        if (started) {
            preservePendingOnRestart = false;
        } else if (wasWatching) {
            if (!hasMediaAccess()) {
                preservePendingOnRestart = false;
                listener.onWatchStopped(true);
            } else {
                // Keep pending TAKE intact across recoverable registration failures until next rebind.
                preservePendingOnRestart = true;
            }
        }
        return started;
    }

    void stop() {
        preservePendingOnRestart = false;
        stopInternal(false);
    }

    void onAccessLost() {
        boolean hadPendingRestart = preservePendingOnRestart;
        preservePendingOnRestart = false;
        if (!hasRegistrationState()) {
            if (hadPendingRestart) {
                listener.onWatchStopped(true);
                logger.write("MEDIA_SESSION_WATCH access-lost");
            }
            return;
        }
        stopInternal(true);
        logger.write("MEDIA_SESSION_WATCH access-lost");
    }

    void pauseActive() {
        for (TrackedPlayer tracked : new ArrayList<>(players.values())) {
            MediaController player = tracked.controller;
            PlaybackState state = player.getPlaybackState();
            if (state != null && (state.getState() == PlaybackState.STATE_PLAYING
                    || state.getState() == PlaybackState.STATE_BUFFERING
                    || state.getState() == PlaybackState.STATE_CONNECTING)) {
                player.getTransportControls().pause();
                logger.write("PAUSE yielded-media package=" + player.getPackageName());
            }
        }
    }

    private boolean hasRegistrationState() {
        return watching || activeSessionsListener != null || !players.isEmpty();
    }

    private boolean startInternal(boolean restarted) {
        if (!hasMediaAccess()) return false;
        MediaSessionManager manager = context.getSystemService(MediaSessionManager.class);
        if (manager == null) return false;
        mediaSessions = manager;
        final long batch = ++generation;
        MediaSessionManager.OnActiveSessionsChangedListener sessionsListener =
                list -> onActiveSessionsChanged(batch, list);
        Map<String, Integer> seeded = new LinkedHashMap<>();
        Map<MediaSession.Token, Integer> seededBaselines = new LinkedHashMap<>();
        try {
            // Register the active-session listener before reading the initial list so sessions
            // added between getActiveSessions() and listener registration cannot be missed on Android 12L.
            manager.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent, handler);
            activeSessionsListener = sessionsListener;
            List<MediaController> active = manager.getActiveSessions(listenerComponent);
            seedPlayers(batch, active, seeded, seededBaselines);
        } catch (SecurityException error) {
            tearDownRegistration();
            logger.write("MEDIA_SESSION_WATCH access-required " + error);
            return false;
        }
        watching = true;
        logger.write("MEDIA_SESSION_WATCH ready");
        listener.onPlayersSeeded(seeded, restarted);
        if (!seededBaselines.isEmpty()) {
            handler.post(() -> finishBaselineFence(batch, seededBaselines));
        }
        return true;
    }

    private void stopInternal(boolean accessLost) {
        if (!hasRegistrationState()) return;
        boolean wasWatching = tearDownRegistration();
        if (wasWatching) logger.write("MEDIA_SESSION_WATCH stopped");
        listener.onWatchStopped(accessLost);
    }

    private boolean tearDownRegistration() {
        // Bump generation before removing platform listeners so queued callbacks see a stale batch.
        ++generation;
        boolean wasWatching = watching;
        if (activeSessionsListener != null && mediaSessions != null) {
            try { mediaSessions.removeOnActiveSessionsChangedListener(activeSessionsListener); }
            catch (RuntimeException ignored) {}
        }
        activeSessionsListener = null;
        for (MediaSession.Token token : new HashSet<>(players.keySet())) {
            unregisterPlayer(token);
        }
        watching = false;
        return wasWatching;
    }

    private void seedPlayers(
            long batch,
            List<MediaController> list,
            Map<String, Integer> seeded,
            Map<MediaSession.Token, Integer> seededBaselines) {
        if (list == null) return;
        for (MediaController controller : list) {
            if (!isTrackable(controller)) continue;
            MediaSession.Token token = controller.getSessionToken();
            if (token == null || players.containsKey(token)) continue;
            // Register callback before reading the baseline so a PAUSED->PLAYING transition cannot slip past Android 12L registration.
            TrackedPlayer tracked = registerPlayer(batch, token, controller);
            PlaybackState baselineState = controller.getPlaybackState();
            int baseline = playbackStateValue(baselineState);
            seeded.put(token.toString(), baseline);
            seededBaselines.put(token, baseline);
            tracked.awaitingBaselineFence = true;
            tracked.baselineUpdateTime = baselineState == null ? 0L : baselineState.getLastPositionUpdateTime();
        }
    }

    private void finishBaselineFence(long batch, Map<MediaSession.Token, Integer> seededBaselines) {
        if (!isCurrentBatch(batch)) return;
        for (Map.Entry<MediaSession.Token, Integer> entry : seededBaselines.entrySet()) {
            if (!isCurrentBatch(batch)) return;
            MediaSession.Token token = entry.getKey();
            TrackedPlayer tracked = players.get(token);
            if (tracked == null || !tracked.awaitingBaselineFence) continue;
            tracked.awaitingBaselineFence = false;
            int current = playbackStateValue(tracked.controller.getPlaybackState());
            if (current != entry.getValue()) {
                listener.onPlayerStateChanged(token.toString(), safePackageName(tracked.controller), current);
            }
        }
    }

    private void onActiveSessionsChanged(long batch, List<MediaController> list) {
        if (!isCurrentBatch(batch)) return;
        List<MediaController> currentList;
        if (mediaSessions != null) {
            try {
                currentList = mediaSessions.getActiveSessions(listenerComponent);
            } catch (SecurityException error) {
                onAccessLost();
                return;
            }
        } else {
            currentList = list;
        }
        Set<MediaSession.Token> present = new HashSet<>();
        List<MediaController> added = new ArrayList<>();
        if (currentList != null) {
            for (MediaController controller : currentList) {
                if (!isTrackable(controller)) continue;
                MediaSession.Token token = controller.getSessionToken();
                if (token == null) continue;
                present.add(token);
                if (players.containsKey(token)) continue;
                registerPlayer(batch, token, controller);
                added.add(controller);
            }
        }
        for (MediaController controller : added) {
            if (!isCurrentBatch(batch)) return;
            MediaSession.Token token = controller.getSessionToken();
            TrackedPlayer tracked = token == null ? null : players.get(token);
            if (tracked == null || tracked.controller != controller) continue;
            listener.onPlayerStateChanged(
                    token.toString(),
                    safePackageName(controller),
                    playbackStateValue(controller.getPlaybackState()));
        }
        for (MediaSession.Token token : new HashSet<>(players.keySet())) {
            if (!isCurrentBatch(batch)) return;
            if (!present.contains(token)) {
                unregisterPlayer(token);
                listener.onPlayerRemoved(token.toString());
            }
        }
        if (isCurrentBatch(batch) && (currentList == null || currentList.isEmpty()) && !hasMediaAccess()) {
            onAccessLost();
            return;
        }
        if (isCurrentBatch(batch)) {
            listener.onSessionsSynced();
        }
    }

    private void onPlaybackStateChanged(long batch, MediaController controller, PlaybackState state) {
        if (!isCurrentBatch(batch)) return;
        MediaSession.Token token = controller.getSessionToken();
        TrackedPlayer tracked = token == null ? null : players.get(token);
        if (tracked == null || tracked.controller != controller) return;
        if (tracked.awaitingBaselineFence) {
            long eventTime = state == null ? 0L : state.getLastPositionUpdateTime();
            if (state == null || eventTime <= tracked.baselineUpdateTime) {
                return;
            }
            tracked.awaitingBaselineFence = false;
        }
        int value = playbackStateValue(state);
        if (value == PlaybackState.STATE_PLAYING
                && playbackStateValue(controller.getPlaybackState()) != PlaybackState.STATE_PLAYING) {
            return;
        }
        listener.onPlayerStateChanged(
                token.toString(),
                safePackageName(controller),
                value);
    }

    private boolean isCurrentBatch(long batch) {
        return watching && batch == generation;
    }

    private static boolean isTrackable(MediaController controller) {
        return controller != null && !TELECOM_PACKAGE.equals(controller.getPackageName());
    }

    private TrackedPlayer registerPlayer(
            final long batch,
            final MediaSession.Token token,
            final MediaController controller) {
        MediaController.Callback callback = new MediaController.Callback() {
            @Override public void onPlaybackStateChanged(PlaybackState state) {
                PlaybackWatch.this.onPlaybackStateChanged(batch, controller, state);
            }
        };
        TrackedPlayer tracked = new TrackedPlayer(controller, callback);
        players.put(token, tracked);
        controller.registerCallback(callback, handler);
        return tracked;
    }

    private void unregisterPlayer(MediaSession.Token token) {
        TrackedPlayer tracked = players.remove(token);
        if (tracked != null) {
            try { tracked.controller.unregisterCallback(tracked.callback); }
            catch (RuntimeException ignored) {}
        }
    }

    private static int playbackStateValue(PlaybackState state) {
        return state == null ? 0 : state.getState();
    }

    private static String safePackageName(MediaController controller) {
        String name = controller.getPackageName();
        return name == null ? "" : name;
    }
}
