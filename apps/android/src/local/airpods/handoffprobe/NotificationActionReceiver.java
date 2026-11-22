package local.airpods.handoffprobe;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Non-exported receiver that handles notification actions without exposing commands on the launcher activity. */
public final class NotificationActionReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        String action = intent.getAction();
        if (HandoffService.ACTION_SET_LISTENING_MODE.equals(action)) {
            int mode = intent.getIntExtra(HandoffService.EXTRA_LISTENING_MODE, -1);
            if (mode >= 0) {
                HandoffService.setListeningModeFromNotification(mode);
            }
            return;
        }
        if (HandoffService.ACTION_MANUAL_HANDOFF.equals(action)) {
            String target = intent.getStringExtra("address");
            if (target != null && !target.isEmpty()) {
                HandoffService.manualHandoff(target);
            }
            return;
        }
    }
}
