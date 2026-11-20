"""Run production MainActivity against host-only Android boundaries."""
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
 public static final String POST_NOTIFICATIONS="android.permission.POST_NOTIFICATIONS";
}}''',
    'android/content/pm/PackageManager.java': 'package android.content.pm; public class PackageManager {public static final int PERMISSION_GRANTED=0;}',
    'android/content/pm/ApplicationInfo.java': 'package android.content.pm; public class ApplicationInfo {public int targetSdkVersion=36;}',
    'android/content/res/Configuration.java': 'package android.content.res; public class Configuration {public static final int UI_MODE_NIGHT_MASK=48,UI_MODE_NIGHT_YES=32;public int uiMode=0;}',
    'android/content/res/Resources.java': 'package android.content.res; public class Resources {private final Configuration c=new Configuration();public Configuration getConfiguration(){return c;}}',
    'android/os/Bundle.java': '''package android.os;
import java.util.*;
public class Bundle {
 private final Map<String,String> strings=new HashMap<>();
 public void putString(String k,String v){strings.put(k,v);}
 public String getString(String k){return strings.get(k);}
}''',
    'android/os/Build.java': '''package android.os;
public class Build {
 public static final String MODEL="SM-X810";
 public static class VERSION {public static final int SDK_INT=36;public static final String RELEASE="16";}
}''',
    'android/os/Looper.java': 'package android.os; public class Looper {public static Looper getMainLooper(){return new Looper();}}',
    'android/os/SystemClock.java': 'package android.os; public class SystemClock {public static long elapsedRealtime(){return 0;}}',
    'android/os/IBinder.java': 'package android.os; public interface IBinder {}',
    'android/os/Handler.java': '''package android.os;
public class Handler {
 public Handler(Looper l){}
 public boolean post(Runnable r){r.run();return true;}
 public boolean postDelayed(Runnable r,long d){return true;}
 public void removeCallbacks(Runnable r){}
 public void removeCallbacksAndMessages(Object t){}
}''',
    'android/util/Log.java': 'package android.util; public class Log {public static int i(String t,String m){return 0;}public static int e(String t,String m,Throwable e){return 0;}}',
    'android/provider/Settings.java': 'package android.provider; public class Settings {public static final String ACTION_BLUETOOTH_SETTINGS="bt",ACTION_NOTIFICATION_LISTENER_SETTINGS="nls";}',
    'android/view/View.java': 'package android.view; public class View {}',
    'android/widget/TextView.java': '''package android.widget;
public class TextView extends android.view.View {
 private String text="";
 public TextView(android.content.Context c){}
 public void setText(CharSequence s){text=s==null?"":s.toString();}
 public void append(CharSequence s){text+=s==null?"":s.toString();}
 public String getText(){return text;}
}''',
    'android/widget/ArrayAdapter.java': '''package android.widget;
import java.util.*;
public class ArrayAdapter<T> {
 public final List<T> items;
 public ArrayAdapter(android.content.Context c,int r,List<T> items){this.items=items;}
 public void setDropDownViewResource(int r){}
}''',
    'android/widget/AdapterView.java': '''package android.widget;
public class AdapterView<T> {
 public interface OnItemSelectedListener {void onItemSelected(AdapterView<?> p,android.view.View v,int pos,long id);void onNothingSelected(AdapterView<?> p);}
}''',
    'android/widget/Spinner.java': '''package android.widget;
