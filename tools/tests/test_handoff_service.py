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
 public boolean bluetoothPermissionGranted=true,baseAttached=false;
 public final List<Intent> startedForegroundServices=new ArrayList<>();
 public final SharedPreferences prefs=new SharedPreferences();
 public <T>T getSystemService(Class<T> type){return type.cast(services.get(type));}
 public Context getApplicationContext(){return this;}
 public java.util.concurrent.Executor getMainExecutor(){return Runnable::run;}
 public int checkSelfPermission(String p){return bluetoothPermissionGranted?android.content.pm.PackageManager.PERMISSION_GRANTED:-1;}
 public void registerReceiver(BroadcastReceiver r,IntentFilter f){registeredReceivers++;lastReceiver=r;}
 public void registerReceiver(BroadcastReceiver r,IntentFilter f,int flags){registeredReceivers++;lastReceiver=r;}
 public void unregisterReceiver(BroadcastReceiver r){unregisteredReceivers++;}
 public FileOutputStream openFileOutput(String name,int mode)throws IOException{throw new IOException("host logging disabled");}
 public SharedPreferences getSharedPreferences(String name,int mode){return prefs;}
 public ComponentName startForegroundService(Intent i){startedForegroundServices.add(i);return null;}
 public ComponentName startService(Intent i){return null;}
 public boolean stopService(Intent i){return true;}
 public void startActivity(Intent i){}
}''',
    'android/content/SharedPreferences.java': '''package android.content;
import java.util.*;
public class SharedPreferences {
 public final Map<String,String> values=new HashMap<>();
 public final Map<String,Boolean> booleans=new HashMap<>();
 public String getString(String k,String d){return values.containsKey(k)?values.get(k):d;}
 public boolean getBoolean(String k,boolean d){return booleans.containsKey(k)?booleans.get(k):d;}
 public Editor edit(){return new Editor(this);}
 public static class Editor {
  private final SharedPreferences owner;
  Editor(SharedPreferences o){owner=o;}
  public Editor putString(String k,String v){owner.values.put(k,v);return this;}
  public Editor putBoolean(String k,boolean v){owner.booleans.put(k,v);return this;}
  public void apply(){}
 }}''',
    'android/content/Intent.java': '''package android.content;
import java.util.*;
public class Intent {
 public static final int FLAG_ACTIVITY_NEW_TASK=0x10000000,FLAG_ACTIVITY_CLEAR_TOP=0x04000000,FLAG_ACTIVITY_SINGLE_TOP=0x20000000;
 public Class<?> component;
 private String action;
 private final Map<String,Object> extras=new HashMap<>();
 public Intent(){}
 public Intent(String a){action=a;}
 public Intent(Context c,Class<?> cls){component=cls;}
 public String getAction(){return action;}
 public Intent setAction(String a){action=a;return this;}
 public Intent addFlags(int f){return this;}
 public Intent putExtra(String k,String v){extras.put(k,v);return this;}
 public Intent putExtra(String k,boolean v){extras.put(k,v);return this;}
 public Intent putExtra(String k,int v){extras.put(k,v);return this;}
 public Intent putExtra(String k,Object v){extras.put(k,v);return this;}
 public boolean hasExtra(String k){return extras.containsKey(k);}
 public void removeExtra(String k){extras.remove(k);}
 public String getStringExtra(String k){return (String)extras.get(k);}
 public boolean getBooleanExtra(String k,boolean d){return extras.containsKey(k)?(Boolean)extras.get(k):d;}
 public int getIntExtra(String k,int d){return extras.containsKey(k)?(Integer)extras.get(k):d;}
 public <T>T getParcelableExtra(String k){return (T)extras.get(k);}
 public <T>T getParcelableExtra(String k,Class<T> t){return t.cast(extras.get(k));}
}''',
    'android/content/ComponentName.java': 'package android.content; public class ComponentName {public ComponentName(Context c,Class<?> t){if(!c.baseAttached)throw new NullPointerException("base context not attached");}}',
    'android/content/IntentFilter.java': 'package android.content; public class IntentFilter {public IntentFilter(){}public IntentFilter(String a){}public void addAction(String a){}}',
    'android/content/BroadcastReceiver.java': 'package android.content; public abstract class BroadcastReceiver {public abstract void onReceive(Context c,Intent i); public final PendingResult goAsync(){return new PendingResult();} public static class PendingResult {public void finish(){}}}',
    'android/service/notification/NotificationListenerService.java': '''package android.service.notification;
public class NotificationListenerService {
 public void onListenerConnected(){}
 public void onListenerDisconnected(){}
}''',
    'android/app/Service.java': '''package android.app;
public class Service extends android.content.Context {
 public static final int START_NOT_STICKY=2;
 public boolean stopRequested;
 public void onCreate(){} public void onDestroy(){} public void stopSelf(){stopRequested=true;}
 public void startForeground(int id,Notification n){}
 public int onStartCommand(android.content.Intent i,int flags,int id){return 2;}
 public android.os.IBinder onBind(android.content.Intent i){return null;}
}''',
    'android/graphics/drawable/Icon.java': 'package android.graphics.drawable; public class Icon {}',
    'android/app/PendingIntent.java': '''package android.app;
