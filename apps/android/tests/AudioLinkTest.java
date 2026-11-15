package local.airpods.handoffprobe;

public final class AudioLinkTest {
    static void expect(boolean result, String label) { if (!result) throw new AssertionError(label); }
    public static void main(String[] args) {
        AudioLinkPolicy p = new AudioLinkPolicy();
        expect(p.update(2) == AudioLinkPolicy.Action.NONE, "startup observation does not disconnect a working link");
        expect(p.want(false,2) == AudioLinkPolicy.Action.DISCONNECT, "yield closes only the media profile");
        expect(p.want(true,3) == AudioLinkPolicy.Action.NONE, "quick reclaim waits for disconnection to finish");
        expect(p.update(0) == AudioLinkPolicy.Action.CONNECT, "quick reclaim reconnects after disconnected broadcast");
        expect(p.update(1) == AudioLinkPolicy.Action.NONE, "connecting does not issue another connect");
        expect(p.want(false,1) == AudioLinkPolicy.Action.DISCONNECT, "remote takeover cancels a pending media connection");
        expect(p.update(0) == AudioLinkPolicy.Action.NONE, "yielded link must not reconnect itself");
        expect(p.update(2) == AudioLinkPolicy.Action.DISCONNECT, "late connection after yield is torn down");
        expect(p.want(true,0) == AudioLinkPolicy.Action.CONNECT, "new explicit playback can reconnect");
        expect(p.update(2) == AudioLinkPolicy.Action.NONE, "connected owner needs no profile churn");
        System.out.println("PASS: A2DP ownership lifecycle and takeover/disconnect races");
    }
}
