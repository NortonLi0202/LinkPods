package local.airpods.handoffprobe;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class SamsungAudioPolicyTest {
    private static final String TARGET = "AA:BB:CC:DD:EE:FF";
    private static final String TARGET_LOWER = "aa:bb:cc:dd:ee:ff";
    private static final String OTHER = "11:22:33:44:55:66";

    static void expect(boolean ok, String message) {
        if (!ok) {
            throw new AssertionError(message);
        }
    }

    static void testConnectionNormalization() {
        expect(SamsungAudioPolicy.normalizeConnectionState(SamsungAudioPolicy.PROFILE_DISCONNECTED, true, true, true)
                == SamsungAudioSnapshot.ConnectionState.DISCONNECTED, "0 normalizes to DISCONNECTED");
        expect(SamsungAudioPolicy.normalizeConnectionState(SamsungAudioPolicy.PROFILE_CONNECTING, true, true, true)
                == SamsungAudioSnapshot.ConnectionState.CONNECTING, "1 normalizes to CONNECTING");
        expect(SamsungAudioPolicy.normalizeConnectionState(SamsungAudioPolicy.PROFILE_CONNECTED, true, true, true)
                == SamsungAudioSnapshot.ConnectionState.CONNECTED, "2 normalizes to CONNECTED");
        expect(SamsungAudioPolicy.normalizeConnectionState(SamsungAudioPolicy.PROFILE_DISCONNECTING, true, true, true)
                == SamsungAudioSnapshot.ConnectionState.DISCONNECTING, "3 normalizes to DISCONNECTING");
        expect(SamsungAudioPolicy.normalizeConnectionState(-1, true, true, true)
                == SamsungAudioSnapshot.ConnectionState.UNKNOWN, "unknown raw state stays UNKNOWN");
        expect(SamsungAudioPolicy.normalizeConnectionState(SamsungAudioPolicy.PROFILE_CONNECTED, false, true, true)
                == SamsungAudioSnapshot.ConnectionState.UNKNOWN, "missing permission forces UNKNOWN");
        expect(SamsungAudioPolicy.normalizeConnectionState(SamsungAudioPolicy.PROFILE_CONNECTED, true, false, true)
                == SamsungAudioSnapshot.ConnectionState.UNKNOWN, "unready proxy forces UNKNOWN");
        expect(SamsungAudioPolicy.normalizeConnectionState(SamsungAudioPolicy.PROFILE_CONNECTED, true, true, false)
                == SamsungAudioSnapshot.ConnectionState.UNKNOWN, "missing target forces UNKNOWN");
    }

    static void testDeviceAvailabilityNeverImpliesActiveRouteAndDistinguishesUnsupportedApi() {
        List<SamsungAudioPolicy.OutputDevice> devices = Collections.singletonList(
                new SamsungAudioPolicy.OutputDevice(TARGET_LOWER, "AirPods Pro", true, true));

        SamsungAudioSnapshot unsupportedSource = SamsungAudioPolicy.evaluate(
                TARGET,
                true,
                true, SamsungAudioPolicy.PROFILE_CONNECTED,
                true, SamsungAudioPolicy.PROFILE_DISCONNECTED,
                devices,
                "", "",
                SamsungAudioPolicy.AUDIO_MODE_NORMAL,
                SamsungAudioPolicy.RouteEvidence.none(),
                1234L);

        expect(unsupportedSource.a2dpState == SamsungAudioSnapshot.ConnectionState.CONNECTED,
                "A2DP connected tracked independently");
        expect(unsupportedSource.headsetState == SamsungAudioSnapshot.ConnectionState.DISCONNECTED,
                "headset disconnected tracked independently");
        expect(unsupportedSource.mediaOutputAvailable, "matching output device marks mediaOutputAvailable=true");
        expect(!unsupportedSource.routeEvidenceSupported,
                "API < 33 non-call mode marks routeEvidenceSupported=false");
        expect(unsupportedSource.routeState == SamsungAudioSnapshot.RouteState.UNKNOWN,
                "device presence alone without route evidence must stay UNKNOWN, not ROUTE_OBSERVED");
        expect(unsupportedSource.routeDetail.contains("不提供系统媒体路由查询"),
                "unsupported route evidence source explains unavailability");
        expect(SamsungAudioPolicy.formatRouteSummary(unsupportedSource).contains("接口不可用"),
                "route summary distinguishes unsupported API from supported-but-unknown");
        expect(unsupportedSource.lastUpdateElapsedMs == 1234L, "elapsed timestamp preserved");

        SamsungAudioPolicy.RouteEvidence supportedButEmpty = new SamsungAudioPolicy.RouteEvidence(
                true, false, false, false, false, false);
        SamsungAudioSnapshot supportedUnknown = SamsungAudioPolicy.evaluate(
                TARGET,
                true,
                true, SamsungAudioPolicy.PROFILE_CONNECTED,
                true, SamsungAudioPolicy.PROFILE_DISCONNECTED,
                devices,
                "", "",
                SamsungAudioPolicy.AUDIO_MODE_NORMAL,
                supportedButEmpty,
                1500L);
        expect(supportedUnknown.routeEvidenceSupported,
                "API 33+ route query source marks routeEvidenceSupported=true");
        expect(supportedUnknown.routeState == SamsungAudioSnapshot.RouteState.UNKNOWN,
                "supported API with empty route evidence stays UNKNOWN");
        expect(supportedUnknown.routeDetail.contains("路由证据不足"),
                "supported-but-unknown route explains insufficient evidence");
        expect(SamsungAudioPolicy.formatRouteSummary(supportedUnknown).contains("路由：未知"),
                "route summary shows 未知 when API is supported but route is unconfirmed");
    }

    static void testOtherRouteYieldsDeviceAvailableNotObserved() {
        List<SamsungAudioPolicy.OutputDevice> devices = Arrays.asList(
                new SamsungAudioPolicy.OutputDevice("", "Phone Speaker", false, false),
                new SamsungAudioPolicy.OutputDevice(TARGET, "AirPods Pro", true, true));

        SamsungAudioPolicy.RouteEvidence speakerRouted = new SamsungAudioPolicy.RouteEvidence(
                true, true, false,
                false, false, false);

        SamsungAudioSnapshot snapshot = SamsungAudioPolicy.evaluate(
                TARGET,
                true,
                true, SamsungAudioPolicy.PROFILE_CONNECTED,
                true, SamsungAudioPolicy.PROFILE_CONNECTED,
                devices,
                "", "",
                SamsungAudioPolicy.AUDIO_MODE_NORMAL,
                speakerRouted,
                2000L);

        expect(snapshot.mediaOutputAvailable, "target still available as media output");
        expect(snapshot.routeEvidenceSupported, "route evidence source is supported");
        expect(snapshot.routeState == SamsungAudioSnapshot.RouteState.DEVICE_AVAILABLE,
                "when another route is observed, available target resolves to DEVICE_AVAILABLE");
        expect(snapshot.routeDetail.contains("其他音频设备"),
                "device available detail explains another device is currently routed");
    }

    static void testObservedRoutesAndCallModes() {
        List<SamsungAudioPolicy.OutputDevice> devices = Collections.singletonList(
                new SamsungAudioPolicy.OutputDevice(TARGET, "AirPods Pro", true, true));

        SamsungAudioPolicy.RouteEvidence mediaEvidence = new SamsungAudioPolicy.RouteEvidence(
                true, true, true, false, false, false);
        SamsungAudioSnapshot mediaRoute = SamsungAudioPolicy.evaluate(
                TARGET, true,
                true, SamsungAudioPolicy.PROFILE_CONNECTED,
                true, SamsungAudioPolicy.PROFILE_DISCONNECTED,
                devices, "", "",
                SamsungAudioPolicy.AUDIO_MODE_NORMAL,
                mediaEvidence,
                3000L);
        expect(mediaRoute.routeState == SamsungAudioSnapshot.RouteState.ROUTE_OBSERVED,
                "media attributes device match resolves to ROUTE_OBSERVED");
        expect(mediaRoute.routeDetail.contains("试听确认"),
                "observed route detail still clarifies actual sound needs listening verification");

        SamsungAudioPolicy.RouteEvidence commEvidence = new SamsungAudioPolicy.RouteEvidence(
                false, false, false, true, true, true);
        SamsungAudioSnapshot callRoute = SamsungAudioPolicy.evaluate(
                TARGET, true,
                true, SamsungAudioPolicy.PROFILE_CONNECTED,
                true, SamsungAudioPolicy.PROFILE_CONNECTED,
                devices, "AirPods Pro (蓝牙通话 (SCO))", TARGET_LOWER,
                SamsungAudioPolicy.AUDIO_MODE_IN_COMMUNICATION,
                commEvidence,
                4000L);
        expect(callRoute.routeEvidenceSupported,
                "call mode communication device counts as supported route evidence even when media API is unavailable");
        expect(callRoute.routeState == SamsungAudioSnapshot.RouteState.ROUTE_OBSERVED,
                "communication route in call mode resolves to ROUTE_OBSERVED");
        expect(callRoute.communicationDeviceMatchesTarget,
                "communication device address matched case-insensitively");
        expect(SamsungAudioPolicy.isCallMode(callRoute.audioMode), "in-communication is recognized as call mode");
        expect(SamsungAudioPolicy.formatHeadsetSummary(callRoute).contains("语音通话中"),
                "headset summary includes audio mode");
    }

    static void testTargetIsolationAndPermissionLoss() {
        List<SamsungAudioPolicy.OutputDevice> foreignOnly = Collections.singletonList(
                new SamsungAudioPolicy.OutputDevice(OTHER, "Other Buds", true, true));
        SamsungAudioPolicy.RouteEvidence foreignRoute = new SamsungAudioPolicy.RouteEvidence(
                true, true, false, false, false, false);

        SamsungAudioSnapshot mismatched = SamsungAudioPolicy.evaluate(
                TARGET, true,
                true, SamsungAudioPolicy.PROFILE_DISCONNECTED,
                true, SamsungAudioPolicy.PROFILE_DISCONNECTED,
                foreignOnly,
                "Other Buds", OTHER,
                SamsungAudioPolicy.AUDIO_MODE_NORMAL,
                foreignRoute,
                5000L);
        expect(!mismatched.mediaOutputAvailable, "foreign Bluetooth output does not mark target available");
        expect(!mismatched.communicationDeviceMatchesTarget, "foreign communication device does not match target");
        expect(mismatched.routeState == SamsungAudioSnapshot.RouteState.UNKNOWN,
                "unavailable target with foreign route resolves to UNKNOWN");

        SamsungAudioSnapshot noPermission = SamsungAudioPolicy.evaluate(
                TARGET, false,
                true, SamsungAudioPolicy.PROFILE_CONNECTED,
                true, SamsungAudioPolicy.PROFILE_CONNECTED,
                Collections.singletonList(new SamsungAudioPolicy.OutputDevice(TARGET, "AirPods", true, true)),
                "AirPods", TARGET,
                SamsungAudioPolicy.AUDIO_MODE_IN_CALL,
                new SamsungAudioPolicy.RouteEvidence(true, true, true, true, true, true),
                6000L);
        expect(!noPermission.permissionGranted, "permission flag false");
        expect(noPermission.a2dpState == SamsungAudioSnapshot.ConnectionState.UNKNOWN,
                "missing permission hides A2DP state as UNKNOWN");
        expect(noPermission.headsetState == SamsungAudioSnapshot.ConnectionState.UNKNOWN,
                "missing permission hides headset state as UNKNOWN");
        expect(!noPermission.mediaOutputAvailable, "missing permission reports mediaOutputAvailable=false");
        expect(!noPermission.routeEvidenceSupported, "missing permission reports routeEvidenceSupported=false");
        expect(noPermission.routeState == SamsungAudioSnapshot.RouteState.UNKNOWN,
                "missing permission reports routeState=UNKNOWN");
    }

    public static void main(String[] args) {
        testConnectionNormalization();
        testDeviceAvailabilityNeverImpliesActiveRouteAndDistinguishesUnsupportedApi();
        testOtherRouteYieldsDeviceAvailableNotObserved();
        testObservedRoutesAndCallModes();
        testTargetIsolationAndPermissionLoss();
        System.out.println("PASS: Samsung audio profile normalization, device availability boundary and route evidence");
    }
}