public class Spinner extends android.view.View {
 private ArrayAdapter<?> adapter;private int selected=0;
 public Spinner(android.content.Context c){}
 public void setAdapter(ArrayAdapter<?> a){adapter=a;}
 public void setSelection(int i){selected=i;}
 public int getSelectedItemPosition(){return selected;}
 public void setOnItemSelectedListener(AdapterView.OnItemSelectedListener l){}
}''',
    'android/widget/Toast.java': 'package android.widget; public class Toast {public static final int LENGTH_SHORT=0;public static Toast makeText(android.content.Context c,CharSequence s,int d){return new Toast();}public void show(){}}',
    'android/window/OnBackInvokedCallback.java': 'package android.window; public interface OnBackInvokedCallback {void onBackInvoked();}',
    'android/window/OnBackInvokedDispatcher.java': 'package android.window; public interface OnBackInvokedDispatcher {int PRIORITY_DEFAULT=0;void registerOnBackInvokedCallback(int p,OnBackInvokedCallback c);void unregisterOnBackInvokedCallback(OnBackInvokedCallback c);}',
    'android/content/DialogInterface.java': 'package android.content; public interface DialogInterface {interface OnClickListener{void onClick(DialogInterface d,int w);}}',
    'android/app/AlertDialog.java': '''package android.app;
import android.content.*;
public class AlertDialog {
 public static DialogInterface.OnClickListener lastPositive,lastNegative;
 public static class Builder {
  public Builder(Context c){}
  public Builder setTitle(CharSequence s){return this;}
  public Builder setMessage(CharSequence s){return this;}
  public Builder setPositiveButton(CharSequence s,DialogInterface.OnClickListener l){lastPositive=l;return this;}
  public Builder setNegativeButton(CharSequence s,DialogInterface.OnClickListener l){lastNegative=l;return this;}
  public AlertDialog show(){return new AlertDialog();}
 }
}''',
    'android/content/Context.java': '''package android.content;
import java.io.*;import java.util.*;
public class Context {
 public static final int MODE_PRIVATE=0,MODE_APPEND=32768,RECEIVER_EXPORTED=2;
 public final Map<Class<?>,Object> services=new HashMap<>();
 public final List<Intent> startedServices=new ArrayList<>();
 public java.util.function.Consumer<Intent> serviceStarter;
 public <T>T getSystemService(Class<T> type){return type.cast(services.get(type));}
 public Context getApplicationContext(){return this;}
 public java.util.concurrent.Executor getMainExecutor(){return Runnable::run;}
 public android.content.res.Resources getResources(){return new android.content.res.Resources();}
 public android.content.pm.ApplicationInfo getApplicationInfo(){return new android.content.pm.ApplicationInfo();}
 public int checkSelfPermission(String p){return android.content.pm.PackageManager.PERMISSION_GRANTED;}
 public void registerReceiver(BroadcastReceiver r,IntentFilter f){}
 public void registerReceiver(BroadcastReceiver r,IntentFilter f,int flags){}
 public void unregisterReceiver(BroadcastReceiver r){}
 public FileOutputStream openFileOutput(String name,int mode)throws IOException{throw new IOException("host logging disabled");}
 public SharedPreferences getSharedPreferences(String name,int mode){return new SharedPreferences();}
 public ComponentName startForegroundService(Intent i){startedServices.add(i);if(serviceStarter!=null)serviceStarter.accept(i);return null;}
 public boolean stopService(Intent i){return true;}
 public void startActivity(Intent i){}
}''',
    'android/content/SharedPreferences.java': '''package android.content;
public class SharedPreferences {
 public String getString(String k,String d){return d;}
 public Editor edit(){return new Editor();}
 public static class Editor {public Editor putString(String k,String v){return this;}public void apply(){}}
}''',
    'android/content/Intent.java': '''package android.content;
