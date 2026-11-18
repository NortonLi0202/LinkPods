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
        void onWatchStopped(boolean accessLost);
    }

    private static final String TELECOM_PACKAGE = "com.android.server.telecom";

    private final Context context;
    private final Handler handler;
    private final ComponentName listenerComponent;
    private final Logger logger;
    private final Listener listener;
    private final Map<MediaSession.Token, MediaController> players = new HashMap<>();
    private final Map<MediaSession.Token, MediaController.Callback> playerCallbacks = new HashMap<>();
    private MediaSessionManager mediaSessions;
    private MediaSessionManager.OnActiveSessionsChangedListener activeSessionsListener;
    private boolean watching;
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
        return startInternal(false);
    }

    boolean restart() {
        boolean wasWatching = watching;
        if (!hasMediaAccess()) {
            if (hasRegistrationState()) onAccessLost();
            return false;
        }
        if (hasRegistrationState()) {
            tearDownRegistration();
        }
        boolean started = startInternal(wasWatching);
        if (!started && wasWatching) {
            listener.onWatchStopped(true);
        }
        return started;
    }

    void stop() {
        stopInternal(false);
    }

    void onAccessLost() {
        if (!hasRegistrationState()) return;
        stopInternal(true);
        logger.write("MEDIA_SESSION_WATCH access-lost");
    }

    void pauseActive() {
        for (MediaController player : new ArrayList<>(players.values())) {
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
        return watching || activeSessionsListener != null
                || !players.isEmpty() || !playerCallbacks.isEmpty();
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
        try {
            List<MediaController> active = manager.getActiveSessions(listenerComponent);
            seedPlayers(batch, active, seeded);
            manager.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent, handler);
        } catch (SecurityException error) {
            tearDownRegistration();
            logger.write("MEDIA_SESSION_WATCH access-required " + error);
            return false;
        }
        activeSessionsListener = sessionsListener;
        watching = true;
        logger.write("MEDIA_SESSION_WATCH ready");
        listener.onPlayersSeeded(seeded, restarted);
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

    private void seedPlayers(long batch, List<MediaController> list, Map<String, Integer> seeded) {
        if (list == null) return;
        for (MediaController controller : list) {
            if (!isTrackable(controller)) continue;
            MediaSession.Token token = controller.getSessionToken();
            if (token == null || players.containsKey(token)) continue;
            registerPlayer(batch, token, controller);
            seeded.put(token.toString(), playbackStateValue(controller.getPlaybackState()));
        }
    }

    private void onActiveSessionsChanged(long batch, List<MediaController> list) {
        if (!isCurrentBatch(batch)) return;
        Set<MediaSession.Token> present = new HashSet<>();
        List<MediaController> added = new ArrayList<>();
        if (list != null) {
            for (MediaController controller : list) {
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
            if (token == null || players.get(token) != controller) continue;
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
        if (isCurrentBatch(batch) && (list == null || list.isEmpty()) && !hasMediaAccess()) {
            onAccessLost();
        }
    }

    private void onPlaybackStateChanged(long batch, MediaController controller, PlaybackState state) {
        if (!isCurrentBatch(batch)) return;
        MediaSession.Token token = controller.getSessionToken();
        if (token == null || players.get(token) != controller) return;
        listener.onPlayerStateChanged(
                token.toString(),
                safePackageName(controller),
                playbackStateValue(state));
    }

    private boolean isCurrentBatch(long batch) {
        return watching && batch == generation;
    }

    private static boolean isTrackable(MediaController controller) {
        return controller != null && !TELECOM_PACKAGE.equals(controller.getPackageName());
    }

    private void registerPlayer(
            final long batch,
            final MediaSession.Token token,
            final MediaController controller) {
        players.put(token, controller);
        MediaController.Callback callback = new MediaController.Callback() {
            @Override public void onPlaybackStateChanged(PlaybackState state) {
                PlaybackWatch.this.onPlaybackStateChanged(batch, controller, state);
            }
        };
        playerCallbacks.put(token, callback);
        controller.registerCallback(callback, handler);
    }

    private void unregisterPlayer(MediaSession.Token token) {
        MediaController controller = players.remove(token);
        MediaController.Callback callback = playerCallbacks.remove(token);
        if (controller != null && callback != null) {
            try { controller.unregisterCallback(callback); }
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
