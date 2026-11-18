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
 private final String pkg;
 private int playbackState;
 private final MediaSession.Token token=new MediaSession.Token();
 public MediaController(String pkg,int state){this.pkg=pkg;this.playbackState=state;}
 public String getPackageName(){return pkg;}
 public MediaSession.Token getSessionToken(){return token;}
 public PlaybackState getPlaybackState(){return new PlaybackState(playbackState);}
 public void setPlaybackState(int state){playbackState=state;}
 public void registerCallback(Callback c,android.os.Handler h){callbacks.add(c);lastCallback=c;registered++;}
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
 public boolean rejectRegistration;
 public int added,removed;
 public OnActiveSessionsChangedListener lastListener;
 public final List<OnActiveSessionsChangedListener> listeners=new ArrayList<>();
 public List<MediaController> getActiveSessions(android.content.ComponentName c){return new ArrayList<>(active);}
 public void addOnActiveSessionsChangedListener(OnActiveSessionsChangedListener l,android.content.ComponentName c,android.os.Handler h){
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
    'android/os/Handler.java':
        'package android.os; public class Handler {public Handler(Looper l){}}',
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
  @Override public void onPlayersSeeded(Map<String,Integer> states,boolean restarted){
   seeded.add(new LinkedHashMap<>(states));
   seededRestartFlags.add(restarted);
  }
  @Override public void onPlayerStateChanged(String playerId,String packageName,int state){
   stateChanges.add(playerId+":"+packageName+":"+state);
  }
  @Override public void onPlayerRemoved(String playerId){removedPlayers.add(playerId);}
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
  expect(controller.registered==1,"seed registers player callback before listener failure");
  expect(controller.callbacks.isEmpty(),"failed start rolls back player callbacks");
  expect(manager.removed==0,"failed listener registration does not call remove");
  expect(events.seeded.isEmpty()&&events.stopped.isEmpty(),"failed start emits no seeded or stopped events");

  manager.rejectRegistration=false;
  expect(watch.start(),"retry start succeeds");
  expect(watch.isWatching()&&watch.isActive(),"successful start activates watch");
  expect(controller.callbacks.size()==1,"retry registers one fresh player callback");
  expect(events.seeded.size()==1&&!events.seededRestartFlags.get(0),"initial start emits seeded without restart flag");

  watch.stop();
  watch.stop();
  expect(!watch.isWatching()&&!watch.isActive(),"stop clears watch state");
  expect(controller.callbacks.isEmpty()&&manager.removed==1,"stop unregisters player and manager listener once");
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

  // Revocation via empty session list triggers accessLost=true.
  nm.notificationAccessGranted=false;
  manager.active.clear();
  manager.lastListener.onActiveSessionsChanged(Collections.emptyList());
  expect(!watch.isWatching()&&!watch.isActive(),"empty list without access stops watch");
  expect(events.stopped.size()==1&&events.stopped.get(0),"empty list without access emits onWatchStopped(true)");

  // Re-grant and lose via onAccessLost() directly.
  nm.notificationAccessGranted=true;
  expect(watch.restart(),"re-grant restart succeeds");
  nm.notificationAccessGranted=false;
  watch.onAccessLost();
  expect(events.stopped.size()==2&&events.stopped.get(1),"onAccessLost emits onWatchStopped(true)");
  expect(logs.stream().anyMatch(line->line.equals("MEDIA_SESSION_WATCH access-lost")),"onAccessLost logs access-lost");

  // Restart when access is still revoked while watching tears down with accessLost=true.
  nm.notificationAccessGranted=true;
  expect(watch.start(),"start succeeds again");
  nm.notificationAccessGranted=false;
  expect(!watch.restart(),"restart fails when access revoked");
  expect(events.stopped.size()==3&&events.stopped.get(2),"failed restart after revoke emits onWatchStopped(true)");
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