import java.util.*;
public class Intent {
 private String action;
 private final Map<String,Object> extras=new HashMap<>();
 public Intent(){}
 public Intent(String a){action=a;}
 public Intent(Context c,Class<?> cls){}
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
    'android/service/notification/NotificationListenerService.java': 'package android.service.notification; public class NotificationListenerService {public void onListenerConnected(){}public void onListenerDisconnected(){}}',
    'android/app/Activity.java': '''package android.app;
public class Activity extends android.content.Context {
 public void setTheme(int r){}
 public void onCreate(android.os.Bundle b){}
 protected void onStart(){}
 protected void onResume(){}
 protected void onPause(){}
 protected void onStop(){}
 protected void onSaveInstanceState(android.os.Bundle b){}
 public void onBackPressed(){}
 public void onRequestPermissionsResult(int r,String[] p,int[] res){}
 public void onDestroy(){}
 public void finish(){}
 public void requestPermissions(String[] p,int r){}
 public android.window.OnBackInvokedDispatcher getOnBackInvokedDispatcher(){return new android.window.OnBackInvokedDispatcher(){public void registerOnBackInvokedCallback(int p,android.window.OnBackInvokedCallback c){}public void unregisterOnBackInvokedCallback(android.window.OnBackInvokedCallback c){}};}
}''',
    'android/app/ForegroundServiceStartNotAllowedException.java': 'package android.app; public class ForegroundServiceStartNotAllowedException extends IllegalStateException {}',
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
 public void createNotificationChannel(NotificationChannel c){}
 public void notify(int i,Notification n){}
 public boolean isNotificationListenerAccessGranted(android.content.ComponentName c){return true;}
}''',
    'android/R.java': '''package android;
public class R {
 public static class drawable {public static final int stat_sys_data_bluetooth=1;}
 public static class style {public static final int Theme_Material_NoActionBar=2,Theme_Material_Light_NoActionBar=3;}
 public static class layout {public static final int simple_spinner_item=4,simple_spinner_dropdown_item=5;}
}''',
    'android/bluetooth/BluetoothDevice.java': '''package android.bluetooth;
public class BluetoothDevice {
 public static final String EXTRA_DEVICE="device";
 private final String address,name;
 public BluetoothDevice(String a){this(a,"AirPods");}
 public BluetoothDevice(String a,String n){address=a;name=n;}
 public String getAddress(){return address;}
 public String getName(){return name;}
 @Override public boolean equals(Object o){return o instanceof BluetoothDevice&&address.equalsIgnoreCase(((BluetoothDevice)o).address);}
}''',
    'android/bluetooth/BluetoothAdapter.java': '''package android.bluetooth;
import java.util.*;
public class BluetoothAdapter {
 public static final String ACTION_STATE_CHANGED="android.bluetooth.adapter.action.STATE_CHANGED";
 public static final Set<BluetoothDevice> bonded=new LinkedHashSet<>();
 public static boolean checkBluetoothAddress(String s){return s!=null&&s.matches("[0-9A-F]{2}(:[0-9A-F]{2}){5}");}
 public BluetoothDevice getRemoteDevice(String a){return new BluetoothDevice(a);}
 public Set<BluetoothDevice> getBondedDevices(){return bonded;}
 public void closeProfileProxy(int k,BluetoothProfile p){}
 public boolean getProfileProxy(android.content.Context c,BluetoothProfile.ServiceListener l,int k){return true;}
 public boolean isEnabled(){return true;}
}''',
    'android/bluetooth/BluetoothManager.java': 'package android.bluetooth; public class BluetoothManager {public BluetoothAdapter getAdapter(){return new BluetoothAdapter();}}',
    'android/bluetooth/BluetoothProfile.java': '''package android.bluetooth;
