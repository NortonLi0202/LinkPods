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
    'android/Manifest.java': '''package android;
public class Manifest { public static class permission {
 public static final String BLUETOOTH_CONNECT="android.permission.BLUETOOTH_CONNECT";
}}''',
    'android/content/pm/PackageManager.java': 'package android.content.pm; public class PackageManager {public static final int PERMISSION_GRANTED=0;}',
    'android/content/Context.java': '''package android.content;
import java.io.*; import java.util.*;
public class Context {
 public static final int MODE_PRIVATE=0,MODE_APPEND=32768,RECEIVER_EXPORTED=2;
 public final Map<Class<?>,Object> services=new HashMap<>();
 public int registeredReceivers,unregisteredReceivers;
 public BroadcastReceiver lastReceiver;
 public boolean bluetoothPermissionGranted=true;
 public <T>T getSystemService(Class<T> type){return type.cast(services.get(type));}
 public Context getApplicationContext(){return this;}
 public java.util.concurrent.Executor getMainExecutor(){return Runnable::run;}
 public int checkSelfPermission(String p){return bluetoothPermissionGranted?android.content.pm.PackageManager.PERMISSION_GRANTED:-1;}
 public void registerReceiver(BroadcastReceiver r,IntentFilter f){registeredReceivers++;lastReceiver=r;}
 public void registerReceiver(BroadcastReceiver r,IntentFilter f,int flags){registeredReceivers++;lastReceiver=r;}
 public void unregisterReceiver(BroadcastReceiver r){unregisteredReceivers++;}
 public FileOutputStream openFileOutput(String name,int mode)throws IOException{throw new IOException("host logging disabled");}
 public SharedPreferences getSharedPreferences(String name,int mode){return new SharedPreferences();}
}''',
    'android/content/SharedPreferences.java': '''package android.content;
public class SharedPreferences {public Editor edit(){return new Editor();}
 public static class Editor {public Editor putString(String k,String v){return this;}public void apply(){}}}''',
    'android/content/Intent.java': '''package android.content;
import java.util.*;
public class Intent {
 private String action;
 private final Map<String,Object> extras=new HashMap<>();
 public Intent(){}
 public Intent(String a){action=a;}
 public String getAction(){return action;}
 public Intent putExtra(String k,String v){extras.put(k,v);return this;}
 public Intent putExtra(String k,boolean v){extras.put(k,v);return this;}
 public String getStringExtra(String k){return (String)extras.get(k);}
 public boolean getBooleanExtra(String k,boolean d){return extras.containsKey(k)?(Boolean)extras.get(k):d;}
 public int getIntExtra(String k,int d){return extras.containsKey(k)?(Integer)extras.get(k):d;}
 public <T>T getParcelableExtra(String k){return (T)extras.get(k);}
 public <T>T getParcelableExtra(String k,Class<T> t){return t.cast(extras.get(k));}
}''',
    'android/content/ComponentName.java': 'package android.content; public class ComponentName {public ComponentName(Context c,Class<?> t){}}',
    'android/content/IntentFilter.java': 'package android.content; public class IntentFilter {public IntentFilter(){}public IntentFilter(String a){}public void addAction(String a){}}',
    'android/content/BroadcastReceiver.java': 'package android.content; public abstract class BroadcastReceiver {public abstract void onReceive(Context c,Intent i);}',
    'android/service/notification/NotificationListenerService.java': '''package android.service.notification;
public class NotificationListenerService {
 public void onListenerConnected(){}
 public void onListenerDisconnected(){}
}''',
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
    'android/app/NotificationManager.java': '''package android.app;
public class NotificationManager {
 public static final int IMPORTANCE_LOW=2;
 public boolean notificationAccessGranted=true;
 public void createNotificationChannel(NotificationChannel c){}
 public void notify(int i,Notification n){}
 public boolean isNotificationListenerAccessGranted(android.content.ComponentName c){return notificationAccessGranted;}
}''',
    'android/R.java': 'package android; public class R {public static class drawable {public static final int stat_sys_data_bluetooth=1;}}',
    'android/bluetooth/BluetoothAdapter.java': '''package android.bluetooth;
import java.util.*;
public class BluetoothAdapter {
 public static final String ACTION_STATE_CHANGED="android.bluetooth.adapter.action.STATE_CHANGED";
 public int proxyRequests;
 public boolean enabled=true;
 public BluetoothProfile.ServiceListener lastA2dpListener,lastHeadsetListener;
 public final List<BluetoothProfile> closedProxies=new ArrayList<>();
 public static boolean checkBluetoothAddress(String s){return s!=null&&s.matches("[0-9A-F]{2}(:[0-9A-F]{2}){5}");}
 public BluetoothDevice getRemoteDevice(String a){return new BluetoothDevice(a);}
 public void closeProfileProxy(int k,BluetoothProfile p){if(p!=null)closedProxies.add(p);}
 public boolean getProfileProxy(android.content.Context c,BluetoothProfile.ServiceListener l,int k){
  proxyRequests++;
  if(k==BluetoothProfile.A2DP)lastA2dpListener=l;
  if(k==BluetoothProfile.HEADSET)lastHeadsetListener=l;
  return true;
 }
 public boolean isEnabled(){return enabled;}
}''',
    'android/bluetooth/BluetoothDevice.java': '''package android.bluetooth;
public class BluetoothDevice {public static final String EXTRA_DEVICE="device";public final String address;public BluetoothDevice(String a){address=a;}public String getAddress(){return address;}}''',
    'android/bluetooth/BluetoothA2dp.java': 'package android.bluetooth; public class BluetoothA2dp {public static final String ACTION_CONNECTION_STATE_CHANGED="android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED";}',
    'android/bluetooth/BluetoothHeadset.java': 'package android.bluetooth; public class BluetoothHeadset {public static final String ACTION_CONNECTION_STATE_CHANGED="android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED",ACTION_AUDIO_STATE_CHANGED="android.bluetooth.headset.profile.action.AUDIO_STATE_CHANGED";}',
    'android/bluetooth/BluetoothManager.java': 'package android.bluetooth; public class BluetoothManager {public final BluetoothAdapter adapter=new BluetoothAdapter();public BluetoothAdapter getAdapter(){return adapter;}}',
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
 public final Set<Callback> callbacks=new HashSet<>(); public int registered,unregistered,paused;
 private int playbackState=3;
 private final MediaSession.Token token=new MediaSession.Token();
 public MediaController(){}
 public MediaController(int state){playbackState=state;}
 public String getPackageName(){return "host.player";}
 public MediaSession.Token getSessionToken(){return token;}
 public PlaybackState getPlaybackState(){return new PlaybackState(playbackState);}
 public void registerCallback(Callback c,android.os.Handler h){callbacks.add(c);registered++;}
 public void unregisterCallback(Callback c){callbacks.remove(c);unregistered++;}
 public TransportControls getTransportControls(){return new TransportControls(this);}
 public static class Callback {public void onPlaybackStateChanged(PlaybackState s){}}
 public static class TransportControls {
  private final MediaController owner;
  TransportControls(MediaController o){owner=o;}
  public void pause(){owner.paused++;}
 }
}''',
    'android/media/session/MediaSessionManager.java': '''package android.media.session;