public class PendingIntent {
 public static final int FLAG_UPDATE_CURRENT=134217728,FLAG_IMMUTABLE=67108864;
 public final android.content.Intent intent;
 public final boolean activity;
 private PendingIntent(android.content.Intent i,boolean a){intent=i;activity=a;}
 public static PendingIntent getActivity(android.content.Context c,int r,android.content.Intent i,int f){return new PendingIntent(i,true);}
 public static PendingIntent getBroadcast(android.content.Context c,int r,android.content.Intent i,int f){return new PendingIntent(i,false);}
}''',
    'local/airpods/handoffprobe/MainActivity.java': 'package local.airpods.handoffprobe; public class MainActivity {public static final class NotificationEntry extends MainActivity {}}',
    'android/app/Notification.java': '''package android.app;
import java.util.*;
public class Notification {
 public String title="",text="";
 public PendingIntent contentIntent;
 public final List<String> actionTitles=new ArrayList<>();
 public final List<PendingIntent> actionIntents=new ArrayList<>();
 public static class Style {}
 public static class BigTextStyle extends Style {public BigTextStyle bigText(CharSequence cs){return this;}}
 public static class Action {
  public final String title;
  public final PendingIntent intent;
  Action(String t,PendingIntent p){title=t;intent=p;}
  public static class Builder {
   private final String title;
   private final PendingIntent intent;
   public Builder(android.graphics.drawable.Icon i,CharSequence t,PendingIntent p){title=t==null?"":t.toString();intent=p;}
   public Action build(){return new Action(title,intent);}
  }
 }
 public static class Builder {
  private final Notification n=new Notification();
  public Builder(android.content.Context c,String id){}
  public Builder setSmallIcon(int i){return this;}
  public Builder setContentTitle(String s){n.title=s==null?"":s;return this;}
  public Builder setContentText(String s){n.text=s==null?"":s;return this;}
  public Builder setOngoing(boolean b){return this;}
  public Builder setAutoCancel(boolean b){return this;}
  public Builder setContentIntent(PendingIntent p){n.contentIntent=p;return this;}
  public Builder setStyle(Style s){return this;}
  public Builder addAction(Action a){if(a!=null){n.actionTitles.add(a.title);n.actionIntents.add(a.intent);}return this;}
  public Notification build(){return n;}}}''',
    'android/app/NotificationChannel.java': 'package android.app; public class NotificationChannel {public NotificationChannel(String a,String b,int c){}}',
    'android/app/NotificationManager.java': '''package android.app;