public interface BluetoothProfile {
 int A2DP=2,HEADSET=1,STATE_CONNECTED=2,STATE_CONNECTING=1,STATE_DISCONNECTING=3,STATE_DISCONNECTED=0;
 String EXTRA_STATE="state";
 int getConnectionState(BluetoothDevice d);
 interface ServiceListener {void onServiceConnected(int k,BluetoothProfile p);void onServiceDisconnected(int k);}
}''',
    'android/bluetooth/BluetoothA2dp.java': 'package android.bluetooth; public class BluetoothA2dp {public static final String ACTION_CONNECTION_STATE_CHANGED="android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED";}',
    'android/bluetooth/BluetoothHeadset.java': 'package android.bluetooth; public class BluetoothHeadset {public static final String ACTION_CONNECTION_STATE_CHANGED="android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED",ACTION_AUDIO_STATE_CHANGED="android.bluetooth.headset.profile.action.AUDIO_STATE_CHANGED";}',
    'android/media/session/MediaSession.java': 'package android.media.session; public class MediaSession {public static class Token {}}',
    'android/media/session/PlaybackState.java': 'package android.media.session; public class PlaybackState {public static final int STATE_PLAYING=3,STATE_BUFFERING=6,STATE_CONNECTING=8;private final int s;public PlaybackState(int s){this.s=s;}public int getState(){return s;}public long getLastPositionUpdateTime(){return 0L;}}',
    'android/media/session/MediaController.java': 'package android.media.session; public class MediaController {public String getPackageName(){return "p";}public MediaSession.Token getSessionToken(){return new MediaSession.Token();}public PlaybackState getPlaybackState(){return new PlaybackState(0);}public void registerCallback(Callback c,android.os.Handler h){}public void unregisterCallback(Callback c){}public TransportControls getTransportControls(){return new TransportControls();}public static class Callback{public void onPlaybackStateChanged(PlaybackState s){}}public static class TransportControls{public void pause(){}}}',
    'android/media/session/MediaSessionManager.java': 'package android.media.session; import java.util.*; public class MediaSessionManager {public List<MediaController> getActiveSessions(android.content.ComponentName c){return Collections.emptyList();}public void addOnActiveSessionsChangedListener(OnActiveSessionsChangedListener l,android.content.ComponentName c,android.os.Handler h){}public void removeOnActiveSessionsChangedListener(OnActiveSessionsChangedListener l){}public interface OnActiveSessionsChangedListener{void onActiveSessionsChanged(List<MediaController> l);}}',
    'android/media/AudioManager.java': '''package android.media;
import java.util.*;
public class AudioManager {
 public static final int GET_DEVICES_OUTPUTS=2,MODE_NORMAL=0,MODE_IN_CALL=2,MODE_IN_COMMUNICATION=3;
 public interface OnModeChangedListener{void onModeChanged(int m);}
 public interface OnCommunicationDeviceChangedListener{void onCommunicationDeviceChanged(AudioDeviceInfo d);}
 public static abstract class AudioPlaybackCallback{public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> c){}}
 public AudioDeviceInfo[] getDevices(int f){return new AudioDeviceInfo[0];}
 public int getMode(){return MODE_NORMAL;}
 public AudioDeviceInfo getCommunicationDevice(){return null;}
 public List<AudioPlaybackConfiguration> getActivePlaybackConfigurations(){return Collections.emptyList();}
 public List<AudioDeviceInfo> getAudioDevicesForAttributes(AudioAttributes a){return Collections.emptyList();}
 public void registerAudioDeviceCallback(AudioDeviceCallback c,android.os.Handler h){}
 public void unregisterAudioDeviceCallback(AudioDeviceCallback c){}
 public void registerAudioPlaybackCallback(AudioPlaybackCallback c,android.os.Handler h){}
 public void unregisterAudioPlaybackCallback(AudioPlaybackCallback c){}
 public void addOnModeChangedListener(java.util.concurrent.Executor e,OnModeChangedListener l){}
 public void removeOnModeChangedListener(OnModeChangedListener l){}
 public void addOnCommunicationDeviceChangedListener(java.util.concurrent.Executor e,OnCommunicationDeviceChangedListener l){}
 public void removeOnCommunicationDeviceChangedListener(OnCommunicationDeviceChangedListener l){}
}''',
    'android/media/MediaMetadata.java': 'package android.media; public class MediaMetadata {}',
    'android/media/AudioAttributes.java': '''package android.media;