import java.util.*;
public class MediaSessionManager {
 public List<MediaController> active=new ArrayList<>();public boolean rejectRegistration;public int added,removed;
 public OnActiveSessionsChangedListener lastListener;
 public List<MediaController> getActiveSessions(android.content.ComponentName c){return active;}
 public void addOnActiveSessionsChangedListener(OnActiveSessionsChangedListener l,android.content.ComponentName c,android.os.Handler h){
  if(rejectRegistration)throw new SecurityException("permission revoked");
  added++;lastListener=l;
 }
 public void removeOnActiveSessionsChangedListener(OnActiveSessionsChangedListener l){removed++;}
 public interface OnActiveSessionsChangedListener {void onActiveSessionsChanged(List<MediaController> l);}
}''',
    'android/media/AudioManager.java': '''package android.media;
import java.util.*;
public class AudioManager {
 public static final int GET_DEVICES_OUTPUTS=2,MODE_NORMAL=0,MODE_IN_CALL=2,MODE_IN_COMMUNICATION=3;
 public interface OnModeChangedListener {void onModeChanged(int mode);}
 public interface OnCommunicationDeviceChangedListener {void onCommunicationDeviceChanged(AudioDeviceInfo device);}
 public static abstract class AudioPlaybackCallback {public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs){}}
 public AudioDeviceInfo[] outputs=new AudioDeviceInfo[0];
 public AudioDeviceInfo communicationDevice;
 public List<AudioPlaybackConfiguration> playbacks=Collections.emptyList();
 public List<AudioDeviceInfo> attributesDevices=Collections.emptyList();
 public int mode=MODE_NORMAL,deviceCallbacks,playbackCallbacks,modeListeners,commListeners;
 public AudioDeviceInfo[] getDevices(int f){return outputs;}
 public int getMode(){return mode;}
 public AudioDeviceInfo getCommunicationDevice(){return communicationDevice;}
 public List<AudioPlaybackConfiguration> getActivePlaybackConfigurations(){return playbacks;}
 public List<AudioDeviceInfo> getAudioDevicesForAttributes(AudioAttributes a){return attributesDevices;}
 public void registerAudioDeviceCallback(AudioDeviceCallback c,android.os.Handler h){deviceCallbacks++;}
 public void unregisterAudioDeviceCallback(AudioDeviceCallback c){deviceCallbacks--;}
 public void registerAudioPlaybackCallback(AudioPlaybackCallback c,android.os.Handler h){playbackCallbacks++;}
 public void unregisterAudioPlaybackCallback(AudioPlaybackCallback c){playbackCallbacks--;}
 public void addOnModeChangedListener(java.util.concurrent.Executor e,OnModeChangedListener l){modeListeners++;}
 public void removeOnModeChangedListener(OnModeChangedListener l){modeListeners--;}
 public void addOnCommunicationDeviceChangedListener(java.util.concurrent.Executor e,OnCommunicationDeviceChangedListener l){commListeners++;}
 public void removeOnCommunicationDeviceChangedListener(OnCommunicationDeviceChangedListener l){commListeners--;}
}''',
    'android/media/MediaMetadata.java': 'package android.media; public class MediaMetadata {}',
    'android/media/AudioAttributes.java': '''package android.media;
public class AudioAttributes {
 public static final int USAGE_UNKNOWN=0,USAGE_MEDIA=1,USAGE_GAME=14,CONTENT_TYPE_MUSIC=2;
 private final int usage;
 public AudioAttributes(){this(USAGE_MEDIA);}
 public AudioAttributes(int u){usage=u;}
 public int getUsage(){return usage;}
 public static class Builder {
  private int usage=USAGE_MEDIA;
  public Builder setUsage(int u){usage=u;return this;}
  public Builder setContentType(int c){return this;}
  public AudioAttributes build(){return new AudioAttributes(usage);}
 }
}''',
    'android/media/AudioDeviceInfo.java': '''package android.media;