public class NotificationManager {
 public static final int IMPORTANCE_LOW=2;
 public boolean notificationAccessGranted=true;
 public int lastNotifyId=-1;
 public Notification lastNotification;
 public void createNotificationChannel(NotificationChannel c){}
 public void notify(int i,Notification n){lastNotifyId=i;lastNotification=n;}
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
public class BluetoothDevice {
 public static final String EXTRA_DEVICE="device";
 public static final String EXTRA_TRANSPORT="transport";
 public static final int TRANSPORT_BREDR=1,TRANSPORT_LE=2;
 public static final String ACTION_ACL_CONNECTED="android.bluetooth.device.action.ACL_CONNECTED";
 public static final String ACTION_ACL_DISCONNECTED="android.bluetooth.device.action.ACL_DISCONNECTED";
 public final String address;public BluetoothDevice(String a){address=a;}public String getAddress(){return address;}}''',
    'android/bluetooth/BluetoothA2dp.java': 'package android.bluetooth; public class BluetoothA2dp {public static final String ACTION_CONNECTION_STATE_CHANGED="android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED";}',
    'android/bluetooth/BluetoothHeadset.java': 'package android.bluetooth; public class BluetoothHeadset {public static final String ACTION_CONNECTION_STATE_CHANGED="android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED",ACTION_AUDIO_STATE_CHANGED="android.bluetooth.headset.profile.action.AUDIO_STATE_CHANGED";}',
    'android/bluetooth/BluetoothManager.java': '''package android.bluetooth;
import java.util.*;
public class BluetoothManager {
 public final BluetoothAdapter adapter=new BluetoothAdapter();
 public BluetoothAdapter getAdapter(){return adapter;}
 public List<BluetoothDevice> getConnectedDevices(int profile){return Collections.emptyList();}
}''',
    'android/bluetooth/BluetoothProfile.java': '''package android.bluetooth;
public interface BluetoothProfile {
 int A2DP=2,HEADSET=1,GATT=7,STATE_DISCONNECTED=0,STATE_CONNECTED=2; String EXTRA_STATE="state";
 int getConnectionState(BluetoothDevice d);
 interface ServiceListener {void onServiceConnected(int k,BluetoothProfile p);void onServiceDisconnected(int k);}
}''',
    'android/media/session/MediaSession.java': 'package android.media.session; public class MediaSession {public static class Token {}}',
    'android/media/session/PlaybackState.java': '''package android.media.session;
public class PlaybackState {public static final int STATE_PAUSED=2,STATE_PLAYING=3,STATE_BUFFERING=6,STATE_CONNECTING=8;
 private final int state;private final long updateTime;
 public PlaybackState(int s){this(s,0L);}
 public PlaybackState(int s,long t){state=s;updateTime=t;}
 public int getState(){return state;}
 public long getLastPositionUpdateTime(){return updateTime;}}''',
    'android/media/session/MediaController.java': '''package android.media.session;
import java.util.*;
public class MediaController {
 public final Set<Callback> callbacks=new HashSet<>(); public int registered,unregistered,paused;
 private int playbackState=3;
 private String pkg="host.player";
 private final MediaSession.Token token=new MediaSession.Token();
 public MediaController(){}
 public MediaController(int state){playbackState=state;}
 public MediaController(String pkg,int state){this.pkg=pkg;this.playbackState=state;}
 public String getPackageName(){return pkg;}
 public MediaSession.Token getSessionToken(){return token;}
 public PlaybackState getPlaybackState(){return new PlaybackState(playbackState);}
 public void setPlaybackState(int state){playbackState=state;}
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
  HandoffService s=new HandoffService();s.baseAttached=true;HandoffService.current=s;s.services.put(MediaSessionManager.class,manager);
  AudioManager audio=new AudioManager();
  s.services.put(AudioManager.class,audio);
  s.services.put(BluetoothManager.class,new BluetoothManager());
  s.services.put(NotificationManager.class,new NotificationManager());
  set(s,"adapter",new BluetoothAdapter());
  set(s,"audio",audio);
  return s;
 }
 static void enableTargetOutputRoute(HandoffService s)throws Exception{
  AudioManager audio=(AudioManager)get(s,"audio");
  audio.attributesDevices=Collections.singletonList(new AudioDeviceInfo(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,TARGET,"AirPods"));
  SamsungAudioMonitor monitor=(SamsungAudioMonitor)get(s,"audioMonitor");
  if(monitor==null){
   SamsungAudioMonitor.Listener l=(SamsungAudioMonitor.Listener)get(s,"audioMonitorListener");
   monitor=new SamsungAudioMonitor(s,(Handler)get(s,"handler"),l);
   set(s,"audioMonitor",monitor);
   monitor.start();
   monitor.setTargetAddress(TARGET);
  }
 }
 static void upgradeWithLocalIdentity(boolean initiallyKnown)throws Exception{
  AapProbe.failStart=false;AapProbe.created.clear();
  HandoffService s=service(new MediaSessionManager());
  enableTargetOutputRoute(s);
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
  enableTargetOutputRoute(s);
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
  expect(!nm.lastNotification.title.contains("需要媒体监听权限"),"grant updates the notification immediately");

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
  expect(nm.lastNotification.title.contains("需要媒体监听权限"),"revocation updates the ongoing notification immediately");
  expect(nm.lastNotification.contentIntent.activity
          && nm.lastNotification.contentIntent.intent.component==MainActivity.class
          && !nm.lastNotification.contentIntent.intent.hasExtra(HandoffService.EXTRA_PENDING_ACTION),
   "notification body opens the launcher without executing any action");
  PendingIntent permission=nm.lastNotification.actionIntents.get(nm.lastNotification.actionTitles.indexOf("授予媒体监听权限"));
  expect(permission.activity && permission.intent.component==MainActivity.NotificationEntry.class,
   "notification repair uses a direct PendingIntent to the private activity");
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
  manager.active.add(newPlayer);
  manager.lastListener.onActiveSessionsChanged(Arrays.asList(activeBeforeRevoke,newPlayer));
  expect(activeBeforeRevoke.callbacks.size()==1&&newPlayer.callbacks.size()==1,
   "both existing and new players are tracked after permission recovery");
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
  enableTargetOutputRoute(s);
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

  // Recoverable rebind failure on a fresh, initially unready control session:
  // 1) Build pending TAKE via real player callback (never calling RoutingPolicy.onPlayback directly).
  // 2) Fail rebind registration and verify no TAKE is sent.
  // 3) Fire first-ever probe.listener.ready() while unwatched during failed rebind:
  //    - must NOT execute pending TAKE;
  //    - must NOT advertise stale playback as mediaInformation(true);
  //    - call audio mode transitions still advertise true and false independently of stale playback.
  // 4) Recover rebind with the same player still playing -> sends mediaInformation(true) and exactly one TAKE.
  s.onDestroy();
  AapProbe.created.clear();
  MediaSessionManager rebindManager=new MediaSessionManager();
  NotificationManager rebindNm=new NotificationManager();
  HandoffService s2=service(rebindManager);s2.services.put(NotificationManager.class,rebindNm);
  s2.onCreate();
  enableTargetOutputRoute(s2);
  s2.onStartCommand(start,0,2);
  expect(AapProbe.created.size()==1,"fresh session creates one probe");
  AapProbe probe2=AapProbe.created.get(0);
  ((Set<String>)get(s2,"peers")).add(PEER);
  MediaController realPlayer=new MediaController(PlaybackState.STATE_PLAYING);
  rebindManager.active.add(realPlayer);
  rebindManager.lastListener.onActiveSessionsChanged(Collections.singletonList(realPlayer));
  expect(((RoutingPolicy)get(s2,"policy")).isPending(),"real player callback while unready queues pending TAKE");

  probe2.packets.clear();
  rebindManager.rejectRegistration=true;
  listener.onListenerConnected();
  for(byte[] p:probe2.packets) expect(!Arrays.equals(p,ownershipTrue),"failed rebind must not execute preserved pending TAKE");

  // First-ever ready() during failed rebind must wait until rebind completes and must not announce stale playback as YES.
  byte[] mediaTrue=RoutingPackets.mediaInformation(SELF,PEER,true);
  byte[] mediaFalse=RoutingPackets.mediaInformation(SELF,PEER,false);
  probe2.listener.ready();
  for(byte[] p:probe2.packets){
   expect(!Arrays.equals(p,ownershipTrue),"first ready() while unwatched after failed rebind must not execute stale TAKE");
   expect(!Arrays.equals(p,mediaTrue),"unwatched failed rebind must not advertise stale playback as mediaInformation(true)");
  }

  // Ending a call while unwatched must NOT send mediaInformation(false) when playback is unobservable.
  AudioManager s2Audio=(AudioManager)get(s2,"audio");
  SamsungAudioMonitor.Listener s2Monitor=(SamsungAudioMonitor.Listener)get(s2,"audioMonitorListener");
  probe2.packets.clear();
  s2Audio.mode=AudioManager.MODE_IN_CALL;
  s2Monitor.onAudioModeChanged(AudioManager.MODE_IN_CALL);
  boolean sentCallTrueWhileUnwatched=false;
  for(byte[] p:probe2.packets) if(Arrays.equals(p,mediaTrue)) sentCallTrueWhileUnwatched=true;
  expect(sentCallTrueWhileUnwatched,"live call audio mode still advertises mediaInformation(true) while unwatched");
  probe2.packets.clear();
  s2Audio.mode=AudioManager.MODE_NORMAL;
  s2Monitor.onAudioModeChanged(AudioManager.MODE_NORMAL);
  for(byte[] p:probe2.packets) expect(!Arrays.equals(p,mediaFalse),"ending call while unwatched must not send mediaInformation(false)");

  // Recover rebind with the same player still playing -> keeps YES without NO->YES flip and sends TAKE exactly once.
  probe2.packets.clear();
  rebindManager.rejectRegistration=false;
  listener.onListenerConnected();
  int takeCount=0;
  for(byte[] p:probe2.packets){
   if(Arrays.equals(p,ownershipTrue)) takeCount++;
   expect(!Arrays.equals(p,mediaFalse),"rebind recovery with player still playing must not send mediaInformation(false)");
  }
  expect(Boolean.TRUE.equals(get(s2,"lastMediaState")),"rebind recovery with player still playing keeps media state active");
  expect(takeCount==1,"successful rebind recovery executes preserved TAKE exactly once when player is still playing");

  // If player stopped during unwatched call end, NO is deferred until rebind recovery confirms no playback.
  probe2.packets.clear();
  rebindManager.rejectRegistration=true;
  listener.onListenerConnected();
  s2Audio.mode=AudioManager.MODE_IN_CALL;
  s2Monitor.onAudioModeChanged(AudioManager.MODE_IN_CALL);
  realPlayer.setPlaybackState(PlaybackState.STATE_PAUSED);
  probe2.packets.clear();
  s2Audio.mode=AudioManager.MODE_NORMAL;
  s2Monitor.onAudioModeChanged(AudioManager.MODE_NORMAL);
  for(byte[] p:probe2.packets) expect(!Arrays.equals(p,mediaFalse),"call end while unwatched does not emit NO before watch confirms");
  rebindManager.rejectRegistration=false;
  listener.onListenerConnected();
  boolean sentFalseAfterConfirmedStop=false;
  for(byte[] p:probe2.packets) if(Arrays.equals(p,mediaFalse)) sentFalseAfterConfirmedStop=true;
  expect(sentFalseAfterConfirmedStop,"rebind recovery sends mediaInformation(false) once watch confirms player stopped");
  realPlayer.setPlaybackState(PlaybackState.STATE_PLAYING);
  rebindManager.active.clear();
  rebindManager.active.add(realPlayer);
  rebindManager.lastListener.onActiveSessionsChanged(Collections.singletonList(realPlayer));

  // If player paused before rebind recovery, preserved pending TAKE must NOT execute on recovery and must be cleared.
  realPlayer.setPlaybackState(PlaybackState.STATE_PAUSED);
  realPlayer.callbacks.iterator().next().onPlaybackStateChanged(new PlaybackState(PlaybackState.STATE_PAUSED));
  ((Set<String>)get(s2,"peers")).clear();
  realPlayer.setPlaybackState(PlaybackState.STATE_PLAYING);
  realPlayer.callbacks.iterator().next().onPlaybackStateChanged(new PlaybackState(PlaybackState.STATE_PLAYING));
  expect(((RoutingPolicy)get(s2,"policy")).isPending(),"fresh pending TAKE queued before second rebind failure");
  probe2.packets.clear();
  rebindManager.rejectRegistration=true;
  listener.onListenerConnected();
  ((Set<String>)get(s2,"peers")).add(PEER);
  realPlayer.setPlaybackState(PlaybackState.STATE_PAUSED);
  rebindManager.rejectRegistration=false;
  listener.onListenerConnected();
  expect(!((RoutingPolicy)get(s2,"policy")).isPending(),"rebind recovery when player already paused clears pending TAKE");
  for(byte[] p:probe2.packets) expect(!Arrays.equals(p,ownershipTrue),"rebind recovery when player already paused clears TAKE without executing");

  // Revoked notification access before onAccessLost runs must block finishPendingTake() on first ready().
  s2.onDestroy();
  AapProbe.created.clear();
  MediaSessionManager revokeManager=new MediaSessionManager();
  NotificationManager revokeNm=new NotificationManager();
  HandoffService s3=service(revokeManager);s3.services.put(NotificationManager.class,revokeNm);
  s3.onCreate();
  s3.onStartCommand(start,0,3);
  AapProbe probe3=AapProbe.created.get(0);
  ((Set<String>)get(s3,"peers")).add(PEER);
  MediaController revokePlayer=new MediaController(PlaybackState.STATE_PLAYING);
  revokeManager.active.add(revokePlayer);
  revokeManager.lastListener.onActiveSessionsChanged(Collections.singletonList(revokePlayer));
  expect(((RoutingPolicy)get(s3,"policy")).isPending(),"pending TAKE queued before access revocation");
  probe3.packets.clear();
  revokeNm.notificationAccessGranted=false;
  probe3.listener.ready();
  for(byte[] p:probe3.packets){
   expect(!Arrays.equals(p,ownershipTrue),"revoked media access blocks finishPendingTake before access-lost callback");
   expect(!Arrays.equals(p,mediaTrue),"revoked media access blocks advertising stale playback on ready()");
  }
  s3.onDestroy();
 }
 static void downgradeReusesSessionWithoutAudioDisruption()throws Exception{
  AapProbe.failStart=false;AapProbe.created.clear();
  MediaSessionManager manager=new MediaSessionManager();
  MediaController activePlayer=new MediaController(PlaybackState.STATE_PLAYING);
  manager.active.add(activePlayer);
  HandoffService s=service(manager);
  enableTargetOutputRoute(s);
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

  // Re-upgrade with the same controller paused and verify stale callback from prior AUTO_HANDOFF batch cannot trigger TAKE,
  // while the fresh callback for the same controller does trigger TAKE.
  activePlayer.setPlaybackState(0);
  probe.packets.clear();
  Intent reupgrade=new Intent().putExtra("address",TARGET).putExtra("local_mac",SELF).putExtra("peer_address",PEER)
   .putExtra(HandoffService.EXTRA_SESSION_MODE,"AUTO_HANDOFF");
  s.onStartCommand(reupgrade,0,3);
  expect(activePlayer.callbacks.size()==1,"re-upgrade registers one fresh callback on same controller");
  MediaController.Callback freshCallback=activePlayer.callbacks.iterator().next();
  expect(freshCallback!=staleCallback,"re-upgrade creates a distinct callback instance");
  activePlayer.setPlaybackState(PlaybackState.STATE_PLAYING);
  staleCallback.onPlaybackStateChanged(new PlaybackState(PlaybackState.STATE_PLAYING));
  byte[] ownershipTrue=RoutingPackets.ownership(true);
  for(byte[] p:probe.packets) expect(!Arrays.equals(p,ownershipTrue),"stale playback callback from pre-downgrade batch cannot trigger TAKE");
  freshCallback.onPlaybackStateChanged(new PlaybackState(PlaybackState.STATE_PLAYING));
  boolean freshTook=false;
  for(byte[] p:probe.packets) if(Arrays.equals(p,ownershipTrue)) freshTook=true;
  expect(freshTook,"fresh callback after re-upgrade triggers TAKE for same controller");

  // Telecom-only session update must NOT send mediaInformation(false) while still in MODE_IN_CALL,
  // and MUST send mediaInformation(false) once the call ends with no media playback.
  probe.packets.clear();
  manager.active.clear();
  manager.lastListener.onActiveSessionsChanged(Collections.emptyList());
  MediaController telecom=new MediaController("com.android.server.telecom",PlaybackState.STATE_PLAYING);
  AudioManager serviceAudio=(AudioManager)get(s,"audio");
  serviceAudio.mode=AudioManager.MODE_IN_CALL;
  SamsungAudioMonitor.Listener monitorListener=(SamsungAudioMonitor.Listener)get(s,"audioMonitorListener");
  monitorListener.onAudioModeChanged(AudioManager.MODE_IN_CALL);
  probe.packets.clear();
  manager.active.clear();
  manager.active.add(telecom);
  manager.lastListener.onActiveSessionsChanged(Collections.singletonList(telecom));
  byte[] mediaFalse=RoutingPackets.mediaInformation(SELF,PEER,false);
  for(byte[] p:probe.packets) expect(!Arrays.equals(p,mediaFalse),"telecom-only list during MODE_IN_CALL must not send mediaInformation(false)");
  // Stale queued MODE_NORMAL callback when a new call is already in MODE_IN_CALL must NOT send mediaInformation(false).
  probe.packets.clear();
  serviceAudio.mode=AudioManager.MODE_IN_CALL;
  monitorListener.onAudioModeChanged(AudioManager.MODE_NORMAL);
  for(byte[] p:probe.packets) expect(!Arrays.equals(p,mediaFalse),"stale MODE_NORMAL callback during active MODE_IN_CALL must not send mediaInformation(false)");
  serviceAudio.mode=AudioManager.MODE_NORMAL;
  monitorListener.onAudioModeChanged(AudioManager.MODE_NORMAL);
  boolean sentNoAfterCall=false;
  for(byte[] p:probe.packets) if(Arrays.equals(p,mediaFalse)) sentNoAfterCall=true;
  expect(sentNoAfterCall,"ending call with no media playback sends mediaInformation(false)");
  // Also verify telecom-only list after call ends syncs mediaInformation(false) when lastMediaState was still true.
  set(s,"lastMediaState",Boolean.TRUE);
  probe.packets.clear();
  manager.active.clear();
  manager.active.add(telecom);
  manager.lastListener.onActiveSessionsChanged(Collections.singletonList(telecom));
  boolean sentNoOnTelecomAfterCall=false;
  for(byte[] p:probe.packets) if(Arrays.equals(p,mediaFalse)) sentNoOnTelecomAfterCall=true;
  expect(sentNoOnTelecomAfterCall,"telecom-only session list after call ends syncs mediaInformation(false)");
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
   case "target-output-gate":speakerRouteSkipsHandoffAndManualTakeWorks();break;
   case "auto-start-and-notification":autoStartAndNotificationActions();break;
   case "remote-audio-source-release":remoteAudioSourceReleasesStaleA2dp();break;
   default:throw new AssertionError("unknown scenario");
  }
  System.out.println("PASS: service lifecycle "+args[0]);
 }
 static void speakerRouteSkipsHandoffAndManualTakeWorks()throws Exception{
  AapProbe.failStart=false;AapProbe.created.clear();
  MediaSessionManager manager=new MediaSessionManager();
  HandoffService s=service(manager);
  s.onCreate();
  AudioManager audio=(AudioManager)get(s,"audio");
  audio.outputs=new AudioDeviceInfo[]{
   new AudioDeviceInfo(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,"","Speaker"),
   new AudioDeviceInfo(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,TARGET,"AirPods")
  };
  audio.attributesDevices=Collections.singletonList(new AudioDeviceInfo(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,"","Speaker"));
  s.onStartCommand(new Intent().putExtra("address",TARGET).putExtra("local_mac",SELF).putExtra("peer_address",PEER)
   .putExtra(HandoffService.EXTRA_SESSION_MODE,"AUTO_HANDOFF"),0,1);
  AapProbe probe=AapProbe.created.get(0);
  probe.listener.ready();
  ((Set<String>)get(s,"peers")).add(PEER);
  probe.packets.clear();
  MediaController player=new MediaController(PlaybackState.STATE_PLAYING);
  manager.active.add(player);
  manager.lastListener.onActiveSessionsChanged(Collections.singletonList(player));
  byte[] ownershipTrue=RoutingPackets.ownership(true);
  for(byte[] p:probe.packets){
   expect(!Arrays.equals(p,ownershipTrue),"speaker route must skip automatic handoff TAKE");
  }
  expect(HandoffService.sessionStatus(TARGET).handoffDescription.contains("保留当前播放"),
   "skipped handoff explains current playback is kept and manual switch is available");
  expect(HandoffService.manualHandoff(TARGET),"manualHandoff succeeds when AUTO_HANDOFF session is ready");
  boolean manualTook=false;
  for(byte[] p:probe.packets){
   if(Arrays.equals(p,ownershipTrue)) manualTook=true;
  }
  expect(manualTook,"manualHandoff sends ownership(true)");
  audio.attributesDevices=Collections.singletonList(new AudioDeviceInfo(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,TARGET,"AirPods"));
  player.setPlaybackState(PlaybackState.STATE_PAUSED);
  player.callbacks.iterator().next().onPlaybackStateChanged(new PlaybackState(PlaybackState.STATE_PAUSED));
  player.setPlaybackState(PlaybackState.STATE_PLAYING);
  player.callbacks.iterator().next().onPlaybackStateChanged(new PlaybackState(PlaybackState.STATE_PLAYING));
  probe.listener.packet(RoutingPackets.ownership(true));
  probe.listener.packet(RoutingPackets.ownership(false));
  player.setPlaybackState(PlaybackState.STATE_PAUSED);
  player.callbacks.iterator().next().onPlaybackStateChanged(new PlaybackState(PlaybackState.STATE_PAUSED));
  ((Handler)get(s,"handler")).flushDelayed();
  expect(!HandoffService.sessionStatus(TARGET).handoffDescription.contains("未成功"),
   "normal ownership release invalidates pending handoff check");
  s.onDestroy();
 }
 static void autoStartAndNotificationActions()throws Exception{
  AapProbe.failStart=false;AapProbe.created.clear();
  Context ctx=new Context();
  NotificationManager nm=new NotificationManager();
  ctx.services.put(NotificationManager.class,nm);
  ctx.prefs.values.put("target",TARGET);
  ctx.prefs.values.put("local",SELF);
  ctx.prefs.values.put("peer",PEER);
  BluetoothAutoStartReceiver receiver=new BluetoothAutoStartReceiver();
  ctx.bluetoothPermissionGranted=false;
  Intent connectIntent=new Intent(BluetoothDevice.ACTION_ACL_CONNECTED).putExtra(BluetoothDevice.EXTRA_DEVICE,(Object)new BluetoothDevice(TARGET));
  receiver.onReceive(ctx,connectIntent);
  expect(ctx.startedForegroundServices.isEmpty(),"missing permission never auto-starts service");

  ctx.bluetoothPermissionGranted=true;
  ctx.prefs.booleans.put(BluetoothAutoStartReceiver.PREF_AUTO_START_OPT_IN,false);
  receiver.onReceive(ctx,connectIntent);
  expect(ctx.startedForegroundServices.isEmpty(),"remembered target without opt-in never auto-starts AUTO_HANDOFF");

  ctx.prefs.booleans.put(BluetoothAutoStartReceiver.PREF_AUTO_START_OPT_IN,true);
  receiver.onReceive(ctx,connectIntent);
  expect(ctx.startedForegroundServices.size()==1,"opted-in target ACL connect auto-starts service");
  Intent launched=ctx.startedForegroundServices.get(0);
  expect("AUTO_HANDOFF".equals(launched.getStringExtra(HandoffService.EXTRA_SESSION_MODE)),"auto-start requests AUTO_HANDOFF");
  expect(launched.getBooleanExtra(HandoffService.EXTRA_AUTO_STARTED,false),"auto-start marks EXTRA_AUTO_STARTED");

  MediaSessionManager manager=new MediaSessionManager();
  HandoffService s=service(manager);
  s.onCreate();
  s.onStartCommand(launched,0,1);
  AapProbe probe=AapProbe.created.get(0);
  probe.listener.ready();
  ((Set<String>)get(s,"peers")).add(PEER);
  probe.packets.clear();
  NotificationActionReceiver actionReceiver=new NotificationActionReceiver();
  actionReceiver.onReceive(ctx,new Intent(HandoffService.ACTION_MANUAL_HANDOFF).putExtra("address",TARGET));
  boolean manualTook=false;
  for(byte[] p:probe.packets){
   if(Arrays.equals(p,RoutingPackets.ownership(true))) manualTook=true;
  }
  expect(manualTook,"NotificationActionReceiver ACTION_MANUAL_HANDOFF executes manual take");
  s.onDestroy();

  BluetoothAutoStartReceiver.onUserStoppedOrDowngraded();
  ctx.services.put(BluetoothManager.class,new BluetoothManager());
  Field receiverHandler=BluetoothAutoStartReceiver.class.getDeclaredField("HANDLER");
  receiverHandler.setAccessible(true);
  Handler debounce=(Handler)receiverHandler.get(null);
  Intent headset=new Intent(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
          .putExtra(BluetoothDevice.EXTRA_DEVICE,(Object)new BluetoothDevice(TARGET));
  receiver.onReceive(ctx,headset.putExtra(BluetoothProfile.EXTRA_STATE,BluetoothProfile.STATE_DISCONNECTED));
  debounce.flushDelayed();
  receiver.onReceive(ctx,headset.putExtra(BluetoothProfile.EXTRA_STATE,BluetoothProfile.STATE_CONNECTED));
  expect(ctx.startedForegroundServices.size()==1,"profile-only disconnect preserves suppression with the service stopped");
  Intent leConnect=new Intent(BluetoothDevice.ACTION_ACL_CONNECTED)
          .putExtra(BluetoothDevice.EXTRA_DEVICE,(Object)new BluetoothDevice(TARGET))
          .putExtra(BluetoothDevice.EXTRA_TRANSPORT,BluetoothDevice.TRANSPORT_LE);
  receiver.onReceive(ctx,leConnect);
  Intent aclDisconnect=new Intent(BluetoothDevice.ACTION_ACL_DISCONNECTED)
          .putExtra(BluetoothDevice.EXTRA_DEVICE,(Object)new BluetoothDevice(TARGET));
  receiver.onReceive(ctx,aclDisconnect.putExtra(BluetoothDevice.EXTRA_TRANSPORT,BluetoothDevice.TRANSPORT_BREDR));
  debounce.flushDelayed();
  expect(ctx.startedForegroundServices.size()==1,"classic disconnect preserves suppression while LE remains");
  receiver.onReceive(ctx,aclDisconnect.putExtra(BluetoothDevice.EXTRA_TRANSPORT,BluetoothDevice.TRANSPORT_LE));
  debounce.flushDelayed();
  receiver.onReceive(ctx,connectIntent);
  expect(ctx.startedForegroundServices.size()==2,"full Bluetooth disconnect clears suppression for the next connection");

  HandoffService failed=service(manager);
  failed.onCreate();
  failed.onStartCommand(ctx.startedForegroundServices.get(1),0,1);
  AapProbe incompatible=AapProbe.created.get(AapProbe.created.size()-1);
  incompatible.listener.failure(AapProbe.Failure.COMPATIBILITY_REQUIRED);
  incompatible.listener.ended();
  ConnectionPolicy.Snapshot state=HandoffService.capabilities(TARGET);
  expect(state.phase==ConnectionPolicy.Phase.FAILED && state.compatibilityRequired,
   "automatic compatibility failure offers explicit bridge preparation without preparing one");
  receiver.onReceive(ctx,aclDisconnect.putExtra(BluetoothDevice.EXTRA_TRANSPORT,BluetoothDevice.TRANSPORT_BREDR));
  debounce.flushDelayed();
  expect(failed.stopRequested,"full disconnect stops a target-matched auto-started service");
  failed.onDestroy();
 }
 static void remoteAudioSourceReleasesStaleA2dp()throws Exception{
  AapProbe.failStart=false;AapProbe.created.clear();
  MediaSessionManager manager=new MediaSessionManager();
  HandoffService s=service(manager);
  s.onCreate();
  final int[] disconnects={0};
  BluetoothProfile a2dp=new BluetoothProfile(){
   @Override public int getConnectionState(BluetoothDevice d){return BluetoothProfile.STATE_CONNECTED;}
   public boolean connect(BluetoothDevice d){return true;}
   public boolean disconnect(BluetoothDevice d){disconnects[0]++;return true;}
  };
  BluetoothManager btManager=(BluetoothManager)s.getSystemService(BluetoothManager.class);
  btManager.adapter.lastA2dpListener.onServiceConnected(BluetoothProfile.A2DP,a2dp);
  s.onStartCommand(new Intent().putExtra("address",TARGET).putExtra("local_mac",SELF).putExtra("peer_address",PEER)
   .putExtra(HandoffService.EXTRA_SESSION_MODE,"AUTO_HANDOFF"),0,1);
  AapProbe probe=AapProbe.created.get(0);
  probe.listener.ready();

  // 0x0e AUDIO_SOURCE for PEER (AA:BB:CC:DD:EE:03 -> reversed 03,EE,DD,CC,BB,AA), type=2
  byte[] remoteSource=new byte[]{4,0,4,0,0x0e,0,0x03,(byte)0xEE,(byte)0xDD,(byte)0xCC,(byte)0xBB,(byte)0xAA,2};
  MediaController player=new MediaController(PlaybackState.STATE_PLAYING);
  manager.active.add(player);
  manager.lastListener.onActiveSessionsChanged(Collections.singletonList(player));
  probe.listener.packet(remoteSource);
  expect(disconnects[0]==0,"active local playback must not release A2DP on remote AUDIO_SOURCE");

  player.setPlaybackState(PlaybackState.STATE_PAUSED);
  player.callbacks.iterator().next().onPlaybackStateChanged(new PlaybackState(PlaybackState.STATE_PAUSED));
  probe.listener.packet(remoteSource);
  expect(disconnects[0]==1,"idle host with remote AUDIO_SOURCE releases stale A2DP link");
  s.onDestroy();
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

    def test_speaker_route_skips_handoff_and_manual_take_works(self):
        self.run_scenario('target-output-gate')

    def test_auto_start_receiver_and_notification_actions(self):
        self.run_scenario('auto-start-and-notification')

    def test_remote_audio_source_releases_stale_a2dp_when_idle(self):
        self.run_scenario('remote-audio-source-release')

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
                     'StatusSummary', 'StatusSummaryPolicy', 'AutoStartPolicy', 'BluetoothAutoStartReceiver', 'NotificationActionReceiver',
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