public class AudioAttributes {
 public static final int USAGE_UNKNOWN=0,USAGE_MEDIA=1,USAGE_GAME=14,CONTENT_TYPE_MUSIC=2;
 public int getUsage(){return USAGE_MEDIA;}
 public static class Builder{public Builder setUsage(int u){return this;}public Builder setContentType(int c){return this;}public AudioAttributes build(){return new AudioAttributes();}}
}''',
    'android/media/AudioDeviceInfo.java': '''package android.media;
public class AudioDeviceInfo {
 public static final int TYPE_BUILTIN_EARPIECE=1,TYPE_BUILTIN_SPEAKER=2,TYPE_WIRED_HEADSET=3,TYPE_WIRED_HEADPHONES=4,TYPE_BLUETOOTH_SCO=7,TYPE_BLUETOOTH_A2DP=8,TYPE_USB_DEVICE=11,TYPE_USB_HEADSET=22,TYPE_HEARING_AID=23,TYPE_BLE_HEADSET=26,TYPE_BLE_SPEAKER=27,TYPE_BLE_BROADCAST=30;
 public int getType(){return TYPE_UNKNOWN;} public static final int TYPE_UNKNOWN=0;
 public String getAddress(){return "";} public CharSequence getProductName(){return "";}
}''',
    'android/media/AudioDeviceCallback.java': 'package android.media; public abstract class AudioDeviceCallback {public void onAudioDevicesAdded(AudioDeviceInfo[] a){}public void onAudioDevicesRemoved(AudioDeviceInfo[] r){}}',
    'android/media/AudioPlaybackConfiguration.java': 'package android.media; public class AudioPlaybackConfiguration {public AudioAttributes getAudioAttributes(){return null;}public AudioDeviceInfo getAudioDeviceInfo(){return null;}}',
    'android/view/KeyEvent.java': 'package android.view; public class KeyEvent {}',
    'org/lsposed/hiddenapibypass/HiddenApiBypass.java': 'package org.lsposed.hiddenapibypass; public class HiddenApiBypass {public static boolean addHiddenApiExemptions(String... s){return true;}}',
    PACKAGE + 'LinkPodsUi.java': '''package local.airpods.handoffprobe;
import android.app.Activity;import android.widget.*;
final class LinkPodsUi {
 interface Actions {
  void openBluetooth();void requestNearby();void openMediaAccess();void requestNotifications();
  void refreshDevices();void connectEarbuds();void enableHandoff();void disableHandoff();void manualHandoff();
  void stopConnection();void stopHandoff();void checkEnvironment();void prepareBridge();
  boolean setSetting(int id,int value);String deviceName();String connectionStatus();
 }
 final TextView environmentStatus,logOutput;
 final Actions actions;
 LinkPodsUi(Activity a,Spinner p,Actions actions,String page){
  this.actions=actions;this.environmentStatus=new TextView(a);this.logOutput=new TextView(a);
 }
 String page(){return "设备";}
 boolean back(){return false;}
 void bind(EarbudSnapshot s,String a,SessionStatus st){}
 void bindAudio(SamsungAudioSnapshot s){}
 void bindCapabilities(ConnectionPolicy.Snapshot c,boolean m){}
}''',
    PACKAGE + 'RootEnvironment.java': '''package local.airpods.handoffprobe;