public class AudioDeviceInfo {
 public static final int TYPE_BUILTIN_EARPIECE=1,TYPE_BUILTIN_SPEAKER=2,TYPE_WIRED_HEADSET=3,TYPE_WIRED_HEADPHONES=4,TYPE_BLUETOOTH_SCO=7,TYPE_BLUETOOTH_A2DP=8,TYPE_USB_DEVICE=11,TYPE_USB_HEADSET=22,TYPE_HEARING_AID=23,TYPE_BLE_HEADSET=26,TYPE_BLE_SPEAKER=27,TYPE_BLE_BROADCAST=30;
 public final int type; public final String address,name;
 public AudioDeviceInfo(int t,String a,String n){type=t;address=a;name=n;}
 public int getType(){return type;}
 public String getAddress(){return address;}
 public CharSequence getProductName(){return name;}
}''',
    'android/media/AudioDeviceCallback.java': 'package android.media; public abstract class AudioDeviceCallback {public void onAudioDevicesAdded(AudioDeviceInfo[] a){}public void onAudioDevicesRemoved(AudioDeviceInfo[] r){}}',
    'android/media/AudioPlaybackConfiguration.java': '''package android.media;
public class AudioPlaybackConfiguration {
 public AudioAttributes attrs; public AudioDeviceInfo device;
 public AudioAttributes getAudioAttributes(){return attrs;}
 public AudioDeviceInfo getAudioDeviceInfo(){return device;}
}''',
    'android/view/KeyEvent.java': 'package android.view; public class KeyEvent {}',
    'android/os/IBinder.java': 'package android.os; public interface IBinder {}',
    'android/os/Build.java': 'package android.os; public class Build {public static class VERSION {public static final int SDK_INT=36;}}',
    'android/os/Looper.java': 'package android.os; public class Looper {public static Looper getMainLooper(){return new Looper();}}',
    'android/os/SystemClock.java': 'package android.os; public class SystemClock {public static long elapsedRealtime(){return 0;}}',
    'android/os/Handler.java': '''package android.os;
public class Handler {
 public final java.util.List<Runnable> delayed=new java.util.ArrayList<>();
 public Handler(Looper l){}public boolean post(Runnable r){r.run();return true;}
 public boolean postDelayed(Runnable r,long d){delayed.add(r);return true;}
 public void removeCallbacks(Runnable r){delayed.remove(r);}
 public void flushDelayed(){for(Runnable r:new java.util.ArrayList<>(delayed))r.run();delayed.clear();}
 public void removeCallbacksAndMessages(Object t){delayed.clear();}
}''',
    'android/util/Log.java': 'package android.util; public class Log {public static int i(String t,String m){return 0;}public static int e(String t,String m,Throwable e){return 0;}}',
    'org/lsposed/hiddenapibypass/HiddenApiBypass.java': 'package org.lsposed.hiddenapibypass; public class HiddenApiBypass {public static boolean addHiddenApiExemptions(String... s){return true;}}',
    PACKAGE + 'RootEnvironment.java': '''package local.airpods.handoffprobe;
class RootEnvironment {
 static final java.util.concurrent.ExecutorService WORK=java.util.concurrent.Executors.newSingleThreadExecutor();
 static final java.util.List<Prepared> stoppedBridges=new java.util.ArrayList<>();
 static boolean nextBridgeUsable=false,nextAcceptBridge=false;
 static class Lease {boolean sameInstance(Lease l){return this==l;}}
 static class Prepared {boolean bridgeOwned;String targetAddress="",local="",adapterId="",missing="",diagnostic="";Lease lease=new Lease();}
 static boolean bridgeUsable(Prepared p){return nextBridgeUsable&&p!=null;}
 static boolean acceptBridge(Prepared p){return nextAcceptBridge&&p!=null;}
 static void queueBridgeStop(Prepared p){if(p!=null&&p.bridgeOwned)stoppedBridges.add(p);}
 static void markBridgeStopping(Prepared p){}
 static boolean stopOwnedBridge(Prepared p){if(p!=null&&p.bridgeOwned)stoppedBridges.add(p);return true;}
 static Prepared prepare(android.content.Context c,String a,boolean r,boolean t){throw new AssertionError("unexpected Root request");}
}''',
    PACKAGE + 'AapProbe.java': '''package local.airpods.handoffprobe;
class AapProbe {
 interface Logger {void write(String m);}enum Failure {COMPATIBILITY_REQUIRED,OTHER}
 interface Listener {void ready();void packet(byte[] b);void ended();default void failure(Failure f){}}
 static final java.util.List<AapProbe> created=new java.util.ArrayList<>();
 static boolean failStart=false;
 final Listener listener;
 final java.util.List<byte[]> packets=new java.util.ArrayList<>();
 final java.util.List<Runnable> queued=new java.util.ArrayList<>();boolean holdWrites;
 int started,stopped;
 AapProbe(android.bluetooth.BluetoothDevice d,Logger l,Listener x){listener=x;created.add(this);}
 void sendPacketAsync(byte[] b,java.util.function.Consumer<Boolean> done){sendPacketAsync(b,()->true,done);}
 void sendPacketAsync(byte[] b,java.util.function.BooleanSupplier guard,java.util.function.Consumer<Boolean> done){
  Runnable task=()->{boolean ok=guard.getAsBoolean();if(ok)packets.add(b);done.accept(ok);};
  if(holdWrites)queued.add(task);else task.run();
 }
 void flush(){for(Runnable task:new java.util.ArrayList<>(queued))task.run();queued.clear();}
 void requestState(){}
 void start(){started++;if(failStart)throw new AssertionError("ready connection must be reused");}
 void stop(){stopped++;}
}''',
    PACKAGE + 'HandoffLifecycleTest.java': '''package local.airpods.handoffprobe;
