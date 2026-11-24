package local.airpods.handoffprobe;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class StatusSummaryPolicyTest {
    private static final String TARGET = "AA:BB:CC:DD:EE:FF";

    private static void expect(boolean ok, String message) {
        if (!ok) {
            throw new AssertionError(message);
        }
    }

    private static SamsungAudioSnapshot audio(boolean a2dpConnected, boolean routeObserved) {
        List<SamsungAudioPolicy.OutputDevice> devices = a2dpConnected
                ? Collections.singletonList(new SamsungAudioPolicy.OutputDevice(TARGET, "AirPods Pro", true, true))
                : Collections.emptyList();
        return SamsungAudioPolicy.evaluate(
                TARGET, true,
                true, a2dpConnected ? SamsungAudioPolicy.PROFILE_CONNECTED : SamsungAudioPolicy.PROFILE_DISCONNECTED,
                true, SamsungAudioPolicy.PROFILE_DISCONNECTED,
                devices, "", "",
                SamsungAudioPolicy.AUDIO_MODE_NORMAL,
                new SamsungAudioPolicy.RouteEvidence(true, a2dpConnected, routeObserved, false, false, false),
                1000L);
    }

    private static EarbudSnapshot earbud(boolean ready, Integer left, Integer right, Integer box, Integer mode, Integer... supported) {
        Map<Integer, Integer> settings = new HashMap<>();
        if (mode != null) {
            settings.put(EarbudCommands.LISTENING_MODE, mode);
        }
        Set<Integer> modes = new HashSet<>(Arrays.asList(supported));
        AirPodsModels.Model model = AirPodsModels.find("A2698");
        expect(model != null, "Expected A2698 model in registry");
        return new EarbudSnapshot(
                TARGET, ready, left, right, box, ready ? "已就绪" : "未就绪",
                settings, Collections.emptySet(), modes, model, "A2698", "AirPods Pro", "6A301", "6A301");
    }

    static void testUnavailablePriorityAndBridgeFallback() {
        StatusSummary noBt = StatusSummaryPolicy.evaluate(
                false, false, false, SessionStatus.idle(TARGET), null, null, true);
        expect(noBt.level == StatusSummary.Level.UNAVAILABLE, "Missing BT permission is UNAVAILABLE");
        expect(noBt.primaryAction == StatusSummary.PrimaryAction.GRANT_BLUETOOTH_PERMISSION, "BT permission comes first");

        StatusSummary noTarget = StatusSummaryPolicy.evaluate(
                true, false, false, SessionStatus.idle(TARGET), null, null, true);
        expect(noTarget.primaryAction == StatusSummary.PrimaryAction.SELECT_DEVICE, "Selecting target comes second");

        StatusSummary stopped = StatusSummaryPolicy.evaluate(
                true, true, true, SessionStatus.idle(TARGET), null, null, true);
        expect(stopped.primaryAction == StatusSummary.PrimaryAction.START_CONTROL, "Stopped service prompts connect");

        SessionStatus connecting = new SessionStatus(TARGET, SessionMode.AUTO_HANDOFF, true, false, "连接失败", "运行中");
        StatusSummary needsBridge = StatusSummaryPolicy.evaluate(
                true, true, true, connecting, null, null, true);
        expect(needsBridge.level == StatusSummary.Level.UNAVAILABLE, "Unready channel is UNAVAILABLE");
        expect(needsBridge.primaryAction == StatusSummary.PrimaryAction.PREPARE_BRIDGE, "Bridge requirement surfaces PREPARE_BRIDGE");
    }

    static void testAttentionAndControlOnlyNormalBoundary() {
        SessionStatus auto = new SessionStatus(TARGET, SessionMode.AUTO_HANDOFF, true, true, "直连已连接", "运行中", "");
        StatusSummary noMediaPerm = StatusSummaryPolicy.evaluate(
                true, false, true, auto, earbud(true, 80, 85, 60, EarbudCommands.MODE_ANC, EarbudCommands.MODE_TRANSPARENCY, EarbudCommands.MODE_ANC),
                audio(true, true), false);
        expect(noMediaPerm.level == StatusSummary.Level.ATTENTION, "Missing media access in AUTO_HANDOFF is ATTENTION");
        expect(noMediaPerm.primaryAction == StatusSummary.PrimaryAction.GRANT_NOTIFICATION_ACCESS, "Prompts media permission");

        SessionStatus noticeAuto = new SessionStatus(TARGET, SessionMode.AUTO_HANDOFF, true, true, "直连已连接", "当前外放", "当前由本机扬声器播放，未自动抢占");
        StatusSummary noticeSummary = StatusSummaryPolicy.evaluate(
                true, true, true, noticeAuto, earbud(true, 80, 85, 60, EarbudCommands.MODE_ANC, EarbudCommands.MODE_TRANSPARENCY, EarbudCommands.MODE_ANC),
                audio(true, false), false);
        expect(noticeSummary.level == StatusSummary.Level.ATTENTION, "Handoff notice yields ATTENTION");
        expect(noticeSummary.primaryAction == StatusSummary.PrimaryAction.MANUAL_HANDOFF, "Handoff notice offers MANUAL_HANDOFF");

        SessionStatus yieldedAuto = new SessionStatus(TARGET, SessionMode.AUTO_HANDOFF, true, true, "直连已连接", "已让出（其他设备使用中）", "", true);
        EarbudSnapshot yieldedBuds = earbud(true, 80, 85, 60, EarbudCommands.MODE_ANC,
                EarbudCommands.MODE_TRANSPARENCY, EarbudCommands.MODE_ANC, EarbudCommands.MODE_ADAPTIVE, EarbudCommands.MODE_OFF);
        StatusSummary yieldedSummary = StatusSummaryPolicy.evaluate(
                true, true, true, yieldedAuto, yieldedBuds, audio(false, false), false);
        expect(yieldedSummary.level == StatusSummary.Level.NORMAL, "Yielded AUTO_HANDOFF with disconnected A2DP stays NORMAL");
        expect(yieldedSummary.primaryAction == StatusSummary.PrimaryAction.MANUAL_HANDOFF, "Yielded summary keeps MANUAL_HANDOFF quick action");
        List<StatusSummary.NotificationAction> yieldedActions = StatusSummaryPolicy.selectNotificationActions(yieldedSummary, yieldedAuto, yieldedBuds);
        expect(yieldedActions.size() == 3 && yieldedActions.get(2).listeningMode == EarbudCommands.MODE_ADAPTIVE,
                "Yielded NORMAL summary preserves all 3 listening mode slots in notification");

        SessionStatus controlOnly = new SessionStatus(TARGET, SessionMode.CONTROL_ONLY, true, true, "直连已连接", "关闭");
        StatusSummary controlNoA2dp = StatusSummaryPolicy.evaluate(
                true, false, true, controlOnly, earbud(true, 80, 85, 60, EarbudCommands.MODE_ANC, EarbudCommands.MODE_TRANSPARENCY, EarbudCommands.MODE_ANC),
                audio(false, false), false);
        expect(controlNoA2dp.level == StatusSummary.Level.NORMAL, "CONTROL_ONLY without local A2DP stays NORMAL");
        expect(!controlNoA2dp.hasPrimaryAction(), "NORMAL summary has no primary action");
    }

    static void testNotificationTelemetryAndActionSlots() {
        SessionStatus disconnected = SessionStatus.idle(TARGET);
        EarbudSnapshot stale = earbud(false, 90, 90, 50, EarbudCommands.MODE_ANC, EarbudCommands.MODE_TRANSPARENCY, EarbudCommands.MODE_ANC);
        expect(StatusSummaryPolicy.formatNotificationTelemetry(disconnected, stale, "").isEmpty(),
                "Disconnected session hides stale battery in notification");

        SessionStatus ready = new SessionStatus(TARGET, SessionMode.AUTO_HANDOFF, true, true, "直连已连接", "运行中");
        EarbudSnapshot live = earbud(true, null, 85, 60, EarbudCommands.MODE_ANC,
                EarbudCommands.MODE_TRANSPARENCY, EarbudCommands.MODE_ANC, EarbudCommands.MODE_ADAPTIVE, EarbudCommands.MODE_OFF);
        String line = StatusSummaryPolicy.formatNotificationTelemetry(ready, live, "");
        expect(line.equals("左 -- · 右 85% · 盒 60% · 模式：降噪"), "Unexpected telemetry line: " + line);

        String pendingLine = StatusSummaryPolicy.formatNotificationTelemetry(ready, live, "通透");
        expect(pendingLine.equals("左 -- · 右 85% · 盒 60% · 切换中：通透"), "Unexpected pending line: " + pendingLine);

        StatusSummary normal = new StatusSummary(StatusSummary.Level.NORMAL, "自动交接运行中", "", StatusSummary.PrimaryAction.NONE);
        List<StatusSummary.NotificationAction> normalActions = StatusSummaryPolicy.selectNotificationActions(normal, ready, live);
        expect(normalActions.size() == 3, "Normal notification has 3 actions");
        expect(normalActions.get(0).listeningMode == EarbudCommands.MODE_TRANSPARENCY, "Slot 0 is transparency");
        expect(normalActions.get(1).listeningMode == EarbudCommands.MODE_ANC, "Slot 1 is ANC");
        expect(normalActions.get(2).listeningMode == EarbudCommands.MODE_ADAPTIVE, "Slot 2 prefers adaptive over off");

        StatusSummary attention = new StatusSummary(StatusSummary.Level.ATTENTION, "未确认输出", "", StatusSummary.PrimaryAction.MANUAL_HANDOFF);
        List<StatusSummary.NotificationAction> attentionActions = StatusSummaryPolicy.selectNotificationActions(attention, ready, live);
        expect(attentionActions.size() == 3 && attentionActions.get(2).primaryAction == StatusSummary.PrimaryAction.MANUAL_HANDOFF,
                "ATTENTION replaces slot 2 with primary action");

        StatusSummary unavailable = new StatusSummary(StatusSummary.Level.UNAVAILABLE, "服务未运行", "", StatusSummary.PrimaryAction.START_CONTROL);
        List<StatusSummary.NotificationAction> unavailableActions = StatusSummaryPolicy.selectNotificationActions(unavailable, disconnected, stale);
        expect(unavailableActions.size() == 1 && unavailableActions.get(0).primaryAction == StatusSummary.PrimaryAction.START_CONTROL,
                "UNAVAILABLE only shows 1 primary action and no listening mode buttons");
    }

    public static void main(String[] args) {
        testUnavailablePriorityAndBridgeFallback();
        testAttentionAndControlOnlyNormalBoundary();
        testNotificationTelemetryAndActionSlots();
        System.out.println("PASS: StatusSummaryPolicy priority, telemetry formatting, and notification action slots");
    }
}