class RootEnvironment {
 static final java.util.concurrent.ExecutorService WORK=new java.util.concurrent.AbstractExecutorService(){
  public void shutdown(){}public java.util.List<Runnable> shutdownNow(){return java.util.Collections.emptyList();}
  public boolean isShutdown(){return false;}public boolean isTerminated(){return false;}
  public boolean awaitTermination(long t,java.util.concurrent.TimeUnit u){return true;}
  public void execute(Runnable r){r.run();}
 };
 static boolean nextUsable=true,nextTempSupported=false;
 static RootAccess.Status nextStatus=RootAccess.Status.EXISTING_ROOT;
 static class Lease {boolean sameInstance(Lease l){return this==l;}}
 static class Prepared {
  boolean bridgeOwned,temporarySupported,temporaryAttempted;RootAccess.Result access;
  String targetAddress="",local="",adapterId="",missing="",diagnostic="";Lease lease=new Lease();
 }
 static boolean bridgeUsable(Prepared p){return nextUsable&&p!=null;}
 static boolean acceptBridge(Prepared p){return nextUsable&&p!=null;}
 static void queueBridgeStop(Prepared p){}
 static void markBridgeStopping(Prepared p){}
 static boolean stopOwnedBridge(Prepared p){return true;}
 static Prepared prepare(android.content.Context c,String a,boolean enable,boolean allowTemp){
  Prepared p=new Prepared();
  p.targetAddress=a==null?"":a;p.local="AA:BB:CC:DD:EE:02";p.adapterId="sm-x810";
  p.bridgeOwned=nextUsable;p.temporarySupported=nextTempSupported;
  p.access=new RootAccess.Result(nextStatus);
  return p;
 }
}''',
    PACKAGE + 'AapProbe.java': '''package local.airpods.handoffprobe;
class AapProbe {
 interface Logger {void write(String m);}enum Failure {COMPATIBILITY_REQUIRED,OTHER}
 interface Listener {void ready();void packet(byte[] b);void ended();default void failure(Failure f){}}
 AapProbe(android.bluetooth.BluetoothDevice d,Logger l,Listener x){}
 void sendPacketAsync(byte[] b,java.util.function.Consumer<Boolean> done){done.accept(true);}
 void sendPacketAsync(byte[] b,java.util.function.BooleanSupplier g,java.util.function.Consumer<Boolean> done){done.accept(true);}
 void requestState(){}void start(){}void stop(){}
}''',
    PACKAGE + 'MainActivityTest.java': '''package local.airpods.handoffprobe;