import android.app.*;import android.bluetooth.*;import android.content.*;import android.media.*;import android.media.session.*;import android.os.*;import java.lang.reflect.*;import java.util.*;
public class HandoffLifecycleTest {
 static final String TARGET="AA:BB:CC:DD:EE:01",SELF="AA:BB:CC:DD:EE:02",PEER="AA:BB:CC:DD:EE:03";
 static void expect(boolean b,String why){if(!b)throw new AssertionError(why);}
 static Object get(Object o,String n)throws Exception{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}
 static void set(Object o,String n,Object v)throws Exception{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);f.set(o,v);}
 static HandoffService service(MediaSessionManager manager)throws Exception{
  HandoffService s=new HandoffService();HandoffService.current=s;s.services.put(MediaSessionManager.class,manager);
  s.services.put(AudioManager.class,new AudioManager());
  s.services.put(BluetoothManager.class,new BluetoothManager());
  s.services.put(NotificationManager.class,new NotificationManager());
  set(s,"adapter",new BluetoothAdapter());
  set(s,"audio",new AudioManager());
  return s;
 }
 static void upgradeWithLocalIdentity(boolean initiallyKnown)throws Exception{
  AapProbe.failStart=false;AapProbe.created.clear();
  HandoffService s=service(new MediaSessionManager());
  Intent initial=new Intent().putExtra("address",TARGET)
   .putExtra("local_mac",initiallyKnown?SELF:"").putExtra("peer_address",PEER)
   .putExtra(HandoffService.EXTRA_SESSION_MODE,"CONTROL_ONLY");
  s.onStartCommand(initial,0,1);
  expect(AapProbe.created.size()==1,"initial start creates one probe");
  AapProbe probe=AapProbe.created.get(0);
  probe.listener.ready();
  AapProbe.failStart=true;
  ((Set<String>)get(s,"peers")).add(PEER);
  ((RoutingPolicy)get(s,"policy")).onPlayback("pending.player",3);
  Intent request=new Intent().putExtra("address",TARGET).putExtra("local_mac",SELF).putExtra("peer_address",PEER)
   .putExtra(HandoffService.EXTRA_SESSION_MODE,"AUTO_HANDOFF");
  s.onStartCommand(request,0,2);
  expect(AapProbe.created.size()==1&&probe.stopped==0,"mode upgrade must reuse ready control session");
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
 static void modeRoundTripInvalidatesQueuedRouting()throws Exception{
  AapProbe.failStart=false;AapProbe.created.clear();
  HandoffService s=service(new MediaSessionManager());
  s.onStartCommand(new Intent().putExtra("address",TARGET).putExtra("local_mac",SELF).putExtra("peer_address",PEER)
   .putExtra(HandoffService.EXTRA_SESSION_MODE,"AUTO_HANDOFF"),0,1);
  AapProbe probe=AapProbe.created.get(0);
  probe.listener.ready();
  AapProbe.failStart=true;
  probe.holdWrites=true;
  Method send=HandoffService.class.getDeclaredMethod("send",byte[].class,String.class);send.setAccessible(true);
  byte[] packet=RoutingPackets.ownership(true);send.invoke(s,packet,"test-routing");
  Intent request=new Intent().putExtra("address",TARGET).putExtra("local_mac",SELF).putExtra("peer_address",PEER);
  s.onStartCommand(request.putExtra(HandoffService.EXTRA_SESSION_MODE,"CONTROL_ONLY"),0,2);
  s.onStartCommand(request.putExtra(HandoffService.EXTRA_SESSION_MODE,"AUTO_HANDOFF"),0,3);
  probe.flush();
  expect(probe.packets.isEmpty(),"service mode round trip must invalidate routing already queued on the reused session");
  send.invoke(s,packet,"fresh-routing");probe.flush();
  expect(probe.packets.size()==1&&Arrays.equals(probe.packets.get(0),packet),"fresh routing after mode round trip must work");
 }
 static void permissionRevokeAndRestoreResumesHandoff()throws Exception{
  AapProbe.failStart=false;AapProbe.created.clear();
  MediaSessionManager manager=new MediaSessionManager();
  NotificationManager nm=new NotificationManager();
  nm.notificationAccessGranted=false;
  HandoffService s=service(manager);s.services.put(NotificationManager.class,nm);
  s.onStartCommand(new Intent().putExtra("address",TARGET).putExtra("local_mac",SELF).putExtra("peer_address",PEER)
   .putExtra(HandoffService.EXTRA_SESSION_MODE,"AUTO_HANDOFF"),0,1);
  AapProbe probe=AapProbe.created.get(0);
  probe.listener.ready();
  AapProbe.failStart=true;
  ((Set<String>)get(s,"peers")).add(PEER);

  nm.notificationAccessGranted=true;
  PlaybackListener listener=new PlaybackListener();
  listener.onListenerConnected();
  expect(manager.added==1,"initial grant registers media listener once");
  expect(HandoffService.sessionStatus(TARGET).handoffDescription.contains("运行中"),"UI reports handoff running when watch active");

  // Path 1: Android revokes access -> removes server listener and emits empty list.
  nm.notificationAccessGranted=false;
  manager.lastListener.onActiveSessionsChanged(Collections.emptyList());
  expect(manager.removed==1,"revoked empty-list callback removes old listener registration");
  expect(HandoffService.sessionStatus(TARGET).handoffDescription.contains("等待媒体权限"),"UI reports waiting for media access after revocation");

  // Re-grant -> onListenerConnected must register a fresh listener.
  nm.notificationAccessGranted=true;
  listener.onListenerConnected();
  expect(manager.added==2,"re-grant registers a fresh media listener");
  expect(HandoffService.sessionStatus(TARGET).handoffDescription.contains("运行中"),"UI reports running again after re-grant");

  // Path 2: onListenerDisconnected revokes access while playing -> sends mediaInformation(false) too.
  MediaController playingBeforeDisconnect=new MediaController(PlaybackState.STATE_PLAYING);
  manager.active.clear();manager.active.add(playingBeforeDisconnect);
  manager.lastListener.onActiveSessionsChanged(Collections.singletonList(playingBeforeDisconnect));
  probe.packets.clear();
  nm.notificationAccessGranted=false;
  listener.onListenerDisconnected();
  expect(manager.removed==2,"onListenerDisconnected removes active listener registration");
  expect(HandoffService.sessionStatus(TARGET).handoffDescription.contains("等待媒体权限"),"UI reports waiting for media access after disconnect");
  byte[] mediaFalse=RoutingPackets.mediaInformation(SELF,PEER,false);
  boolean sentFalseOnDisconnect=false;
  for(byte[] p:probe.packets) if(Arrays.equals(p,mediaFalse)) sentFalseOnDisconnect=true;
  expect(sentFalseOnDisconnect,"onListenerDisconnected while playing sends mediaInformation(false)");
  manager.active.clear();

  nm.notificationAccessGranted=true;
  listener.onListenerConnected();
  expect(manager.added==3,"second re-grant registers media listener again");

  // Path 3: Even if watching stayed true during a race, onListenerConnected removes stale registration and re-registers.
  listener.onListenerConnected();
  expect(manager.removed==3&&manager.added==4,"onListenerConnected replaces existing registration cleanly");

  // Path 4: Revoke while playing sends media-state false; restoring while still playing resends media-state true without TAKE.
  MediaController activeBeforeRevoke=new MediaController(PlaybackState.STATE_PLAYING);
  manager.active.clear();manager.active.add(activeBeforeRevoke);
  manager.lastListener.onActiveSessionsChanged(Collections.singletonList(activeBeforeRevoke));
  probe.packets.clear();
  nm.notificationAccessGranted=false;
  manager.active.clear();
  manager.lastListener.onActiveSessionsChanged(Collections.emptyList());
  byte[] mediaTrue=RoutingPackets.mediaInformation(SELF,PEER,true);
  byte[] ownershipTrue=RoutingPackets.ownership(true);
  boolean sentFalseOnRevoke=false;
  for(byte[] p:probe.packets) if(Arrays.equals(p,mediaFalse)) sentFalseOnRevoke=true;
  expect(sentFalseOnRevoke,"revocation while playing sends mediaInformation(false)");
  probe.packets.clear();
  nm.notificationAccessGranted=true;
  manager.active.add(activeBeforeRevoke);
  listener.onListenerConnected();
  boolean sentTrueOnRestore=false,tookOnRestore=false;
  for(byte[] p:probe.packets){
   if(Arrays.equals(p,mediaTrue)) sentTrueOnRestore=true;
   if(Arrays.equals(p,ownershipTrue)) tookOnRestore=true;
  }
  expect(sentTrueOnRestore,"re-grant while playing resyncs mediaInformation(true)");
  expect(!tookOnRestore,"media state resync on restore must not trigger TAKE");
  expect(activeBeforeRevoke.paused==0,"media state resync must not pause media");

  // Fresh playback edge after recovery still triggers TAKE.
  probe.packets.clear();
  MediaController newPlayer=new MediaController(PlaybackState.STATE_PLAYING);
  manager.lastListener.onActiveSessionsChanged(Arrays.asList(activeBeforeRevoke,newPlayer));
  expect(newPlayer.callbacks.size()==1,"new player is tracked after permission recovery");
  boolean took=false;
  for(byte[] p:probe.packets) if(Arrays.equals(p,ownershipTrue)) took=true;
  expect(took,"new player playback after permission recovery triggers handoff TAKE");
 }
 static void restartPreservesPendingTakeUntilReady()throws Exception{
  AapProbe.failStart=false;AapProbe.created.clear();
  MediaSessionManager manager=new MediaSessionManager();
  NotificationManager nm=new NotificationManager();
  HandoffService s=service(manager);s.services.put(NotificationManager.class,nm);
  s.onCreate();
  Intent start=new Intent().putExtra("address",TARGET).putExtra("local_mac",SELF).putExtra("peer_address",PEER)
   .putExtra(HandoffService.EXTRA_SESSION_MODE,"AUTO_HANDOFF");
  s.onStartCommand(start,0,1);
  expect(AapProbe.created.size()==1,"initial start creates one probe");
  AapProbe probe=AapProbe.created.get(0);
  // Control channel is not ready yet; peer discovered and player starts playing -> pending TAKE queued.
  ((Set<String>)get(s,"peers")).add(PEER);
  MediaController playing=new MediaController(PlaybackState.STATE_PLAYING);
  manager.active.add(playing);
  manager.lastListener.onActiveSessionsChanged(Collections.singletonList(playing));
  expect(((RoutingPolicy)get(s,"policy")).isPending(),"playback while unready queues pending TAKE");

  // Rebuilding listener registration while watching=true must preserve pending TAKE for active playback.
  PlaybackListener listener=new PlaybackListener();
  listener.onListenerConnected();
  expect(manager.removed==1&&manager.added==2,"restartSessionWatch replaces registration");
  expect(((RoutingPolicy)get(s,"policy")).isPending(),"restartSessionWatch preserves pending TAKE when player still playing");

  // Once control channel becomes ready, the preserved pending TAKE completes handoff.
  probe.listener.ready();
  boolean took=false;
  byte[] ownershipTrue=RoutingPackets.ownership(true);
  for(byte[] p:probe.packets) if(Arrays.equals(p,ownershipTrue)) took=true;
  expect(took,"ready() after listener restart completes preserved pending TAKE");
 }
 static void downgradeReusesSessionWithoutAudioDisruption()throws Exception{
  AapProbe.failStart=false;AapProbe.created.clear();
  MediaSessionManager manager=new MediaSessionManager();
  MediaController activePlayer=new MediaController(PlaybackState.STATE_PLAYING);
  manager.active.add(activePlayer);
  HandoffService s=service(manager);
  s.onStartCommand(new Intent().putExtra("address",TARGET).putExtra("local_mac",SELF).putExtra("peer_address",PEER)
   .putExtra(HandoffService.EXTRA_SESSION_MODE,"AUTO_HANDOFF"),0,1);
  AapProbe probe=AapProbe.created.get(0);
  probe.listener.ready();
  AapProbe.failStart=true;
  ((Set<String>)get(s,"peers")).add(PEER);
  expect(activePlayer.callbacks.size()==1&&HandoffService.sessionStatus(TARGET).handoffDescription.contains("运行中"),"AUTO_HANDOFF starts with active watch");
  MediaController.Callback staleCallback=activePlayer.callbacks.iterator().next();

  Intent downgrade=new Intent().putExtra("address",TARGET).putExtra("local_mac",SELF).putExtra("peer_address",PEER)
   .putExtra(HandoffService.EXTRA_SESSION_MODE,"CONTROL_ONLY");
  s.onStartCommand(downgrade,0,2);
  expect(AapProbe.created.size()==1&&probe.stopped==0,"downgrade reuses existing AACP probe without stopping it");
  expect(manager.removed==1,"downgrade stops media session watch");
  expect(activePlayer.callbacks.isEmpty(),"downgrade unregisters player callbacks");
  expect(activePlayer.paused==0,"downgrade never pauses playing media");
  byte[] ownershipFalse=RoutingPackets.ownership(false);
  for(byte[] p:probe.packets) expect(!Arrays.equals(p,ownershipFalse),"downgrade must not send release ownership packet");
  expect(HandoffService.sessionStatus(TARGET).handoffDescription.contains("关闭"),"UI reports handoff closed after downgrade");

  // Re-upgrade with paused player and verify stale callback from prior AUTO_HANDOFF batch cannot trigger TAKE.
  MediaController pausedPlayer=new MediaController(0);
  manager.active.clear();manager.active.add(pausedPlayer);
  probe.packets.clear();
  Intent reupgrade=new Intent().putExtra("address",TARGET).putExtra("local_mac",SELF).putExtra("peer_address",PEER)
   .putExtra(HandoffService.EXTRA_SESSION_MODE,"AUTO_HANDOFF");
  s.onStartCommand(reupgrade,0,2);
  staleCallback.onPlaybackStateChanged(new PlaybackState(PlaybackState.STATE_PLAYING));
  byte[] ownershipTrue=RoutingPackets.ownership(true);
  for(byte[] p:probe.packets) expect(!Arrays.equals(p,ownershipTrue),"stale playback callback from pre-downgrade batch cannot trigger TAKE");
 }
 static void bridgeLifecycle(String modeName)throws Exception{
  AapProbe.failStart=false;AapProbe.created.clear();RootEnvironment.stoppedBridges.clear();
  MediaSessionManager manager=new MediaSessionManager();
  HandoffService s=service(manager);
  s.onCreate();
  RootEnvironment.Prepared prepared=new RootEnvironment.Prepared();
  prepared.bridgeOwned=true;prepared.targetAddress=TARGET;prepared.local=SELF;prepared.adapterId="sm-x810";
  RootEnvironment.nextBridgeUsable=true;RootEnvironment.nextAcceptBridge=true;
  expect(HandoffService.adoptPreparedBridge(prepared),"bridge adoption succeeds for valid prepared instance");

  Intent start=new Intent().putExtra("address",TARGET).putExtra("local_mac",SELF).putExtra("peer_address",PEER)
   .putExtra(HandoffService.EXTRA_SESSION_MODE,modeName).putExtra("native_backend_ready",true);
  s.onStartCommand(start,0,1);
  expect(AapProbe.created.size()==1,"bridge start creates one AapProbe");
  AapProbe first=AapProbe.created.get(0);
  first.listener.ready();
  SessionStatus st=HandoffService.sessionStatus(TARGET);
  expect(st.channelReady&&st.controlDescription.contains("桥接"),modeName+" bridge ready reflected in sessionStatus");
  boolean expectWatch="AUTO_HANDOFF".equals(modeName);
  expect(st.handoffDescription.contains("运行中")==expectWatch,modeName+" watch state matches session mode after bridge start");

  // Bridge failure after one reconnect attempt must clean up owned bridge.
  first.listener.ended();
  ((Handler)get(s,"handler")).flushDelayed();
  expect(AapProbe.created.size()==2,"bridge retries once before failing");
  AapProbe retry=AapProbe.created.get(1);
  retry.listener.ended();
  expect(RootEnvironment.stoppedBridges.contains(prepared),modeName+" failed bridge cleans up owned bridge");
  expect(!HandoffService.bridgePrepared(),modeName+" clears nativeBackendReady on failure");

  // Second bridge session stopped via onDestroy must also clean up owned bridge and watch.
  RootEnvironment.stoppedBridges.clear();
  RootEnvironment.Prepared second=new RootEnvironment.Prepared();
  second.bridgeOwned=true;second.targetAddress=TARGET;second.local=SELF;second.adapterId="sm-x810";
  HandoffService.adoptPreparedBridge(second);
  s.onStartCommand(start,0,2);
  AapProbe active=AapProbe.created.get(AapProbe.created.size()-1);
  active.listener.ready();
  s.onDestroy();
  expect(RootEnvironment.stoppedBridges.contains(second),modeName+" onDestroy cleans up owned bridge");
  expect(active.stopped>=1,modeName+" onDestroy stops active probe");
  expect(!HandoffService.isRunning()&&(!expectWatch||manager.removed==1),modeName+" onDestroy stops watching");
 }
 static void audioSnapshotObservation()throws Exception{
  AapProbe.failStart=false;AapProbe.created.clear();
  AudioManager audio=new AudioManager();
  audio.outputs=new AudioDeviceInfo[]{new AudioDeviceInfo(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,TARGET,"AirPods Pro")};
  BluetoothManager btManager=new BluetoothManager();
  HandoffService s=service(new MediaSessionManager());
  s.services.put(AudioManager.class,audio);
  s.services.put(BluetoothManager.class,btManager);
  s.bluetoothPermissionGranted=false;
  s.onCreate();
  expect(btManager.adapter.proxyRequests==0,"no profile proxies requested before BLUETOOTH_CONNECT grant");
  expect(audio.deviceCallbacks==1&&audio.playbackCallbacks==1&&audio.modeListeners==1&&audio.commListeners==1,
   "onCreate registers SamsungAudioMonitor listeners once");
  expect(s.registeredReceivers==1,"onCreate registers Bluetooth audio receiver once");
  s.bluetoothPermissionGranted=true;
  Intent start=new Intent().putExtra("address",TARGET).putExtra("local_mac",SELF).putExtra("peer_address",PEER)
   .putExtra(HandoffService.EXTRA_SESSION_MODE,"CONTROL_ONLY");
  s.onStartCommand(start,0,1);
  expect(btManager.adapter.proxyRequests==2,"setTargetAddress requests A2DP and HEADSET proxies once permitted");
  s.onStartCommand(start,0,2);
  expect(btManager.adapter.proxyRequests==2,"repeated start does not re-request profile proxies");
  BluetoothProfile.ServiceListener oldA2dpListener=btManager.adapter.lastA2dpListener;
  BluetoothProfile p1=d->2;
  oldA2dpListener.onServiceConnected(BluetoothProfile.A2DP,p1);
  expect(HandoffService.audioSnapshot(TARGET).a2dpState==SamsungAudioSnapshot.ConnectionState.CONNECTED,
   "connected P1 reports A2DP state");
  oldA2dpListener.onServiceDisconnected(BluetoothProfile.A2DP);
  expect(btManager.adapter.closedProxies.contains(p1),
   "onServiceDisconnected closes disconnected proxy P1 before retry");
  expect(btManager.adapter.proxyRequests==3,
   "onServiceDisconnected automatically re-requests replacement A2DP proxy P2 when started, enabled, and permitted");
  expect(HandoffService.audioSnapshot(TARGET).a2dpState==SamsungAudioSnapshot.ConnectionState.UNKNOWN,
   "disconnected proxy reports A2DP state as UNKNOWN until P2 connects");
  BluetoothProfile p2=d->2;
  BluetoothProfile.ServiceListener secondA2dpListener=btManager.adapter.lastA2dpListener;
  secondA2dpListener.onServiceConnected(BluetoothProfile.A2DP,p2);
  int closedBeforeStale=btManager.adapter.closedProxies.size();
  oldA2dpListener.onServiceConnected(BluetoothProfile.A2DP,p1);
  expect(btManager.adapter.closedProxies.size()==closedBeforeStale+1,
   "stale onServiceConnected for P1 is immediately closed and cannot replace P2");
  oldA2dpListener.onServiceDisconnected(BluetoothProfile.A2DP);
  expect(HandoffService.audioSnapshot(TARGET).a2dpState==SamsungAudioSnapshot.ConnectionState.CONNECTED,
   "stale onServiceDisconnected from P1 cannot clear active P2");
  btManager.adapter.enabled=false;
  secondA2dpListener.onServiceDisconnected(BluetoothProfile.A2DP);
  expect(btManager.adapter.proxyRequests==3,
   "onServiceDisconnected does not re-request proxy while adapter is disabled");
  btManager.adapter.enabled=true;
  s.lastReceiver.onReceive(s,new Intent(BluetoothAdapter.ACTION_STATE_CHANGED));
  expect(btManager.adapter.proxyRequests==4,
   "ACTION_STATE_CHANGED when adapter re-enables requests replacement A2DP proxy P3");
  BluetoothProfile p3=d->2;
  btManager.adapter.lastA2dpListener.onServiceConnected(BluetoothProfile.A2DP,p3);
  SamsungAudioSnapshot initial=HandoffService.audioSnapshot(TARGET);
  expect(initial.mediaOutputAvailable,"service audio snapshot sees available A2DP output device");
  expect(initial.routeEvidenceSupported,"SDK 36 supports media attributes route evidence");
  expect(initial.routeState==SamsungAudioSnapshot.RouteState.UNKNOWN,
   "service audio snapshot does not infer ROUTE_OBSERVED from getDevices alone");
  audio.attributesDevices=Collections.singletonList(new AudioDeviceInfo(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,TARGET,"AirPods Pro"));
  SamsungAudioSnapshot routed=HandoffService.audioSnapshot(TARGET);
  expect(routed.routeState==SamsungAudioSnapshot.RouteState.ROUTE_OBSERVED,
   "service audio snapshot sees ROUTE_OBSERVED when media attributes route matches target");
  s.onDestroy();
  expect(btManager.adapter.closedProxies.contains(p3),
   "onDestroy closes replacement proxy P3");
  expect(audio.deviceCallbacks==0&&audio.playbackCallbacks==0&&audio.modeListeners==0&&audio.commListeners==0,
   "onDestroy unregisters all SamsungAudioMonitor listeners");
  expect(s.unregisteredReceivers==1,"onDestroy unregisters Bluetooth receiver");
  expect(HandoffService.audioSnapshot(TARGET).routeState==SamsungAudioSnapshot.RouteState.UNKNOWN,
   "stopped service returns unavailable audio snapshot");
 }
 static void sessionLifecycleStability()throws Exception{
  AapProbe.failStart=false;AapProbe.created.clear();
  MediaSessionManager manager=new MediaSessionManager();
  AudioManager audio=new AudioManager();
  HandoffService s=service(manager);
  s.services.put(AudioManager.class,audio);
  s.onCreate();
  SamsungAudioMonitor monitor=(SamsungAudioMonitor)get(s,"audioMonitor");
  monitor.start();
  expect(monitor.isStarted()&&audio.deviceCallbacks==1&&s.registeredReceivers==1,
   "repeated SamsungAudioMonitor.start() is idempotent");
  Intent start=new Intent().putExtra("address",TARGET).putExtra("local_mac",SELF).putExtra("peer_address",PEER)
   .putExtra(HandoffService.EXTRA_SESSION_MODE,"AUTO_HANDOFF");
  s.onStartCommand(start,0,1);
  s.onStartCommand(start,0,2);
  expect(AapProbe.created.size()==1,"repeated onStartCommand while connecting creates one probe");
  expect(manager.added==1,"repeated onStartCommand registers media watch once");
  AapProbe first=AapProbe.created.get(0);
  first.listener.ready();
  s.onStartCommand(start,0,3);
  expect(AapProbe.created.size()==1,"repeated onStartCommand while ready reuses existing probe");
  expect(manager.added==1,"repeated onStartCommand while ready keeps single media watch");
  first.listener.ended();
  s.onStartCommand(start,0,4);
  expect(AapProbe.created.size()==1,"repeated onStartCommand while reconnecting avoids duplicate session");
  s.onDestroy();
  expect(!HandoffService.isRunning()&&!HandoffService.sessionStatus(TARGET).serviceRunning
   &&!HandoffService.earbudSnapshot(TARGET).ready&&get(s,"target")==null,
   "onDestroy marks service stopped and clears session/target references");
  ((Handler)get(s,"handler")).flushDelayed();
  expect(AapProbe.created.size()==1,"onDestroy cancels pending reconnect callbacks");
  PlaybackListener listener=new PlaybackListener();
  listener.onListenerConnected();
  expect(AapProbe.created.size()==1&&manager.added==1,
   "PlaybackListener after service stop does not restart service or media watch");
  monitor.stop();
  expect(!monitor.isStarted()&&audio.deviceCallbacks==0&&s.unregisteredReceivers==1,
   "repeated SamsungAudioMonitor.stop() is idempotent");
  monitor.start();
  expect(monitor.isStarted()&&audio.deviceCallbacks==1&&s.registeredReceivers==2,
   "SamsungAudioMonitor can cleanly restart after idempotent stop");
  monitor.stop();
  expect(!monitor.isStarted()&&audio.deviceCallbacks==0&&s.unregisteredReceivers==2,
   "second lifecycle stop cleans all listeners");
 }
 public static void main(String[] args)throws Exception{
  switch(args[0]){
   case "identity":upgradeWithLocalIdentity(false);break;
   case "known-peer":upgradeWithLocalIdentity(true);break;
   case "mode-round-trip":modeRoundTripInvalidatesQueuedRouting();break;
   case "permission-recovery":permissionRevokeAndRestoreResumesHandoff();break;
   case "restart-pending-take":restartPreservesPendingTakeUntilReady();break;
   case "mode-downgrade":downgradeReusesSessionWithoutAudioDisruption();break;
   case "bridge-control-only":bridgeLifecycle("CONTROL_ONLY");break;
   case "bridge-auto-handoff":bridgeLifecycle("AUTO_HANDOFF");break;
   case "audio-snapshot":audioSnapshotObservation();break;
   case "session-lifecycle":sessionLifecycleStability();break;
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

    def test_mode_round_trip_invalidates_queued_routing(self):
        self.run_scenario('mode-round-trip')

    def test_media_permission_revoked_and_restored_resumes_handoff(self):
        self.run_scenario('permission-recovery')

    def test_listener_restart_preserves_pending_take_until_ready(self):
        self.run_scenario('restart-pending-take')

    def test_mode_downgrade_reuses_session_without_disconnection(self):
        self.run_scenario('mode-downgrade')

    def test_bridge_lifecycle_in_control_only_mode(self):
        self.run_scenario('bridge-control-only')

    def test_bridge_lifecycle_in_auto_handoff_mode(self):
        self.run_scenario('bridge-auto-handoff')

    def test_service_audio_snapshot_and_monitor_cleanup(self):
        self.run_scenario('audio-snapshot')

    def test_session_lifecycle_no_duplicate_connections_and_clean_stop(self):
        self.run_scenario('session-lifecycle')

    def run_scenario(self, scenario):
        jdk = Path(os.environ['JAVA_HOME']) / 'bin' if os.environ.get('JAVA_HOME') else None
        javac = shutil.which('javac', path=str(jdk) if jdk else None)
        java = shutil.which('java', path=str(jdk) if jdk else None)
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
            names = ('HandoffService', 'PlaybackListener', 'PlaybackWatch', 'ControlSession', 'SessionMode', 'SessionStatus',
                     'SamsungAudioMonitor', 'SamsungAudioSnapshot', 'SamsungAudioPolicy',
                     'RoutingPolicy', 'AudioLinkPolicy', 'PeerRoutingPolicy', 'DeviceConfig',
                     'ConnectionPolicy', 'RoutingPackets', 'AapPackets', 'SmartRouting',
                     'EarbudCommands', 'EarbudSnapshot', 'AirPodsModels', 'AirPodsInformation',
                     'BackendPolicy')
            files += [str(package / (name + '.java')) for name in names]
            compiled = subprocess.run([javac, '-encoding', 'UTF-8', '-d', str(output), *files],
                                      capture_output=True, text=True, timeout=30)
            self.assertEqual(compiled.returncode, 0, compiled.stderr)
            result = subprocess.run([java, '-cp', str(output),
                                     'local.airpods.handoffprobe.HandoffLifecycleTest', scenario],
                                    capture_output=True, text=True, timeout=10)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
