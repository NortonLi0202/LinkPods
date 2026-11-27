package local.airpods.handoffprobe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Pure policy that converges session, earbud, permission, and audio route into one StatusSummary. */
public final class StatusSummaryPolicy {
    private StatusSummaryPolicy() {}

    public static StatusSummary evaluate(
            boolean hasBluetoothPermission,
            boolean hasNotificationPermission,
            boolean targetSelected,
            SessionStatus session,
            EarbudSnapshot earbud,
            SamsungAudioSnapshot audio,
            boolean bridgeRequired) {
        if (!hasBluetoothPermission) {
            return new StatusSummary(
                    StatusSummary.Level.UNAVAILABLE,
                    "缺少附近设备权限",
                    "请授予蓝牙与附近设备权限，否则无法检测耳机或启动控制会话。",
                    StatusSummary.PrimaryAction.GRANT_BLUETOOTH_PERMISSION);
        }
        if (!targetSelected) {
            return new StatusSummary(
                    StatusSummary.Level.UNAVAILABLE,
                    "尚未选择目标耳机",
                    "请在设置中选择一副已配对的 AirPods。",
                    StatusSummary.PrimaryAction.SELECT_DEVICE);
        }
        if (session == null || !session.serviceRunning) {
            return new StatusSummary(
                    StatusSummary.Level.UNAVAILABLE,
                    "控制服务未运行",
                    "点击连接耳机以建立 AACP 控制会话。",
                    StatusSummary.PrimaryAction.START_CONTROL);
        }
        if (!session.channelReady) {
            if (bridgeRequired) {
                return new StatusSummary(
                        StatusSummary.Level.UNAVAILABLE,
                        "直连不可用，需准备兼容桥接",
                        "请打开应用查看兼容桥接方案并显式确认后启用。",
                        StatusSummary.PrimaryAction.PREPARE_BRIDGE);
            }
            String desc = session.controlDescription.isEmpty() ? "控制会话连接中" : session.controlDescription;
            return new StatusSummary(
                    StatusSummary.Level.UNAVAILABLE,
                    desc,
                    "控制会话尚未就绪，暂不可读取电量或切换降噪。",
                    StatusSummary.PrimaryAction.OPEN_APP);
        }

        if (session.mode == SessionMode.AUTO_HANDOFF) {
            if (!hasNotificationPermission) {
                return new StatusSummary(
                        StatusSummary.Level.ATTENTION,
                        "自动交接需要媒体监听权限",
                        "控制会话已就绪，但缺少通知读取权限，无法随播放自动交接。",
                        StatusSummary.PrimaryAction.GRANT_NOTIFICATION_ACCESS);
            }
            if (!session.handoffNotice.isEmpty()) {
                return new StatusSummary(
                        StatusSummary.Level.ATTENTION,
                        session.handoffNotice,
                        "当前未能由系统确认目标输出，可使用手动切换到此耳机。",
                        StatusSummary.PrimaryAction.MANUAL_HANDOFF);
            }
            if (session.audioYielded && (audio == null || audio.a2dpState == SamsungAudioSnapshot.ConnectionState.DISCONNECTED)) {
                return new StatusSummary(
                        StatusSummary.Level.NORMAL,
                        "音频已让出给其他设备",
                        "控制会话保持就绪；本机播放将自动切回，也可点击手动切换。如需本机外放请切为纯控制模式。",
                        StatusSummary.PrimaryAction.MANUAL_HANDOFF);
            }
            if (session.audioYieldPending || session.audioYielded) {
                return new StatusSummary(
                        StatusSummary.Level.ATTENTION,
                        "正在让出音频（等待系统断开）",
                        "系统音频尚未断开，若仍由本机出声可点击手动切换或稍候。",
                        StatusSummary.PrimaryAction.MANUAL_HANDOFF);
            }
            if (audio != null && !SamsungAudioPolicy.isTargetOutputConfirmed(audio)) {
                return new StatusSummary(
                        StatusSummary.Level.ATTENTION,
                        "未确认为当前音频输出",
                        "控制会话已就绪；如声音仍在其他设备，可点击手动切换。",
                        StatusSummary.PrimaryAction.MANUAL_HANDOFF);
            }
            return new StatusSummary(
                    StatusSummary.Level.NORMAL,
                    "自动交接运行中",
                    "控制会话就绪，音频将随本机媒体播放自动交接。",
                    StatusSummary.PrimaryAction.NONE);
        }

        boolean a2dpConnected = audio != null && audio.a2dpState == SamsungAudioSnapshot.ConnectionState.CONNECTED;
        String headline = a2dpConnected ? "仅控制已连接" : "仅控制（本机未连音频）";
        String detail = a2dpConnected
                ? "当前处于纯控制模式（CONTROL_ONLY），支持电量和降噪控制。"
                : "当前处于纯控制模式（CONTROL_ONLY），仅维持控制会话，不干扰其他设备播放。";
        return new StatusSummary(
                StatusSummary.Level.NORMAL,
                headline,
                detail,
                StatusSummary.PrimaryAction.NONE);
    }