import android.app.*;import android.bluetooth.*;import android.content.*;import android.media.*;import android.media.session.*;import android.os.*;import java.lang.reflect.*;
public class MainActivityTest {
 static final String TARGET="AA:BB:CC:DD:EE:01",OTHER="AA:BB:CC:DD:EE:09",SELF="AA:BB:CC:DD:EE:02",PEER="AA:BB:CC:DD:EE:03";
 static void expect(boolean ok,String why){if(!ok)throw new AssertionError(why);}
 static Object get(Object o,String n)throws Exception{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}
 static void set(Object o,String n,Object v)throws Exception{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);f.set(o,v);}
 static MainActivity createActivity(Bundle state)throws Exception{
  MainActivity a=new MainActivity();
  a.services.put(BluetoothManager.class,new BluetoothManager());
  a.services.put(NotificationManager.class,new NotificationManager());
  a.services.put(AudioManager.class,new AudioManager());
  a.serviceStarter=i->{if(HandoffService.current!=null)HandoffService.current.onStartCommand(i,0,1);};
  a.onCreate(state);
  return a;
 }
 static HandoffService runningService(String target,SessionMode mode)throws Exception{
  HandoffService s=new HandoffService();HandoffService.current=s;
  s.services.put(MediaSessionManager.class,new MediaSessionManager());
  s.services.put(NotificationManager.class,new NotificationManager());
  set(s,"adapter",new BluetoothAdapter());
  set(s,"audio",new AudioManager());
  set(s,"deviceConfig",DeviceConfig.create(target,SELF,PEER));
  set(s,"target",new BluetoothDevice(target));
  set(s,"mode",mode);
  return s;
 }
 public static void main(String[] args)throws Exception{
  BluetoothAdapter.bonded.clear();
  BluetoothAdapter.bonded.add(new BluetoothDevice(TARGET,"AirPods Pro"));
  BluetoothAdapter.bonded.add(new BluetoothDevice(OTHER,"Other Pods"));

  // Scenario 1: AUTO_HANDOFF running -> reopen Activity fresh (null Bundle) -> prepareBridge preserves AUTO_HANDOFF.
  runningService(TARGET,SessionMode.AUTO_HANDOFF);
  RootEnvironment.nextUsable=true;RootEnvironment.nextTempSupported=false;RootEnvironment.nextStatus=RootAccess.Status.EXISTING_ROOT;
  MainActivity reopened=createActivity(null);
  ((LinkPodsUi)get(reopened,"ui")).actions.prepareBridge();
  expect(reopened.startedServices.size()==1,"prepareBridge starts service once");
  expect("AUTO_HANDOFF".equals(reopened.startedServices.get(0).getStringExtra(HandoffService.EXTRA_SESSION_MODE)),
   "reopened activity must preserve running AUTO_HANDOFF when preparing bridge");
  reopened.onDestroy();

  // Scenario 2: Temp root prompt path preserves fixed AUTO_HANDOFF for both positive and negative buttons.
  RootEnvironment.nextUsable=false;RootEnvironment.nextTempSupported=true;RootEnvironment.nextStatus=RootAccess.Status.UNSUPPORTED;
  MainActivity tempPrompt=createActivity(null);
  ((LinkPodsUi)get(tempPrompt,"ui")).actions.prepareBridge();
  expect(tempPrompt.startedServices.isEmpty(),"temporary root prompt pauses before starting service");
  expect(AlertDialog.lastPositive!=null&&AlertDialog.lastNegative!=null,"temporary root dialog exposes both actions");
  AlertDialog.lastNegative.onClick(null,0);
  expect(tempPrompt.startedServices.size()==1&&"AUTO_HANDOFF".equals(tempPrompt.startedServices.get(0).getStringExtra(HandoffService.EXTRA_SESSION_MODE)),
   "negative button ('仅连接耳机') keeps active AUTO_HANDOFF mode");
  RootEnvironment.nextUsable=true;RootEnvironment.nextStatus=RootAccess.Status.TEMPORARY_ROOT;
  AlertDialog.lastPositive.onClick(null,0);
  expect(tempPrompt.startedServices.size()==2&&"AUTO_HANDOFF".equals(tempPrompt.startedServices.get(1).getStringExtra(HandoffService.EXTRA_SESSION_MODE)),
   "positive button ('继续') keeps active AUTO_HANDOFF mode");
  tempPrompt.onDestroy();

  // Scenario 3: Foreign target selected (no active session for it) defaults bridge preparation to CONTROL_ONLY.
  MainActivity otherSelected=createActivity(null);
  ((android.widget.Spinner)get(otherSelected,"picker")).setSelection(1);
  ((LinkPodsUi)get(otherSelected,"ui")).actions.prepareBridge();
  expect(otherSelected.startedServices.size()==1&&"CONTROL_ONLY".equals(otherSelected.startedServices.get(0).getStringExtra(HandoffService.EXTRA_SESSION_MODE)),
   "inactive target defaults bridge preparation to CONTROL_ONLY");
  otherSelected.onDestroy();

  // Scenario 4: No active service at all defaults bridge preparation to CONTROL_ONLY.
  HandoffService.current=null;
  MainActivity idle=createActivity(null);
  ((LinkPodsUi)get(idle,"ui")).actions.prepareBridge();
  expect(idle.startedServices.size()==1&&"CONTROL_ONLY".equals(idle.startedServices.get(0).getStringExtra(HandoffService.EXTRA_SESSION_MODE)),
   "idle service defaults bridge preparation to CONTROL_ONLY");

  // Scenario 5: onSaveInstanceState persists requested_mode across rotation.
  set(idle,"requestedMode",SessionMode.AUTO_HANDOFF);
  Bundle saved=new Bundle();
  idle.onSaveInstanceState(saved);
  expect("AUTO_HANDOFF".equals(saved.getString("requested_mode")),"onSaveInstanceState saves requested_mode");
  MainActivity rotated=createActivity(saved);
  expect(get(rotated,"requestedMode")==SessionMode.AUTO_HANDOFF,"onCreate restores requested_mode from savedInstanceState");
  idle.onDestroy();rotated.onDestroy();

  // Scenario 6: Fresh Activity recreation while service is running for OTHER device restores target & mode without restarting service or duplicating monitor.
  runningService(OTHER,SessionMode.AUTO_HANDOFF);
  MainActivity recovered=createActivity(null);
  expect(recovered.startedServices.isEmpty(),"reopening activity never calls startForegroundService");
  expect(get(recovered,"requestedMode")==SessionMode.AUTO_HANDOFF,"fresh reopen syncs AUTO_HANDOFF from running service");
  expect(((android.widget.Spinner)get(recovered,"picker")).getSelectedItemPosition()==1,
   "fresh reopen selects active service target OTHER in picker");
  SamsungAudioMonitor recoveredMonitor=(SamsungAudioMonitor)get(recovered,"localAudioMonitor");
  expect(!recoveredMonitor.isStarted(),"localAudioMonitor stays stopped while HandoffService monitor is active");
  recovered.onDestroy();

  // Scenario 7: When service is stopped, onStart starts localAudioMonitor and onStop releases it without starting service.
  HandoffService.current=null;
  MainActivity backgrounded=createActivity(null);
  SamsungAudioMonitor bgMonitor=(SamsungAudioMonitor)get(backgrounded,"localAudioMonitor");
  expect(bgMonitor.isStarted(),"idle activity starts localAudioMonitor while active");
  backgrounded.onStop();
  expect(!bgMonitor.isStarted(),"onStop stops localAudioMonitor when screen locks or app goes to background");
  backgrounded.onStart();
  expect(bgMonitor.isStarted()&&backgrounded.startedServices.isEmpty(),
   "onStart resumes localAudioMonitor without starting foreground service");
  backgrounded.onDestroy();
  expect(!bgMonitor.isStarted(),"onDestroy stops localAudioMonitor cleanly");

  System.out.println("PASS: main activity bridge preparation and session lifecycle recovery");
 }
}''',
}


class MainActivityBridgeTests(unittest.TestCase):
    def test_bridge_preparation_preserves_active_handoff_after_activity_recreation(self):
        jdk = Path(os.environ['JAVA_HOME']) / 'bin' if os.environ.get('JAVA_HOME') else None
        javac = shutil.which('javac', path=str(jdk) if jdk else None)
        java = shutil.which('java', path=str(jdk) if jdk else None)
        if not javac or not java:
            self.skipTest('JDK required')
        with tempfile.TemporaryDirectory(prefix='linkpods-activity-') as directory:
            output = Path(directory)
            files = []
            for name, content in STUBS.items():
                target = output / name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text(content, encoding='utf-8')
                files.append(str(target))
            package = ROOT / 'apps/android/src' / PACKAGE
            names = ('MainActivity', 'HandoffService', 'PlaybackListener', 'PlaybackWatch', 'ControlSession', 'SessionMode',
                     'SessionStatus', 'SamsungAudioMonitor', 'SamsungAudioSnapshot',
                     'SamsungAudioPolicy', 'RoutingPolicy', 'AudioLinkPolicy',
                     'PeerRoutingPolicy', 'DeviceConfig', 'ConnectionPolicy', 'RequestGate',
                     'RootAccess', 'RoutingPackets', 'AapPackets', 'SmartRouting',
                     'EarbudCommands', 'EarbudSnapshot', 'AirPodsModels',
                     'AirPodsInformation', 'BackendPolicy')
            files += [str(package / (name + '.java')) for name in names]
            compiled = subprocess.run([javac, '-encoding', 'UTF-8', '-d', str(output), *files],
                                      capture_output=True, text=True, timeout=30)
            self.assertEqual(compiled.returncode, 0, compiled.stderr)
            result = subprocess.run([java, '-cp', str(output),
                                     'local.airpods.handoffprobe.MainActivityTest'],
                                    capture_output=True, text=True, timeout=10)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
