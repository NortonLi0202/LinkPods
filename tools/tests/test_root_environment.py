"""Exercise bridge cleanup against the real RootEnvironment with scripted host replies."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
STUBS = {
    'android/content/Context.java': '''package android.content;
public class Context {
 public String getPackageName(){return "lp.pods";}
 public android.content.pm.ApplicationInfo getApplicationInfo(){return new android.content.pm.ApplicationInfo();}
}''',
    'android/content/pm/ApplicationInfo.java': '''package android.content.pm;
public class ApplicationInfo {public String nativeLibraryDir=".";}''',
    'org/json/JSONObject.java': '''package org.json;
public class JSONObject {
 private final String[] fields; private final String section;
 public JSONObject(String value){fields=value.split("\\\\|",-1);section="";}
 private JSONObject(String[] fields,String section){this.fields=fields;this.section=section;}
 public JSONObject getJSONObject(String key){return new JSONObject(fields,key);}
 public String getString(String key){
  if(key.equals("instance_id"))return fields[0];
  if(key.equals("target_address"))return fields[1];
  if(key.equals("profile_sha256"))return fields[2];
  if(key.equals("state"))return fields[3];
  throw new IllegalArgumentException(key);
 }
 public String optString(String key,String fallback){return getString(key);}
 public boolean optBoolean(String key,boolean fallback){
  if(key.equals("di_identity"))return true;
  return fallback;
 }
 public int getInt(String key){
  if(key.equals("pid"))return Integer.parseInt(fields[5]);
  if(key.equals("active_sdp"))return Integer.parseInt(fields[4]);
  if(key.equals("vendor"))return 0x4c;
  if(key.equals("source"))return 1;
  throw new IllegalArgumentException(key);
 }
 public long getLong(String key){return System.currentTimeMillis();}
}''',
    'local/airpods/handoffprobe/Stubs.java': '''package local.airpods.handoffprobe;
import android.content.Context;
import java.io.*;
import java.util.*;
class RootSessionConfig {static final String PACKAGE="lp.pods";}
class DeviceConfig {static String address(String value,boolean unused){return value;}}
class BridgeAdapterRegistry {static class Adapter {String id="adapter",profileHash="profile";}}
class RootAssets {
 static String stagedInstance="";
 RootAssets(Context c){}
 BridgeAdapterRegistry.Adapter matchBridge(){return new BridgeAdapterRegistry.Adapter();}
 void stageBridge(String target,BridgeAdapterRegistry.Adapter adapter){stagedInstance="";}
 void stageBridge(String target,BridgeAdapterRegistry.Adapter adapter,String instance){stagedInstance=instance;}
}
class RootProtocol {enum Command {ID,LOCAL_ADDRESS,BRIDGE_PREPARE,BRIDGE_STATUS,BRIDGE_STOP}}
class RootHost {
 static final RootHost INSTANCE=new RootHost();
 volatile boolean prepareThrows,statusThrows,disabled,reuse,statusUntilPrepare; volatile int stops,prepares,statusFailures; String otherInstance="",activeInstance="";
 final List<String> events=new ArrayList<>();
 static RootHost get(Context context){return INSTANCE;} static RootHost current(){return INSTANCE;}
 boolean await(long timeout) throws InterruptedException{return true;}
 String call(RootProtocol.Command command) throws IOException {
  switch(command){
   case ID:return "0";
   case LOCAL_ADDRESS:return "AA:BB:CC:DD:EE:FF";
   case BRIDGE_PREPARE:
    events.add("prepare");prepares++;
    if(!reuse){activeInstance=RootAssets.stagedInstance;disabled=false;}
    if(prepareThrows){prepareThrows=false;throw new IOException("response lost");}
    if(statusUntilPrepare){statusThrows=false;statusUntilPrepare=false;}
    if(reuse)return "LINKPODS_BRIDGE_REUSED";
    return "LINKPODS_BRIDGE_STARTED";
   case BRIDGE_STOP:events.add("stop");stops++;disabled=true;return "";
   case BRIDGE_STATUS:
    if(statusFailures>0){statusFailures--;throw new IOException("status unavailable");}
    if(statusThrows)throw new IOException("status unavailable");
    return (otherInstance.isEmpty()?activeInstance:otherInstance)+"|AA:BB:CC:DD:EE:FF|profile|"+(disabled?"disabled|0":"enabled|1")+"|42";
   default:throw new IOException();
  }
 }
}
class SuRootProvider implements RootAccess.Provider {
 public RootAccess.Probe probe(){return RootAccess.Probe.GRANTED;} public boolean supported(){return true;}
 public boolean acquire(){return false;} boolean startBroker(File file){return true;} String localBluetoothAddress(){return "";}
}
class StandaloneRootProvider implements RootAccess.Provider {
 StandaloneRootProvider(Context c,RootHost h,RootAssets a){} public RootAccess.Probe probe(){return RootAccess.Probe.GRANTED;}
 public boolean supported(){return false;} public boolean acquire(){return false;} String diagnostic(){return "";}
}''',
    'local/airpods/handoffprobe/RootEnvironmentHostTest.java': '''package local.airpods.handoffprobe;
import android.content.Context;
public class RootEnvironmentHostTest {
 static final String TARGET="AA:BB:CC:DD:EE:FF";
 static void expect(boolean ok,String message){if(!ok)throw new AssertionError(message);}
 static void run(boolean lostPrepare) {
  RootHost host=RootHost.INSTANCE;host.disabled=false;host.stops=0;host.otherInstance="";
  host.prepareThrows=lostPrepare;host.statusThrows=true;
  RootEnvironment.Prepared prepared=RootEnvironment.prepare(new Context(),TARGET,true,false);
  expect(!RootAssets.stagedInstance.isEmpty(),"instance token staged before bridge prepare");
  expect(prepared.bridgeOwned,"ambiguous preparation retains cleanup ownership");
  host.statusThrows=false;
  host.otherInstance="another-instance";
  expect(!RootEnvironment.stopOwnedBridge(prepared),"different runtime must not be adopted");
  expect(host.stops==0,"different runtime must not be stopped");
  host.otherInstance="";
  expect(RootEnvironment.stopOwnedBridge(prepared),"same runtime can be recovered and stopped");
  expect(host.stops==1 && !prepared.bridgeOwned,"cleanup confirms disabled state");
 }
 static void immediateCleanupSucceedsWhenStatusAvailable(){
  RootHost host=RootHost.INSTANCE;host.disabled=false;host.otherInstance="";host.stops=0;
  host.prepareThrows=true;host.statusThrows=false;
  RootEnvironment.Prepared prepared=RootEnvironment.prepare(new Context(),TARGET,true,false);
  expect(host.stops==1 && !prepared.bridgeOwned,"lost prepare response is cleaned immediately when status is available");
 }
 static void pendingCleanupRunsBeforeNextPrepare(){
  RootHost host=RootHost.INSTANCE;host.disabled=false;host.otherInstance="";host.events.clear();
  host.prepareThrows=true;host.statusThrows=true;
  RootEnvironment.Prepared first=RootEnvironment.prepare(new Context(),TARGET,true,false);
  expect(first.bridgeOwned,"failed cleanup must keep ownership for retry");
  host.statusThrows=false;
  RootEnvironment.Prepared next=RootEnvironment.prepare(new Context(),TARGET,true,false);
  int stop=host.events.indexOf("stop"), second=host.events.lastIndexOf("prepare");
  expect(stop>=0 && second>stop,"pending cleanup must stop old runtime before next prepare");
  expect(next.nativeReady,"new bridge is ready after prior cleanup");
  if(next.bridgeOwned)RootEnvironment.stopOwnedBridge(next);
 }
 static void successfulBridgeIsNotPendingCleanup(){
  RootHost host=RootHost.INSTANCE;host.disabled=false;host.otherInstance="";host.stops=0;host.reuse=false;
  RootEnvironment.Prepared first=RootEnvironment.prepare(new Context(),TARGET,true,false);
  expect(first.nativeReady && first.bridgeOwned,"first bridge is running and owned");
  host.reuse=true;
  RootEnvironment.Prepared second=RootEnvironment.prepare(new Context(),TARGET,true,false);
  expect(host.stops==0,"later prepare must not stop a successful active bridge");
  expect(second.nativeReady,"existing bridge can be reused");
  host.reuse=false;RootEnvironment.stopOwnedBridge(first);
 }
 static void unknownPendingDoesNotBlockLaterPrepare(){
  RootHost host=RootHost.INSTANCE;host.disabled=false;host.otherInstance="";host.stops=0;host.reuse=false;
  host.prepareThrows=true;host.statusThrows=true;
  RootEnvironment.Prepared first=RootEnvironment.prepare(new Context(),TARGET,true,false);
  expect(first.bridgeOwned,"unknown candidate is retained for later cleanup");
  int previous=host.prepares;host.statusUntilPrepare=true;
  RootEnvironment.Prepared next=RootEnvironment.prepare(new Context(),TARGET,true,false);
  expect(host.prepares==previous+1,"unknown pending candidate must not prevent another prepare");
  expect(host.stops==0,"unknown candidate must not cause a stop");
  expect(next.nativeReady,"new preparation succeeds once runtime starts");
  if(next.bridgeOwned)RootEnvironment.stopOwnedBridge(next);
 }
 static void stoppingPendingBridgeCannotBeReused(){
  RootHost host=RootHost.INSTANCE;host.disabled=false;host.otherInstance="";host.stops=0;host.reuse=false;
  host.prepareThrows=true;host.statusThrows=true;
  RootEnvironment.Prepared first=RootEnvironment.prepare(new Context(),TARGET,true,false);
  expect(first.bridgeOwned,"uncertain first launch retains ownership");
  host.activeInstance=RootAssets.stagedInstance;host.reuse=true;host.statusThrows=false;host.statusFailures=1;
  RootEnvironment.Prepared second=RootEnvironment.prepare(new Context(),TARGET,true,false);
  expect(!second.nativeReady,"bridge pending cleanup cannot be handed off as ready");
  expect(first.bridgeOwned,"cleanup claim remains with original preparation");
 }
 static void awaitStop(RootHost host) throws Exception {
  long deadline=System.nanoTime()+3_000_000_000L;
  while(host.stops==0 && System.nanoTime()<deadline)Thread.sleep(20);
  expect(host.stops>0,"failed cleanup must retry without another prepare");
 }
 static void failedCleanupRetries() throws Exception {
  RootHost host=RootHost.INSTANCE;
  RootEnvironment.Prepared prepared=RootEnvironment.prepare(new Context(),TARGET,true,false);
  expect(prepared.nativeReady && prepared.bridgeOwned,"bridge must start owned");
  host.statusThrows=true;
  expect(!RootEnvironment.stopOwnedBridge(prepared),"unavailable status must defer cleanup");
  host.statusThrows=false;
  awaitStop(host);
 }
 static void channelReconnectRetries() throws Exception {
  RootHost host=RootHost.INSTANCE;
  RootEnvironment.Prepared prepared=RootEnvironment.prepare(new Context(),TARGET,true,false);
  host.statusThrows=true;
  expect(!RootEnvironment.stopOwnedBridge(prepared),"unavailable status must defer cleanup");
  host.statusThrows=false;
  RootEnvironment.rootChannelConnected();
  awaitStop(host);
 }
 static void stoppingInvalidatesAliases() {
  RootHost host=RootHost.INSTANCE;
  RootEnvironment.Prepared first=RootEnvironment.prepare(new Context(),TARGET,true,false);
  host.reuse=true;
  RootEnvironment.Prepared second=RootEnvironment.prepare(new Context(),TARGET,true,false);
  expect(second.nativeReady && !second.bridgeOwned && first.bridgeOwned,"preparation leaves ownership with current owner");
  expect(RootEnvironment.acceptBridge(second),"receiver accepts reused bridge");
  expect(second.bridgeOwned && !first.bridgeOwned,"acceptance transfers active ownership");
  RootEnvironment.stopOwnedBridge(first);
  expect(host.stops==0 && second.bridgeOwned && RootEnvironment.bridgeUsable(second),"displaced cleanup must leave accepted owner running");
  expect(!RootEnvironment.bridgeUsable(first) && RootEnvironment.bridgeUsable(second),"only the transferred result remains usable");
  RootEnvironment.markBridgeStopping(second);
  expect(!RootEnvironment.bridgeUsable(first) && !RootEnvironment.bridgeUsable(second),"stop invalidates all aliases before I/O");
  expect(host.stops==0,"marking stop does not perform host I/O");
 }
 static void cancelledReuseLeavesOwnerRunning() {
  RootHost host=RootHost.INSTANCE;
  RootEnvironment.Prepared first=RootEnvironment.prepare(new Context(),TARGET,true,false);
  host.reuse=true;
  RootEnvironment.Prepared second=RootEnvironment.prepare(new Context(),TARGET,true,false);
  expect(second.nativeReady,"reuse preparation is ready");
  RootEnvironment.stopOwnedBridge(second);
  expect(host.stops==0 && first.bridgeOwned && RootEnvironment.bridgeUsable(first),"cancelled reuse must leave original owner running");
 }
 public static void main(String[] args) throws Exception {
  switch(args[0]) {
   case "lost_prepare":run(true);break;
   case "lost_status":run(false);break;
   case "immediate_cleanup":immediateCleanupSucceedsWhenStatusAvailable();break;
   case "pending_cleanup":pendingCleanupRunsBeforeNextPrepare();break;
   case "unknown_pending":unknownPendingDoesNotBlockLaterPrepare();break;
   case "successful_bridge":successfulBridgeIsNotPendingCleanup();break;
   case "stopping_pending":stoppingPendingBridgeCannotBeReused();break;
   case "failed_cleanup_retries":failedCleanupRetries();break;
   case "channel_reconnect":channelReconnectRetries();break;
   case "stopping_aliases":stoppingInvalidatesAliases();break;
   case "cancelled_reuse":cancelledReuseLeavesOwnerRunning();break;
   default:throw new IllegalArgumentException(args[0]);
  }
 }
}''',
}


class RootEnvironmentTests(unittest.TestCase):
    def test_ambiguous_prepare_recovers_only_matching_runtime(self):
        jdk = Path(os.environ['JAVA_HOME']) / 'bin' if os.environ.get('JAVA_HOME') else None
        javac = shutil.which('javac', path=str(jdk) if jdk else None)
        java = shutil.which('java', path=str(jdk) if jdk else None)
        if not javac or not java:
            self.skipTest('JDK required')
        with tempfile.TemporaryDirectory(prefix='linkpods-root-environment-') as directory:
            root = Path(directory)
            files = []
            for name, content in STUBS.items():
                file = root / name
                file.parent.mkdir(parents=True, exist_ok=True)
                file.write_text(content, encoding='utf-8')
                files.append(str(file))
            package = ROOT / 'apps/android/src/local/airpods/handoffprobe'
            files += [str(package / name) for name in ('RootEnvironment.java', 'BridgeRuntimeLease.java', 'RootAccess.java', 'RootWorkQueue.java')]
            compiled = subprocess.run([javac, '-encoding', 'UTF-8', '-d', str(root), *files], capture_output=True, text=True, timeout=30)
            self.assertEqual(compiled.returncode, 0, compiled.stderr)
            for scenario in ('lost_prepare', 'lost_status', 'immediate_cleanup', 'pending_cleanup',
                             'unknown_pending', 'successful_bridge', 'stopping_pending',
                             'failed_cleanup_retries', 'channel_reconnect', 'stopping_aliases', 'cancelled_reuse'):
                with self.subTest(scenario=scenario):
                    result = subprocess.run([java, '-cp', str(root), 'local.airpods.handoffprobe.RootEnvironmentHostTest', scenario], capture_output=True, text=True, timeout=10)
                    self.assertEqual(result.returncode, 0, result.stderr)
