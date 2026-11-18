"""Run ControlSession lifecycle, request-token, and stale-callback tests against host-only stubs."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
PACKAGE = 'local/airpods/handoffprobe/'
STUBS = {
    'android/bluetooth/BluetoothDevice.java': '''package android.bluetooth;
public class BluetoothDevice {
 public final String address;
 public BluetoothDevice(String a){address=a;}
 public String getAddress(){return address;}
}''',
    'android/os/Looper.java':
        'package android.os; public class Looper {public static Looper getMainLooper(){return new Looper();}}',
    'android/os/SystemClock.java':
        'package android.os; public class SystemClock {public static long elapsedRealtime(){return 0;}}',
    'android/os/Handler.java': '''package android.os;
public class Handler {
 public Handler(Looper l){}
 public boolean post(Runnable r){r.run();return true;}
}''',
    PACKAGE + 'AapProbe.java': '''package local.airpods.handoffprobe;
import java.util.*;
import java.util.function.*;
class AapProbe {
 interface Logger {void write(String m);}
 enum Failure {COMPATIBILITY_REQUIRED,OTHER}
 interface Listener {void ready();void packet(byte[] b);void ended();default void failure(Failure f){}}
 final Listener listener;
 final List<byte[]> packets=new ArrayList<>();
 final List<Consumer<Boolean>> pendingWrites=new ArrayList<>();
 boolean holdWrites;
 int started,stopped,stateRequests;
 AapProbe(android.bluetooth.BluetoothDevice d,Logger l,Listener x){listener=x;}
 void sendPacketAsync(byte[] b,Consumer<Boolean> done){sendPacketAsync(b,()->true,done);}
 void sendPacketAsync(byte[] b,BooleanSupplier guard,Consumer<Boolean> done){
  if(holdWrites){
   pendingWrites.add(ok->{
    boolean delivered=ok&&guard.getAsBoolean();
    if(delivered)packets.add(b);
    done.accept(delivered);
   });
   return;
  }
  boolean ok=guard.getAsBoolean();
  if(ok)packets.add(b);
  done.accept(ok);
 }
 void requestState(){stateRequests++;}
 void start(){started++;}
 void stop(){stopped++;}
}''',
    PACKAGE + 'ControlSessionTest.java': '''package local.airpods.handoffprobe;
import android.bluetooth.*;
import android.os.*;
import java.util.*;
import java.util.concurrent.atomic.*;

public class ControlSessionTest {
 static final String TARGET_A="AA:BB:CC:DD:EE:01",TARGET_B="AA:BB:CC:DD:EE:02";

 static final class RecordingListener implements ControlSession.Listener {
  final List<String> events=new ArrayList<>();
  final List<byte[]> packets=new ArrayList<>();
  @Override public void onReady(long gen,String addr){events.add("ready:"+gen+":"+addr);}
  @Override public void onPacket(byte[] data){packets.add(data);}
  @Override public void onEnded(long gen,String addr,boolean compat){events.add("ended:"+gen+":"+addr+":"+compat);}
 }

 static byte[] scalarReport(int id,int value){
  return new byte[]{4,0,4,0,9,0,(byte)id,(byte)value,0,0,0};
 }

 static void expect(boolean ok,String why){if(!ok)throw new AssertionError(why);}

 static void timeoutRetryIgnoresStaleFailure(){
  AtomicLong clock=new AtomicLong(100);
  List<AapProbe> probes=new ArrayList<>();
  RecordingListener events=new RecordingListener();
  ControlSession session=new ControlSession(
   new Handler(Looper.getMainLooper()),
   clock::get,
   (dev,log,l)->{AapProbe p=new AapProbe(dev,log,l);probes.add(p);return p;},
   m->{},
   events);

  session.begin(TARGET_A);
  session.connect(new BluetoothDevice(TARGET_A),TARGET_A,1L);
  AapProbe probe=probes.get(0);
  probe.listener.ready();
  probe.listener.packet(scalarReport(EarbudCommands.CONVERSATION_AWARENESS,2));

  // Request A queued with held write; clock advances past 5s timeout before A finishes.
  probe.holdWrites=true;
  expect(session.setSetting(TARGET_A,EarbudCommands.CONVERSATION_AWARENESS,1),"request A queued");
  clock.set(5200);
  EarbudSnapshot expired=session.snapshot(TARGET_A);
  expect(expired.pending.isEmpty()&&"设置请求超时，耳机未确认".equals(expired.status),
   "request A expires before socket write as request-timeout, not sent");

  // Retry Request B on the same connection.
  expect(session.setSetting(TARGET_A,EarbudCommands.CONVERSATION_AWARENESS,2),"request B queued");
  expect(probe.pendingWrites.size()==2,"both async writes held on same probe");

  // Late failure of Request A must NOT clear Request B.
  probe.pendingWrites.get(0).accept(false);
  EarbudSnapshot afterStaleFailure=session.snapshot(TARGET_A);
  expect(afterStaleFailure.pending.contains(EarbudCommands.CONVERSATION_AWARENESS)
    &&"等待耳机确认设置".equals(afterStaleFailure.status),
   "stale failure of timed-out request A must not clear active retry B");

  // Succeeding Request B triggers state refresh; if B then times out without reply, status says sent-unconfirmed.
  probe.pendingWrites.get(1).accept(true);
  expect(probe.stateRequests==1,"successful write triggers state refresh");
  clock.set(10300);
  EarbudSnapshot sentExpired=session.snapshot(TARGET_A);
  expect(sentExpired.pending.isEmpty()&&"设置已发送，耳机未确认".equals(sentExpired.status),
   "timeout after confirmed write reports sent without confirmation");

  // Fresh Request B2 succeeds and matching reply confirms it.
  expect(session.setSetting(TARGET_A,EarbudCommands.CONVERSATION_AWARENESS,2),"request B2 queued");
  probe.pendingWrites.get(2).accept(true);
  expect(probe.stateRequests==2,"B2 write triggers state refresh");
  probe.listener.packet(scalarReport(EarbudCommands.CONVERSATION_AWARENESS,2));
  expect(!session.snapshot(TARGET_A).pending.contains(EarbudCommands.CONVERSATION_AWARENESS),
   "matching reply confirms request B2");

  // Late success on an already-timed-out write only triggers a read-only refresh without clearing a newer pending request.
  clock.set(11000);
  expect(session.setSetting(TARGET_A,EarbudCommands.CONVERSATION_AWARENESS,1),"request C queued");
  clock.set(16200);
  expect(session.snapshot(TARGET_A).pending.isEmpty(),"request C expires");
  expect(session.setSetting(TARGET_A,EarbudCommands.CONVERSATION_AWARENESS,2),"request D queued");
  probe.pendingWrites.get(3).accept(true);
  expect(probe.stateRequests==3,"late success triggers read-only refresh");
  expect(session.snapshot(TARGET_A).pending.contains(EarbudCommands.CONVERSATION_AWARENESS),
   "late success of C does not clear D");
 }

 static void resetAndDisconnectFenceStaleCallbacks(){
  AtomicLong clock=new AtomicLong(0);
  List<AapProbe> probes=new ArrayList<>();
  RecordingListener events=new RecordingListener();
  ControlSession session=new ControlSession(
   new Handler(Looper.getMainLooper()),
   clock::get,
   (dev,log,l)->{AapProbe p=new AapProbe(dev,log,l);probes.add(p);return p;},
   m->{},
   events);

  session.begin(TARGET_A);
  session.connect(new BluetoothDevice(TARGET_A),TARGET_A,1L);
  AapProbe first=probes.get(0);
  first.listener.ready();
  first.listener.packet(new byte[]{4,0,4,0,4,0,1,4,1,85,1,0});
  expect(Integer.valueOf(85).equals(session.snapshot(TARGET_A).leftBattery),"battery reported on first probe");

  // Reset (e.g. bridge takeover) replaces probe and invalidates all callbacks from first probe.
  session.reset();
  expect(first.stopped==1&&!session.isReady()&&!session.isConnectingOrConnected(),"reset stops old probe");
  session.connect(new BluetoothDevice(TARGET_A),TARGET_A,2L);
  AapProbe second=probes.get(1);

  events.events.clear();
  events.packets.clear();
  first.listener.ready();
  first.listener.packet(scalarReport(EarbudCommands.EAR_DETECTION,1));
  first.listener.ended();
  expect(events.events.isEmpty()&&events.packets.isEmpty(),"stale callbacks from reset probe are ignored");
  expect(!session.isReady()&&session.isConnectingOrConnected(),"stale ended() cannot clear new connecting probe");

  second.listener.ready();
  expect(session.isReady()&&session.snapshot(TARGET_A).leftBattery==null,
   "reconnected session requires fresh battery report");

  // Target switch / stop fences further callbacks and writes.
  session.stop();
  expect(second.stopped==1&&!session.isReady(),"stop closes active probe");
  second.listener.packet(scalarReport(EarbudCommands.EAR_DETECTION,1));
  second.listener.ended();
  expect(events.events.size()==1,"ended() after stop is ignored");
  expect(!session.refreshState(TARGET_A)&&!session.setSetting(TARGET_B,EarbudCommands.EAR_DETECTION,1),
   "stopped session rejects refresh and setting writes");
 }

 static void sendPacketRespectsSessionAndCallerGuards(){
  AtomicLong clock=new AtomicLong(0);
  List<AapProbe> probes=new ArrayList<>();
  RecordingListener events=new RecordingListener();
  ControlSession session=new ControlSession(
   new Handler(Looper.getMainLooper()),
   clock::get,
   (dev,log,l)->{AapProbe p=new AapProbe(dev,log,l);probes.add(p);return p;},
   m->{},
   events);

  byte[] pkt=new byte[]{4,0,4,0,9,0,6,1,0,0,0};
  expect(!session.sendPacket(pkt,()->true,ok->{}),"sendPacket returns false when disconnected");

  session.connect(new BluetoothDevice(TARGET_A),TARGET_A,1L);
  AapProbe probe=probes.get(0);
  probe.holdWrites=true;
  AtomicBoolean callerAllowed=new AtomicBoolean(true);
  List<Boolean> results=new ArrayList<>();
  expect(session.sendPacket(pkt,callerAllowed::get,results::add),"sendPacket queued on active probe");
  callerAllowed.set(false);
  probe.pendingWrites.get(0).accept(true);
  expect(probe.packets.isEmpty()&&results.isEmpty(),"false caller guard blocks write and callback");

  callerAllowed.set(true);
  expect(session.sendPacket(pkt,callerAllowed::get,results::add),"second sendPacket queued");
  session.reset();
  probe.pendingWrites.get(1).accept(true);
  expect(probe.packets.isEmpty()&&results.isEmpty(),"reset fences queued packet write and callback");
 }

 public static void main(String[] args){
  switch(args[0]){
   case "timeout-retry":timeoutRetryIgnoresStaleFailure();break;
   case "reset-fence":resetAndDisconnectFenceStaleCallbacks();break;
   case "send-guards":sendPacketRespectsSessionAndCallerGuards();break;
   default:throw new AssertionError("unknown scenario: "+args[0]);
  }
  System.out.println("PASS: control session "+args[0]);
 }
}''',
}


class ControlSessionTests(unittest.TestCase):
    def test_timeout_retry_ignores_stale_failure(self):
        self.run_scenario('timeout-retry')

    def test_reset_and_disconnect_fence_stale_callbacks(self):
        self.run_scenario('reset-fence')

    def test_send_packet_respects_session_and_caller_guards(self):
        self.run_scenario('send-guards')

    def run_scenario(self, scenario):
        jdk = Path(os.environ['JAVA_HOME']) / 'bin' if os.environ.get('JAVA_HOME') else None
        javac = shutil.which('javac', path=str(jdk) if jdk else None)
        java = shutil.which('java', path=str(jdk) if jdk else None)
        if not javac or not java:
            self.skipTest('JDK required')
        with tempfile.TemporaryDirectory(prefix='linkpods-control-') as directory:
            output = Path(directory)
            files = []
            for name, content in STUBS.items():
                target = output / name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text(content, encoding='utf-8')
                files.append(str(target))
            package = ROOT / 'apps/android/src' / PACKAGE
            names = ('ControlSession', 'EarbudCommands', 'EarbudSnapshot',
                     'AirPodsModels', 'AirPodsInformation', 'AapPackets')
            files += [str(package / (name + '.java')) for name in names]
            compiled = subprocess.run([javac, '-encoding', 'UTF-8', '-d', str(output), *files],
                                      capture_output=True, text=True, timeout=30)
            self.assertEqual(compiled.returncode, 0, compiled.stderr)
            result = subprocess.run([java, '-cp', str(output),
                                     'local.airpods.handoffprobe.ControlSessionTest', scenario],
                                    capture_output=True, text=True, timeout=10)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
