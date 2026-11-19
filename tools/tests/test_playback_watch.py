"""Run PlaybackWatch lifecycle and batch-isolation tests against host-only Android stubs."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
PACKAGE = 'local/airpods/handoffprobe/'
STUBS = {
    'android/content/ComponentName.java':
        'package android.content; public class ComponentName {public ComponentName(Context c,Class<?> t){}}',
    'android/content/Context.java': '''package android.content;
import java.util.*;
public class Context {
 public final Map<Class<?>,Object> services=new HashMap<>();
 public <T> T getSystemService(Class<T> type){return type.cast(services.get(type));}
}''',
    'android/app/NotificationManager.java': '''package android.app;
public class NotificationManager {
 public boolean notificationAccessGranted=true;
 public boolean isNotificationListenerAccessGranted(android.content.ComponentName c){return notificationAccessGranted;}
}''',
    'android/media/session/MediaSession.java':
        'package android.media.session; public class MediaSession {public static class Token {}}',
    'android/media/session/PlaybackState.java': '''package android.media.session;
public class PlaybackState {
 public static final int STATE_PAUSED=2,STATE_PLAYING=3,STATE_BUFFERING=6,STATE_CONNECTING=8;
 private final int state;
 public PlaybackState(int s){state=s;}
 public int getState(){return state;}
}''',
    'android/media/session/MediaController.java': '''package android.media.session;
import java.util.*;
public class MediaController {
 public final Set<Callback> callbacks=new HashSet<>();
 public Callback lastCallback;
 public int registered,unregistered,paused;
 public java.util.function.Consumer<Callback> onRegister;
 private final String pkg;
 private int playbackState;
 private final MediaSession.Token token=new MediaSession.Token();
 public MediaController(String pkg,int state){this.pkg=pkg;this.playbackState=state;}
 public String getPackageName(){return pkg;}
 public MediaSession.Token getSessionToken(){return token;}
 public PlaybackState getPlaybackState(){return new PlaybackState(playbackState);}
 public void setPlaybackState(int state){playbackState=state;}
 public void registerCallback(Callback c,android.os.Handler h){
  callbacks.add(c);lastCallback=c;registered++;
  if(onRegister!=null)onRegister.accept(c);
 }
 public void unregisterCallback(Callback c){if(callbacks.remove(c))unregistered++;}
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
 public List<MediaController> active=new ArrayList<>();
 public boolean rejectRegistration,rejectGetActiveSessions;
 public int added,removed;
 public final List<String> callOrder=new ArrayList<>();
 public OnActiveSessionsChangedListener lastListener;
 public final List<OnActiveSessionsChangedListener> listeners=new ArrayList<>();
 public List<MediaController> getActiveSessions(android.content.ComponentName c){
  callOrder.add("getActiveSessions");
  if(rejectGetActiveSessions)throw new SecurityException("getActiveSessions denied");
  return new ArrayList<>(active);
 }
 public void addOnActiveSessionsChangedListener(OnActiveSessionsChangedListener l,android.content.ComponentName c,android.os.Handler h){
  callOrder.add("addListener");
  if(rejectRegistration)throw new SecurityException("permission revoked");
  added++;lastListener=l;listeners.add(l);
 }
 public void removeOnActiveSessionsChangedListener(OnActiveSessionsChangedListener l){
  if(listeners.remove(l))removed++;
 }
 public interface OnActiveSessionsChangedListener {void onActiveSessionsChanged(List<MediaController> l);}
}''',
    'android/os/Build.java':
        'package android.os; public class Build {public static class VERSION {public static final int SDK_INT=36;}}',
    'android/os/Looper.java':
        'package android.os; public class Looper {public static Looper getMainLooper(){return new Looper();}}',
    'android/os/Handler.java': '''package android.os;
import java.util.*;
public class Handler {
 public boolean queuePosted;
 public final List<Runnable> posted=new ArrayList<>();
 public Handler(Looper l){}
 public boolean post(Runnable r){
  if(queuePosted){posted.add(r);return true;}
  r.run();return true;
 }
 public void flushPosted(){
  List<Runnable> pending=new ArrayList<>(posted);
  posted.clear();
  for(Runnable r:pending)r.run();
 }
}''',
    PACKAGE + 'PlaybackWatchTest.java': '''package local.airpods.handoffprobe;
import android.app.*;
import android.content.*;
import android.media.session.*;
import android.os.*;
import java.util.*;

public class PlaybackWatchTest {
 static final class RecordingListener implements PlaybackWatch.Listener {
  final List<Map<String,Integer>> seeded=new ArrayList<>();
  final List<Boolean> seededRestartFlags=new ArrayList<>();
  final List<String> stateChanges=new ArrayList<>();
  final List<String> removedPlayers=new ArrayList<>();
  final List<Boolean> stopped=new ArrayList<>();
  int sessionsSynced;
  @Override public void onPlayersSeeded(Map<String,Integer> states,boolean restarted){
   seeded.add(new LinkedHashMap<>(states));
   seededRestartFlags.add(restarted);
  }
  @Override public void onPlayerStateChanged(String playerId,String packageName,int state){
   stateChanges.add(playerId+":"+packageName+":"+state);
  }
  @Override public void onPlayerRemoved(String playerId){removedPlayers.add(playerId);}
  @Override public void onSessionsSynced(){sessionsSynced++;}
  @Override public void onWatchStopped(boolean accessLost){stopped.add(accessLost);}
 }

 static void expect(boolean ok,String why){if(!ok)throw new AssertionError(why);}

 static void failedStartRollsBackAndRetries(){
  Context ctx=new Context();
  MediaSessionManager manager=new MediaSessionManager();
  NotificationManager nm=new NotificationManager();
  ctx.services.put(MediaSessionManager.class,manager);
  ctx.services.put(NotificationManager.class,nm);
  MediaController controller=new MediaController("host.player",PlaybackState.STATE_PLAYING);
  manager.active.add(controller);
  manager.rejectRegistration=true;
  RecordingListener events=new RecordingListener();
  List<String> logs=new ArrayList<>();
  PlaybackWatch watch=new PlaybackWatch(ctx,new Handler(Looper.getMainLooper()),
   new ComponentName(ctx,PlaybackWatchTest.class),logs::add,events);

  expect(!watch.start(),"failed registration must return false");
  expect(!watch.isWatching()&&!watch.isActive(),"failed start leaves watch inactive");
  expect(controller.registered==0&&controller.callbacks.isEmpty(),"failed listener registration occurs before player seeding");
  expect(manager.removed==0,"failed listener registration does not call remove");
  expect(events.seeded.isEmpty()&&events.stopped.isEmpty(),"failed start emits no seeded or stopped events");

  // Listener registration succeeds, then getActiveSessions() fails -> must roll back the registered listener.
  manager.rejectRegistration=false;
  manager.rejectGetActiveSessions=true;
  expect(!watch.start(),"failed getActiveSessions must return false");
  expect(!watch.isWatching()&&!watch.isActive(),"failed getActiveSessions leaves watch inactive");
  expect(manager.added==1&&manager.removed==1&&manager.listeners.isEmpty(),"failed getActiveSessions rolls back registered session listener");
  expect(events.seeded.isEmpty()&&events.stopped.isEmpty(),"failed getActiveSessions emits no seeded or stopped events");

  manager.rejectGetActiveSessions=false;
  manager.callOrder.clear();
  expect(watch.start(),"retry start succeeds");
  expect(manager.callOrder.equals(Arrays.asList("addListener","getActiveSessions")),
   "session listener is registered before reading initial active sessions");
  expect(watch.isWatching()&&watch.isActive(),"successful start activates watch");
  expect(controller.callbacks.size()==1,"retry registers one fresh player callback");
  expect(events.seeded.size()==1&&!events.seededRestartFlags.get(0),"initial start emits seeded without restart flag");

  watch.stop();
  watch.stop();
  expect(!watch.isWatching()&&!watch.isActive(),"stop clears watch state");
  expect(controller.callbacks.isEmpty()&&manager.removed==2,"stop unregisters player and manager listener once");
  expect(events.stopped.size()==1&&!events.stopped.get(0),"repeated stop emits one non-access-lost stop");
 }

 static void staleCallbacksIsolatedAcrossBatchesAndRemoval(){
  Context ctx=new Context();
  MediaSessionManager manager=new MediaSessionManager();
  NotificationManager nm=new NotificationManager();
  ctx.services.put(MediaSessionManager.class,manager);
  ctx.services.put(NotificationManager.class,nm);
  MediaController first=new MediaController("first.player",PlaybackState.STATE_PLAYING);
  manager.active.add(first);
  RecordingListener events=new RecordingListener();
  PlaybackWatch watch=new PlaybackWatch(ctx,new Handler(Looper.getMainLooper()),
   new ComponentName(ctx,PlaybackWatchTest.class),msg->{},events);

  expect(watch.start(),"batch 1 starts");
  MediaSessionManager.OnActiveSessionsChangedListener batch1Listener=manager.lastListener;
  MediaController.Callback batch1PlayerCallback=first.lastCallback;

  // Stop and start batch 2 with a different player.
  watch.stop();
  MediaController second=new MediaController("second.player",PlaybackState.STATE_PAUSED);
  manager.active.clear();
  manager.active.add(second);
  expect(watch.start(),"batch 2 starts");
  MediaSessionManager.OnActiveSessionsChangedListener batch2Listener=manager.lastListener;
  MediaController.Callback batch2PlayerCallback=second.lastCallback;
  events.stateChanges.clear();
  events.removedPlayers.clear();

  // Late callbacks from batch 1 must be ignored completely.
  MediaController injectedFromBatch1=new MediaController("stale.player",PlaybackState.STATE_PLAYING);
  batch1Listener.onActiveSessionsChanged(Collections.singletonList(injectedFromBatch1));
  batch1PlayerCallback.onPlaybackStateChanged(new PlaybackState(PlaybackState.STATE_PLAYING));
  expect(events.stateChanges.isEmpty(),"stale batch 1 callbacks emit no playback events");
  expect(events.removedPlayers.isEmpty(),"stale batch 1 session list cannot remove batch 2 player");
  expect(second.callbacks.size()==1,"batch 2 player subscription stays intact");

  // Also test restart() isolates pre-restart callbacks and preserves restarted=true.
  expect(watch.restart(),"restart starts batch 3");
  expect(events.seededRestartFlags.get(events.seededRestartFlags.size()-1),"restart sets restarted=true");
  expect(events.stopped.size()==1,"restart does not emit intermediate onWatchStopped");
  batch2Listener.onActiveSessionsChanged(Collections.emptyList());
  batch2PlayerCallback.onPlaybackStateChanged(new PlaybackState(PlaybackState.STATE_PLAYING));
  expect(events.stateChanges.isEmpty()&&events.removedPlayers.isEmpty(),"stale batch 2 callbacks after restart are ignored");

  // Removing a player within the active batch also fences late callbacks from that removed player.
  MediaController.Callback batch3PlayerCallback=second.lastCallback;
  manager.lastListener.onActiveSessionsChanged(Collections.emptyList());
  expect(events.removedPlayers.size()==1,"active batch removes missing player");
  batch3PlayerCallback.onPlaybackStateChanged(new PlaybackState(PlaybackState.STATE_PLAYING));
  expect(events.stateChanges.isEmpty(),"late callback from removed player in same batch is ignored");

  // Stale queued PLAYING callback when controller is already PAUSED must be discarded.
  manager.lastListener.onActiveSessionsChanged(Collections.singletonList(second));
  events.stateChanges.clear();
  second.setPlaybackState(PlaybackState.STATE_PAUSED);
  second.lastCallback.onPlaybackStateChanged(new PlaybackState(PlaybackState.STATE_PLAYING));
  expect(events.stateChanges.isEmpty(),"stale queued PLAYING callback when controller is paused is discarded");

  // Current PLAYING callback with matching live controller state is forwarded.
  second.setPlaybackState(PlaybackState.STATE_PLAYING);
  second.lastCallback.onPlaybackStateChanged(new PlaybackState(PlaybackState.STATE_PLAYING));
  expect(events.stateChanges.size()==1,"live PLAYING callback matching controller state is forwarded");

  // Transition between callback registration and reading initial state:
  // 1) PAUSED -> PLAYING during registration must not miss PLAYING in initial seed.
  watch.stop();
  events.seeded.clear();
  MediaController transitionToPlay=new MediaController("late.start",PlaybackState.STATE_PAUSED);
  transitionToPlay.onRegister=cb->transitionToPlay.setPlaybackState(PlaybackState.STATE_PLAYING);
  manager.active.clear();
  manager.active.add(transitionToPlay);
  expect(watch.start(),"watch starts during PAUSED->PLAYING race");
  expect(Integer.valueOf(PlaybackState.STATE_PLAYING).equals(
    events.seeded.get(0).get(transitionToPlay.getSessionToken().toString())),
   "register-before-read captures PAUSED->PLAYING transition during startup");

  // 2) Queued PLAYING callback followed by PAUSE before read must not trigger PLAYING event.
  watch.stop();
  events.seeded.clear();
  events.stateChanges.clear();
  MediaController transitionToPause=new MediaController("quick.pause",PlaybackState.STATE_PLAYING);
  final MediaController.Callback[] queuedPlay=new MediaController.Callback[1];
  transitionToPause.onRegister=cb->{
   queuedPlay[0]=cb;
   transitionToPause.setPlaybackState(PlaybackState.STATE_PAUSED);
  };
  manager.active.clear();
  manager.active.add(transitionToPause);
  expect(watch.start(),"watch starts during PLAYING->PAUSED race");
  expect(Integer.valueOf(PlaybackState.STATE_PAUSED).equals(
    events.seeded.get(0).get(transitionToPause.getSessionToken().toString())),
   "initial seed observes final PAUSED state");
  queuedPlay[0].onPlaybackStateChanged(new PlaybackState(PlaybackState.STATE_PLAYING));
  expect(events.stateChanges.isEmpty(),"stale queued PLAYING callback after PAUSE does not fire");

  // 3) Pre-baseline PAUSED -> PLAYING callbacks queued before reading PLAYING baseline must NOT trigger TAKE,
  //    while real fast PAUSED -> PLAYING callbacks after the baseline fence still trigger both events.
  watch.stop();
  Handler queuedHandler=new Handler(Looper.getMainLooper());
  queuedHandler.queuePosted=true;
  RecordingListener fenceEvents=new RecordingListener();
  PlaybackWatch fenceWatch=new PlaybackWatch(ctx,queuedHandler,
   new ComponentName(ctx,PlaybackWatchTest.class),msg->{},fenceEvents);
  MediaController preSeedChurn=new MediaController("pre.seed.churn",PlaybackState.STATE_PLAYING);
  manager.active.clear();
  manager.active.add(preSeedChurn);
  expect(fenceWatch.start(),"watch starts with queued baseline fence");
  expect(Integer.valueOf(PlaybackState.STATE_PLAYING).equals(
    fenceEvents.seeded.get(0).get(preSeedChurn.getSessionToken().toString())),
   "baseline sees PLAYING");
  // Callbacks queued before the baseline fence are discarded even if live state is PLAYING.
  preSeedChurn.lastCallback.onPlaybackStateChanged(new PlaybackState(PlaybackState.STATE_PAUSED));
  preSeedChurn.lastCallback.onPlaybackStateChanged(new PlaybackState(PlaybackState.STATE_PLAYING));
  expect(fenceEvents.stateChanges.isEmpty(),"pre-baseline queued PAUSED and PLAYING callbacks are dropped");
  // Flush the baseline fence; baseline still matches live PLAYING so no spurious event fires.
  queuedHandler.flushPosted();
  expect(fenceEvents.stateChanges.isEmpty(),"baseline fence emits nothing when live state matches baseline");
  // Real fast pause + replay after baseline fence (live state already back to PLAYING when PAUSED callback runs)
  // must preserve both PAUSED and PLAYING callbacks so RoutingPolicy sees the replay edge.
  preSeedChurn.setPlaybackState(PlaybackState.STATE_PLAYING);
  preSeedChurn.lastCallback.onPlaybackStateChanged(new PlaybackState(PlaybackState.STATE_PAUSED));
  preSeedChurn.lastCallback.onPlaybackStateChanged(new PlaybackState(PlaybackState.STATE_PLAYING));
  expect(fenceEvents.stateChanges.size()==2
    &&fenceEvents.stateChanges.get(0).endsWith(":"+PlaybackState.STATE_PAUSED)
    &&fenceEvents.stateChanges.get(1).endsWith(":"+PlaybackState.STATE_PLAYING),
   "real fast PAUSED->PLAYING after baseline fence preserves both callbacks");
  fenceWatch.stop();
 }

 static void accessLostAndPauseActive(){
  Context ctx=new Context();
  MediaSessionManager manager=new MediaSessionManager();
  NotificationManager nm=new NotificationManager();
  ctx.services.put(MediaSessionManager.class,manager);
  ctx.services.put(NotificationManager.class,nm);
  MediaController playing=new MediaController("music.app",PlaybackState.STATE_PLAYING);
  MediaController buffering=new MediaController("video.app",PlaybackState.STATE_BUFFERING);
  MediaController paused=new MediaController("podcast.app",PlaybackState.STATE_PAUSED);
  MediaController telecom=new MediaController("com.android.server.telecom",PlaybackState.STATE_PLAYING);
  manager.active.addAll(Arrays.asList(playing,buffering,paused,telecom));
  RecordingListener events=new RecordingListener();
  List<String> logs=new ArrayList<>();
  PlaybackWatch watch=new PlaybackWatch(ctx,new Handler(Looper.getMainLooper()),
   new ComponentName(ctx,PlaybackWatchTest.class),logs::add,events);

  expect(watch.start(),"watch starts with mixed players");
  expect(telecom.callbacks.isEmpty(),"telecom session is never tracked");
  watch.pauseActive();
  expect(playing.paused==1&&buffering.paused==1,"pauseActive pauses playing and buffering players");
  expect(paused.paused==0&&telecom.paused==0,"pauseActive skips paused and telecom players");

  // Recoverable restart failure while access remains granted does NOT emit onWatchStopped, and preserves restarted=true on recovery.
  manager.rejectRegistration=true;
  int stopsBeforeRecoverableFailure=events.stopped.size();
  expect(!watch.restart(),"recoverable restart failure returns false");
  expect(events.stopped.size()==stopsBeforeRecoverableFailure,"recoverable restart failure does not emit onWatchStopped");
  // Access lost while waiting to rebind must still emit onWatchStopped(true) once, and duplicate onAccessLost is a no-op.
  nm.notificationAccessGranted=false;
  watch.onAccessLost();
  expect(events.stopped.size()==stopsBeforeRecoverableFailure+1&&events.stopped.get(events.stopped.size()-1),
   "onAccessLost after recoverable restart failure emits onWatchStopped(true)");
  watch.onAccessLost();
  expect(events.stopped.size()==stopsBeforeRecoverableFailure+1,"repeated onAccessLost is a no-op");
  nm.notificationAccessGranted=true;
  manager.rejectRegistration=false;
  expect(watch.start(),"watch recovers after access-lost");

  // Second recoverable restart failure followed by direct restart recovery preserves restarted=true.
  manager.rejectRegistration=true;
  expect(!watch.restart(),"second recoverable restart failure returns false");
  manager.rejectRegistration=false;
  expect(watch.restart(),"subsequent restart succeeds");
  expect(events.seededRestartFlags.get(events.seededRestartFlags.size()-1),"recovery after recoverable restart failure preserves restarted=true");

  // Revocation via empty session list triggers accessLost=true.
  int stopsBeforeEmptyRevoke=events.stopped.size();
  nm.notificationAccessGranted=false;
  manager.active.clear();
  manager.lastListener.onActiveSessionsChanged(Collections.emptyList());
  expect(!watch.isWatching()&&!watch.isActive(),"empty list without access stops watch");
  expect(events.stopped.size()==stopsBeforeEmptyRevoke+1&&events.stopped.get(stopsBeforeEmptyRevoke),
   "empty list without access emits onWatchStopped(true)");

  // Re-grant and lose via onAccessLost() directly.
  nm.notificationAccessGranted=true;
  expect(watch.restart(),"re-grant restart succeeds");
  nm.notificationAccessGranted=false;
  watch.onAccessLost();
  expect(events.stopped.size()==stopsBeforeEmptyRevoke+2&&events.stopped.get(stopsBeforeEmptyRevoke+1),
   "onAccessLost emits onWatchStopped(true)");
  expect(logs.stream().anyMatch(line->line.equals("MEDIA_SESSION_WATCH access-lost")),"onAccessLost logs access-lost");

  // Restart when access is still revoked while watching tears down with accessLost=true.
  nm.notificationAccessGranted=true;
  expect(watch.start(),"start succeeds again");
  nm.notificationAccessGranted=false;
  expect(!watch.restart(),"restart fails when access revoked");
  expect(events.stopped.size()==stopsBeforeEmptyRevoke+3&&events.stopped.get(stopsBeforeEmptyRevoke+2),
   "failed restart after revoke emits onWatchStopped(true)");

  // Telecom-only session update still triggers onSessionsSynced().
  nm.notificationAccessGranted=true;
  manager.active.clear();
  expect(watch.start(),"start succeeds with no players");
  int syncedBeforeTelecom=events.sessionsSynced;
  manager.lastListener.onActiveSessionsChanged(Collections.singletonList(telecom));
  expect(events.sessionsSynced==syncedBeforeTelecom+1,"telecom-only session list triggers onSessionsSynced");
 }

 public static void main(String[] args){
  switch(args[0]){
   case "failed-start":failedStartRollsBackAndRetries();break;
   case "stale-callbacks":staleCallbacksIsolatedAcrossBatchesAndRemoval();break;
   case "access-and-pause":accessLostAndPauseActive();break;
   default:throw new AssertionError("unknown scenario: "+args[0]);
  }
  System.out.println("PASS: playback watch "+args[0]);
 }
}''',
}


class PlaybackWatchTests(unittest.TestCase):
    def test_failed_start_rolls_back_and_retries(self):
        self.run_scenario('failed-start')

    def test_stale_callbacks_isolated_across_batches_and_removal(self):
        self.run_scenario('stale-callbacks')

    def test_access_lost_and_pause_active(self):
        self.run_scenario('access-and-pause')

    def run_scenario(self, scenario):
        jdk = Path(os.environ['JAVA_HOME']) / 'bin' if os.environ.get('JAVA_HOME') else None
        javac = shutil.which('javac', path=str(jdk) if jdk else None)
        java = shutil.which('java', path=str(jdk) if jdk else None)
        if not javac or not java:
            self.skipTest('JDK required')
        with tempfile.TemporaryDirectory(prefix='linkpods-watch-') as directory:
            output = Path(directory)
            files = []
            for name, content in STUBS.items():
                target = output / name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text(content, encoding='utf-8')
                files.append(str(target))
            files.append(str(ROOT / 'apps/android/src' / PACKAGE / 'PlaybackWatch.java'))
            compiled = subprocess.run([javac, '-encoding', 'UTF-8', '-d', str(output), *files],
                                      capture_output=True, text=True, timeout=30)
            self.assertEqual(compiled.returncode, 0, compiled.stderr)
            result = subprocess.run([java, '-cp', str(output),
                                     'local.airpods.handoffprobe.PlaybackWatchTest', scenario],
                                    capture_output=True, text=True, timeout=10)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