    /** Formats battery and listening mode line for the persistent notification. Empty when disconnected. */
    public static String formatNotificationTelemetry(
            SessionStatus session,
            EarbudSnapshot earbud,
            String pendingModeLabel) {
        if (session == null || !session.serviceRunning || !session.channelReady) {
            return "";
        }
        if (earbud == null || !earbud.ready) {
            return "";
        }
        boolean hasAnyBattery = earbud.leftBattery != null
                || earbud.rightBattery != null
                || earbud.caseBattery != null;
        String modePart = formatModePart(earbud, pendingModeLabel);
        if (!hasAnyBattery) {
            return modePart;
        }
        String batteryPart = "左 " + batteryText(earbud.leftBattery)
                + " · 右 " + batteryText(earbud.rightBattery)
                + " · 盒 " + batteryText(earbud.caseBattery);
        return modePart.isEmpty() ? batteryPart : batteryPart + " · " + modePart;
    }

    private static String formatModePart(EarbudSnapshot earbud, String pendingModeLabel) {
        if (pendingModeLabel != null && !pendingModeLabel.isEmpty()) {
            return "切换中：" + pendingModeLabel;
        }
        Integer mode = earbud.value(EarbudCommands.LISTENING_MODE);
        if (mode == null) {
            return "";
        }
        String name = modeDisplayName(mode);
        return name.isEmpty() ? "" : "模式：" + name;
    }

    public static String modeDisplayName(int mode) {
        if (mode == EarbudCommands.MODE_OFF) return "关闭";
        if (mode == EarbudCommands.MODE_ANC) return "降噪";
        if (mode == EarbudCommands.MODE_TRANSPARENCY) return "通透";
        if (mode == EarbudCommands.MODE_ADAPTIVE) return "自适应";
        return "";
    }

    private static String batteryText(Integer value) {
        return value == null ? "--" : value + "%";
    }

    /** Selects at most 3 notification actions following the agreed slot policy. */
    public static List<StatusSummary.NotificationAction> selectNotificationActions(
            StatusSummary summary,
            SessionStatus session,
            EarbudSnapshot earbud) {
        if (summary == null) {
            return StatusSummary.emptyActions();
        }
        boolean channelReady = session != null && session.serviceRunning && session.channelReady;
        if (!channelReady || earbud == null || !earbud.ready) {
            if (!summary.hasPrimaryAction()) {
                return StatusSummary.emptyActions();
            }
            return Collections.singletonList(StatusSummary.NotificationAction.primary(summary.primaryAction));
        }

        List<StatusSummary.NotificationAction> actions = new ArrayList<>(3);
        if (earbud.supportsListeningMode(EarbudCommands.MODE_TRANSPARENCY)) {
            actions.add(StatusSummary.NotificationAction.mode("通透", EarbudCommands.MODE_TRANSPARENCY));
        }
        if (earbud.supportsListeningMode(EarbudCommands.MODE_ANC)) {
            actions.add(StatusSummary.NotificationAction.mode("降噪", EarbudCommands.MODE_ANC));
        }

        if (actions.size() < 3 && summary.level == StatusSummary.Level.ATTENTION && summary.hasPrimaryAction()) {
            actions.add(StatusSummary.NotificationAction.primary(summary.primaryAction));
            return Collections.unmodifiableList(actions);
        }

        if (actions.size() < 3) {
            if (earbud.supportsListeningMode(EarbudCommands.MODE_ADAPTIVE)) {
                actions.add(StatusSummary.NotificationAction.mode("自适应", EarbudCommands.MODE_ADAPTIVE));
            } else if (earbud.supportsListeningMode(EarbudCommands.MODE_OFF)) {
                actions.add(StatusSummary.NotificationAction.mode("关闭", EarbudCommands.MODE_OFF));
            }
        }
        return Collections.unmodifiableList(actions);
    }
}
