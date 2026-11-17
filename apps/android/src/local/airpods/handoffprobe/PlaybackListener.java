package local.airpods.handoffprobe;

import android.service.notification.NotificationListenerService;

/** Grants the standard MediaSession callback path; notification contents are unused. */
public final class PlaybackListener extends NotificationListenerService {
    @Override public void onListenerConnected() {
        HandoffService service = HandoffService.current;
        if (service != null && service.mode() == SessionMode.AUTO_HANDOFF) service.restartSessionWatch();
    }

    @Override public void onListenerDisconnected() {
        HandoffService service = HandoffService.current;
        if (service != null) service.onMediaAccessLost();
    }
}
