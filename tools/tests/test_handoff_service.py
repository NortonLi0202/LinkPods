"""Run the production service lifecycle against host-only Android boundaries."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
PACKAGE = 'local/airpods/handoffprobe/'
STUBS = {
    'android/content/Context.java': '''package android.content;
import java.io.*; import java.util.*;
public class Context {
 public static final int MODE_PRIVATE=0,MODE_APPEND=32768,RECEIVER_EXPORTED=2;
 public final Map<Class<?>,Object> services=new HashMap<>();
 public <T>T getSystemService(Class<T> type){return type.cast(services.get(type));}
 public Context getApplicationContext(){return this;}
 public java.util.concurrent.Executor getMainExecutor(){return Runnable::run;}
 public void registerReceiver(BroadcastReceiver r,IntentFilter f){}
 public void registerReceiver(BroadcastReceiver r,IntentFilter f,int flags){}
 public void unregisterReceiver(BroadcastReceiver r){}
 public FileOutputStream openFileOutput(String name,int mode)throws IOException{throw new IOException("host logging disabled");}
 public SharedPreferences getSharedPreferences(String name,int mode){return new SharedPreferences();}
}''',
    'android/content/SharedPreferences.java': '''package android.content;
public class SharedPreferences {public Editor edit(){return new Editor();}
 public static class Editor {public Editor putString(String k,String v){return this;}public void apply(){}}}''',
    'android/content/Intent.java': '''package android.content;
import java.util.*;
public class Intent {
 private final Map<String,Object> extras=new HashMap<>();
 public Intent putExtra(String k,String v){extras.put(k,v);return this;}
 public String getStringExtra(String k){return (String)extras.get(k);}
 public boolean getBooleanExtra(String k,boolean d){return extras.containsKey(k)?(Boolean)extras.get(k):d;}
 public int getIntExtra(String k,int d){return extras.containsKey(k)?(Integer)extras.get(k):d;}
 public <T>T getParcelableExtra(String k){return (T)extras.get(k);}
 public <T>T getParcelableExtra(String k,Class<T> t){return t.cast(extras.get(k));}
}''',
    'android/content/ComponentName.java': 'package android.content; public class ComponentName {public ComponentName(Context c,Class<?> t){}}',
    'android/content/IntentFilter.java': 'package android.content; public class IntentFilter {public IntentFilter(String a){}}',
    'android/content/BroadcastReceiver.java': 'package android.content; public abstract class BroadcastReceiver {public abstract void onReceive(Context c,Intent i);}',
    'android/app/Service.java': '''package android.app;
public class Service extends android.content.Context {
 public static final int START_NOT_STICKY=2;
 public void onCreate(){} public void onDestroy(){} public void stopSelf(){}
 public void startForeground(int id,Notification n){}
 public int onStartCommand(android.content.Intent i,int flags,int id){return 2;}
 public android.os.IBinder onBind(android.content.Intent i){return null;}
}''',
    'android/app/Notification.java': '''package android.app;
public class Notification {public static class Builder {
 public Builder(android.content.Context c,String id){}
 public Builder setSmallIcon(int i){return this;} public Builder setContentTitle(String s){return this;}
 public Builder setContentText(String s){return this;} public Builder setOngoing(boolean b){return this;}
 public Notification build(){return new Notification();}}}''',
    'android/app/NotificationChannel.java': 'package android.app; public class NotificationChannel {public NotificationChannel(String a,String b,int c){}}',
    'android/app/NotificationManager.java': '''package android.app; public class NotificationManager {
 public static final int IMPORTANCE_LOW=2; public void createNotificationChannel(NotificationChannel c){} public void notify(int i,Notification n){} }''',
    'android/R.java': 'package android; public class R {public static class drawable {public static final int stat_sys_data_bluetooth=1;}}',
    'android/bluetooth/BluetoothAdapter.java': '''package android.bluetooth;
public class BluetoothAdapter {
 public static boolean checkBluetoothAddress(String s){return s!=null&&s.matches("[0-9A-F]{2}(:[0-9A-F]{2}){5}");}
 public BluetoothDevice getRemoteDevice(String a){return new BluetoothDevice(a);}
 public void closeProfileProxy(int k,BluetoothProfile p){}
 public boolean getProfileProxy(android.content.Context c,BluetoothProfile.ServiceListener l,int k){return true;}
 public boolean isEnabled(){return true;}
}''',
    'android/bluetooth/BluetoothDevice.java': '''package android.bluetooth;
public class BluetoothDevice {public static final String EXTRA_DEVICE="device";public final String address;public BluetoothDevice(String a){address=a;}}''',
    'android/bluetooth/BluetoothManager.java': 'package android.bluetooth; public class BluetoothManager {public BluetoothAdapter getAdapter(){return new BluetoothAdapter();}}',
    'android/bluetooth/BluetoothProfile.java': '''package android.bluetooth;
public interface BluetoothProfile {
 int A2DP=2,HEADSET=1,STATE_DISCONNECTED=0; String EXTRA_STATE="state";
 int getConnectionState(BluetoothDevice d);
 interface ServiceListener {void onServiceConnected(int k,BluetoothProfile p);void onServiceDisconnected(int k);}
}''',
    'android/media/session/MediaSession.java': 'package android.media.session; public class MediaSession {public static class Token {}}',
    'android/media/session/PlaybackState.java': '''package android.media.session;
public class PlaybackState {public static final int STATE_PLAYING=3,STATE_BUFFERING=6,STATE_CONNECTING=8;
 private final int state;public PlaybackState(int s){state=s;}public int getState(){return state;}}''',
    'android/media/session/MediaController.java': '''package android.media.session;
import java.util.*;
public class MediaController {
 public final Set<Callback> callbacks=new HashSet<>(); public int registered,unregistered;
 private final MediaSession.Token token=new MediaSession.Token();
 public String getPackageName(){return "host.player";}
 public MediaSession.Token getSessionToken(){return token;}
 public PlaybackState getPlaybackState(){return new PlaybackState(3);}
 public void registerCallback(Callback c,android.os.Handler h){callbacks.add(c);registered++;}
 public void unregisterCallback(Callback c){callbacks.remove(c);unregistered++;}
 public TransportControls getTransportControls(){return new TransportControls();}
 public static class Callback {public void onPlaybackStateChanged(PlaybackState s){}}
 public static class TransportControls {public void pause(){}}
}''',
    'android/media/session/MediaSessionManager.java': '''package android.media.session;
import java.util.*;
public class MediaSessionManager {
 public List<MediaController> active=new ArrayList<>();public boolean rejectRegistration;public int removed;
 public List<MediaController> getActiveSessions(android.content.ComponentName c){return active;}
 public void addOnActiveSessionsChangedListener(OnActiveSessionsChangedListener l,android.content.ComponentName c,android.os.Handler h){if(rejectRegistration)throw new SecurityException("permission revoked");}
 public void removeOnActiveSessionsChangedListener(OnActiveSessionsChangedListener l){removed++;}
 public interface OnActiveSessionsChangedListener {void onActiveSessionsChanged(List<MediaController> l);}
}''',
    'android/media/AudioManager.java': '''package android.media;
public class AudioManager {public static final int MODE_IN_CALL=2,MODE_IN_COMMUNICATION=3;
 public interface OnModeChangedListener {void onModeChanged(int mode);}
 public void addOnModeChangedListener(java.util.concurrent.Executor e,OnModeChangedListener l){}
 public void removeOnModeChangedListener(OnModeChangedListener l){} }''',
    'android/media/MediaMetadata.java': 'package android.media; public class MediaMetadata {}',
    'android/media/AudioAttributes.java': 'package android.media; public class AudioAttributes {}',
    'android/media/AudioPlaybackConfiguration.java': 'package android.media; public class AudioPlaybackConfiguration {}',
    'android/view/KeyEvent.java': 'package android.view; public class KeyEvent {}',
    'android/os/IBinder.java': 'package android.os; public interface IBinder {}',
    'android/os/Build.java': 'package android.os; public class Build {public static class VERSION {public static final int SDK_INT=36;}}',
    'android/os/Looper.java': 'package android.os; public class Looper {public static Looper getMainLooper(){return new Looper();}}',
    'android/os/SystemClock.java': 'package android.os; public class SystemClock {public static long elapsedRealtime(){return 0;}}',
    'android/os/Handler.java': '''package android.os; public class Handler {
 public Handler(Looper l){}public boolean post(Runnable r){r.run();return true;}
 public boolean postDelayed(Runnable r,long d){return true;}public void removeCallbacksAndMessages(Object t){} }''',
    'android/util/Log.java': 'package android.util; public class Log {public static int i(String t,String m){return 0;}public static int e(String t,String m,Throwable e){return 0;}}',
    'org/lsposed/hiddenapibypass/HiddenApiBypass.java': 'package org.lsposed.hiddenapibypass; public class HiddenApiBypass {public static boolean addHiddenApiExemptions(String... s){return true;}}',
    PACKAGE + 'PlaybackListener.java': 'package local.airpods.handoffprobe; class PlaybackListener {}',
    PACKAGE + 'RootEnvironment.java': '''package local.airpods.handoffprobe;
class RootEnvironment {
 static final java.util.concurrent.ExecutorService WORK=java.util.concurrent.Executors.newSingleThreadExecutor();
 static class Lease {boolean sameInstance(Lease l){return this==l;}}
 static class Prepared {boolean bridgeOwned;String targetAddress,local,adapterId,missing,diagnostic;Lease lease;}
 static boolean bridgeUsable(Prepared p){return false;}static boolean acceptBridge(Prepared p){return false;}
 static void queueBridgeStop(Prepared p){}static void markBridgeStopping(Prepared p){}
 static boolean stopOwnedBridge(Prepared p){return true;}
 static Prepared prepare(android.content.Context c,String a,boolean r,boolean t){throw new AssertionError("unexpected Root request");}
}''',
    PACKAGE + 'AapProbe.java': '''package local.airpods.handoffprobe;
class AapProbe {
 interface Logger {void write(String m);}enum Failure {COMPATIBILITY_REQUIRED,OTHER}
 interface Listener {void ready();void packet(byte[] b);void ended();default void failure(Failure f){}}
 final java.util.List<byte[]> packets=new java.util.ArrayList<>();
 final java.util.List<Runnable> queued=new java.util.ArrayList<>();boolean holdWrites;
 AapProbe(android.bluetooth.BluetoothDevice d,Logger l,Listener x){}
 void sendPacketAsync(byte[] b,java.util.function.Consumer<Boolean> done){sendPacketAsync(b,()->true,done);}
 void sendPacketAsync(byte[] b,java.util.function.BooleanSupplier guard,java.util.function.Consumer<Boolean> done){
  Runnable task=()->{boolean ok=guard.getAsBoolean();if(ok)packets.add(b);done.accept(ok);};
  if(holdWrites)queued.add(task);else task.run();
 }
 void flush(){for(Runnable task:new java.util.ArrayList<>(queued))task.run();queued.clear();}
 void requestState(){}void start(){throw new AssertionError("ready connection must be reused");}void stop(){}
}''',
    PACKAGE + 'HandoffLifecycleTest.java': '''package local.airpods.handoffprobe;
import android.content.*;import android.media.session.*;import java.lang.reflect.*;import java.util.*;
public class HandoffLifecycleTest {
 static final String TARGET="AA:BB:CC:DD:EE:01",SELF="AA:BB:CC:DD:EE:02",PEER="AA:BB:CC:DD:EE:03";
 static void expect(boolean b,String why){if(!b)throw new AssertionError(why);}
 static Object get(Object o,String n)throws Exception{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}
 static void set(Object o,String n,Object v)throws Exception{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);f.set(o,v);}
 static HandoffService service(MediaSessionManager manager)throws Exception{
  HandoffService s=new HandoffService();HandoffService.current=s;s.services.put(MediaSessionManager.class,manager);
  set(s,"adapter",new android.bluetooth.BluetoothAdapter());return s;
 }
 static void upgradeWithLocalIdentity(boolean initiallyKnown)throws Exception{
  HandoffService s=service(new MediaSessionManager());
  set(s,"deviceConfig",DeviceConfig.create(TARGET,initiallyKnown?SELF:"",PEER));set(s,"selfMac",initiallyKnown?SELF:"");
  set(s,"peerMac",PEER);set(s,"ready",true);
  ((Set<String>)get(s,"peers")).add(PEER);
  ConnectionPolicy connection=(ConnectionPolicy)get(s,"connection");
  connection.start(TARGET,false);connection.ready(connection.generation(),TARGET);
  ((RoutingPolicy)get(s,"policy")).onPlayback("pending.player",3);
  AapProbe probe=new AapProbe(null,m->{},null);set(s,"session",probe);
  Intent request=new Intent().putExtra("address",TARGET).putExtra("local_mac",SELF).putExtra("peer_address",PEER)
   .putExtra(HandoffService.EXTRA_SESSION_MODE,"AUTO_HANDOFF");
  s.onStartCommand(request,0,1);
  expect(get(s,"session")==probe,"mode upgrade must reuse ready control session");
  boolean announced=false,media=false,take=false;
  byte[] expected=RoutingPackets.announce(SELF,PEER);
  for(byte[] packet:probe.packets){
   if(Arrays.equals(packet,expected))announced=true;
   if(Arrays.equals(packet,RoutingPackets.mediaInformation(SELF,PEER,true)))media=true;
   if(Arrays.equals(packet,RoutingPackets.ownership(true)))take=true;
  }
  expect(announced,"upgrade must announce known peer using current local address");
  expect(media,"upgrade must send current media state after announcing peer");
  expect(take,"upgrade must finish pending take without a fresh peer list");
 }
 static void failedWatchCleansPlayers()throws Exception{
  MediaSessionManager manager=new MediaSessionManager();manager.rejectRegistration=true;
  MediaController controller=new MediaController();manager.active.add(controller);
  HandoffService s=service(manager);set(s,"mode",SessionMode.AUTO_HANDOFF);
  s.startSessionWatch();
  expect(controller.registered==1,"test must reach player callback registration before listener failure");
  expect(controller.callbacks.isEmpty(),"failed watch start must immediately unregister player callbacks");
  expect(((Map<?,?>)get(s,"players")).isEmpty(),"failed watch must discard player controllers");
  expect(((Map<?,?>)get(s,"playerCallbacks")).isEmpty(),"failed watch must discard callback map");
  expect(!((RoutingPolicy)get(s,"policy")).hasPlayback(),"failed watch must discard seeded playback policy");
  expect(manager.removed==0,"listener registration failed, so no active listener must be removed");
  manager.rejectRegistration=false;s.startSessionWatch();
  expect(controller.callbacks.size()==1,"retry must register one fresh player callback");
  s.stopSessionWatch();expect(controller.callbacks.isEmpty()&&manager.removed==1,"successful retry must stop normally");
 }
 static void stopPartialWatch()throws Exception{
  MediaSessionManager manager=new MediaSessionManager();HandoffService s=service(manager);
  MediaController controller=new MediaController();MediaSession.Token token=controller.getSessionToken();
  MediaController.Callback callback=new MediaController.Callback();controller.registerCallback(callback,null);
  ((Map<MediaSession.Token,MediaController>)get(s,"players")).put(token,controller);
  ((Map<MediaSession.Token,MediaController.Callback>)get(s,"playerCallbacks")).put(token,callback);
  ((RoutingPolicy)get(s,"policy")).seed(token.toString(),3);
  s.stopSessionWatch();s.stopSessionWatch();
  expect(controller.callbacks.isEmpty(),"stop must release partial player callbacks even when watching=false");
  expect(controller.unregistered==1,"repeated stop must not unregister a player twice");
  expect(((Map<?,?>)get(s,"players")).isEmpty()&&((Map<?,?>)get(s,"playerCallbacks")).isEmpty(),"stop must clear player maps");
  expect(!((RoutingPolicy)get(s,"policy")).hasPlayback(),"stop must clear partial playback policy");
  expect(manager.removed==0,"partial watch has no registered active listener");
 }
 static void modeRoundTripInvalidatesQueuedRouting()throws Exception{
  HandoffService s=service(new MediaSessionManager());set(s,"mode",SessionMode.AUTO_HANDOFF);
  set(s,"deviceConfig",DeviceConfig.create(TARGET,SELF,PEER));set(s,"selfMac",SELF);set(s,"peerMac",PEER);set(s,"ready",true);
  ConnectionPolicy connection=(ConnectionPolicy)get(s,"connection");connection.start(TARGET,false);connection.ready(connection.generation(),TARGET);
  AapProbe probe=new AapProbe(null,m->{},null);probe.holdWrites=true;set(s,"session",probe);
  Method send=HandoffService.class.getDeclaredMethod("send",byte[].class,String.class);send.setAccessible(true);
  byte[] packet=RoutingPackets.ownership(true);send.invoke(s,packet,"test-routing");
  Intent request=new Intent().putExtra("address",TARGET).putExtra("local_mac",SELF).putExtra("peer_address",PEER);
  s.onStartCommand(request.putExtra(HandoffService.EXTRA_SESSION_MODE,"CONTROL_ONLY"),0,1);
  s.onStartCommand(request.putExtra(HandoffService.EXTRA_SESSION_MODE,"AUTO_HANDOFF"),0,2);
  probe.flush();
  expect(probe.packets.isEmpty(),"service mode round trip must invalidate routing already queued on the reused session");
  send.invoke(s,packet,"fresh-routing");probe.flush();
  expect(probe.packets.size()==1&&Arrays.equals(probe.packets.get(0),packet),"fresh routing after mode round trip must work");
 }
 public static void main(String[] args)throws Exception{
  switch(args[0]){
   case "identity":upgradeWithLocalIdentity(false);break;
   case "known-peer":upgradeWithLocalIdentity(true);break;
   case "watch-failure":failedWatchCleansPlayers();break;
   case "partial-stop":stopPartialWatch();break;
   case "mode-round-trip":modeRoundTripInvalidatesQueuedRouting();break;
   default:throw new AssertionError("unknown scenario");
  }
  System.out.println("PASS: service lifecycle "+args[0]);
 }
}''',
}


class HandoffLifecycleTests(unittest.TestCase):
    def test_ready_session_upgrade_announces_new_local_identity(self):
        self.run_scenario('identity')

    def test_ready_session_upgrade_announces_known_peer(self):
        self.run_scenario('known-peer')

    def test_failed_watch_start_releases_registered_players(self):
        self.run_scenario('watch-failure')

    def test_stopping_partial_watch_is_complete_and_idempotent(self):
        self.run_scenario('partial-stop')

    def test_mode_round_trip_invalidates_queued_routing(self):
        self.run_scenario('mode-round-trip')

    def run_scenario(self, scenario):
        jdk = Path(os.environ['JAVA_HOME']) / 'bin' if os.environ.get('JAVA_HOME') else None
        javac = str(jdk / 'javac.exe') if jdk else shutil.which('javac')
        java = str(jdk / 'java.exe') if jdk else shutil.which('java')
        if not javac or not java:
            self.skipTest('JDK required')
        with tempfile.TemporaryDirectory(prefix='linkpods-service-') as directory:
            output = Path(directory)
            files = []
            for name, content in STUBS.items():
                target = output / name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text(content, encoding='utf-8')
                files.append(str(target))
            package = ROOT / 'apps/android/src' / PACKAGE
            names = ('HandoffService', 'SessionMode', 'RoutingPolicy',
                     'AudioLinkPolicy', 'PeerRoutingPolicy', 'DeviceConfig', 'ConnectionPolicy',
                     'RoutingPackets', 'AapPackets', 'SmartRouting', 'EarbudCommands',
                     'EarbudSnapshot', 'AirPodsModels', 'AirPodsInformation', 'BackendPolicy')
            files += [str(package / (name + '.java')) for name in names]
            if (package / 'SessionStatus.java').is_file():
                files.append(str(package / 'SessionStatus.java'))
            compiled = subprocess.run([javac, '-encoding', 'UTF-8', '-d', str(output), *files],
                                      capture_output=True, text=True, timeout=30)
            self.assertEqual(compiled.returncode, 0, compiled.stderr)
            result = subprocess.run([java, '-cp', str(output),
                                     'local.airpods.handoffprobe.HandoffLifecycleTest', scenario],
                                    capture_output=True, text=True, timeout=10)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
