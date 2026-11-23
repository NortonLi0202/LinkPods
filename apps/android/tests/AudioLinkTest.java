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
        p.want(true, 0);
        p.reset();
        expect(p.update(0) == AudioLinkPolicy.Action.NONE, "reset clears wanted and avoids connecting");
        expect(p.update(2) == AudioLinkPolicy.Action.NONE, "reset avoids disconnecting active link");
        p.want(false, 2);
        p.clear();
        expect(p.update(2) == AudioLinkPolicy.Action.NONE, "clear clears wanted and avoids disconnecting");

        AudioLinkPolicy resetLink = new AudioLinkPolicy();
        resetLink.want(false, 2);
        expect(resetLink.isWanted() == false, "wanted is false before reset");
        resetLink.reset();
        expect(resetLink.isWanted() == false, "isWanted is false after reset");
        expect(resetLink.update(0) == AudioLinkPolicy.Action.NONE, "reset cancels connect intent on disconnected broadcast");
        expect(resetLink.update(2) == AudioLinkPolicy.Action.NONE, "reset cancels disconnect intent on connected broadcast");

        AudioLinkPolicy yielded = new AudioLinkPolicy();
        expect(yielded.want(false, 2) == AudioLinkPolicy.Action.DISCONNECT, "yield disconnects active A2DP");
        expect(yielded.isYielded(), "yield is an explicit link state");
        expect(yielded.update(0) == AudioLinkPolicy.Action.NONE, "disconnect finishes");
        expect(yielded.update(1) == AudioLinkPolicy.Action.DISCONNECT, "passive earbud reconnect while yielded is torn down");
        expect(yielded.update(0) == AudioLinkPolicy.Action.NONE, "second disconnect finishes");
        expect(yielded.update(2) == AudioLinkPolicy.Action.DISCONNECT, "a later passive connection is still torn down");
        expect(yielded.update(3) == AudioLinkPolicy.Action.NONE && yielded.isYielded(),
            "teardown in flight needs no second disconnect and preserves yield");
        expect(yielded.want(true, 0) == AudioLinkPolicy.Action.CONNECT && !yielded.isYielded(),
            "only an explicit switch back leaves the yielded state");
        System.out.println("PASS: A2DP ownership lifecycle, yielded reconnect suppression and takeover races");
    }
}
