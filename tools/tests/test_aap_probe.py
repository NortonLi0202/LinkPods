"""Run the real AapProbe scheduler against host-only Android socket/Handler fakes."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
STUBS = {
    'android/bluetooth/BluetoothDevice.java': '''package android.bluetooth;
import java.io.IOException;
public class BluetoothDevice {
 public BluetoothSocket createInsecureL2capSocket(int psm) throws IOException {throw new IOException("EPROTONOSUPPORT");}
 public BluetoothSocket createL2capSocket(int psm) throws IOException {throw new IOException("Connection refused");}
}''',
    'android/bluetooth/BluetoothSocket.java': '''package android.bluetooth;
import java.io.*;
public class BluetoothSocket {
 public volatile int writes; public volatile boolean closed;
 public java.util.concurrent.CountDownLatch entered,release;
 public boolean isConnected(){return !closed;}
 public void connect(){} public void close(){closed=true;if(release!=null)release.countDown();}
 public InputStream getInputStream(){return new ByteArrayInputStream(new byte[0]);}
 public OutputStream getOutputStream(){return new OutputStream(){
  public void write(int value){throw new AssertionError("unexpected scalar write");}
  public void write(byte[] bytes) throws IOException {
   if(Thread.currentThread().getName().equals("main"))throw new IOException("main-thread Bluetooth write");
   if(entered!=null) {
    entered.countDown();
    try {release.await();}catch(InterruptedException e){throw new IOException(e);}
    if(closed)throw new IOException("closed during blocked write");
   }
   writes++;
  }
 };}
}''',
    'android/os/Looper.java': 'package android.os; public class Looper {public static Looper getMainLooper(){return new Looper();}}',
    'android/os/SystemClock.java': 'package android.os; public class SystemClock {public static long uptimeMillis(){return 0;}}',
    'android/os/Handler.java': '''package android.os;
import java.util.*;
public class Handler {
 public static final List<Runnable> tasks=new ArrayList<>();
 public static final Map<Runnable,Object> tokens=new IdentityHashMap<>();
 public Handler(Looper looper){}
 public boolean postDelayed(Runnable r,long delay){tasks.add(r);tokens.put(r,null);return true;}
 public boolean postAtTime(Runnable r,Object token,long at){tasks.add(r);tokens.put(r,token);return true;}
 public void removeCallbacks(Runnable r){tasks.remove(r);tokens.remove(r);}
 public void removeCallbacksAndMessages(Object token){if(token==null){tasks.clear();tokens.clear();}else{tasks.removeIf(r -> tokens.get(r)==token);tokens.entrySet().removeIf(e -> e.getValue()==token);}}
}''',
    'org/lsposed/hiddenapibypass/HiddenApiBypass.java': '''package org.lsposed.hiddenapibypass;
public class HiddenApiBypass {public static boolean addHiddenApiExemptions(String... values){return true;}}''',
    'local/airpods/handoffprobe/AapSchedulerTest.java': '''package local.airpods.handoffprobe;
import android.bluetooth.*;
import android.os.Handler;
import java.lang.reflect.*;
import java.util.concurrent.*;
public class AapSchedulerTest {
 static void expect(boolean ok,String reason){if(!ok)throw new AssertionError(reason);}
 public static void main(String[] args) throws Exception {
  expect(AapProbe.classifyFailure(new java.io.IOException("EPROTONOSUPPORT"))==AapProbe.Failure.COMPATIBILITY_REQUIRED,"explicit protocol capability failure");
  expect(AapProbe.classifyFailure(new java.lang.reflect.InvocationTargetException(new java.io.IOException("Operation not supported")))==AapProbe.Failure.COMPATIBILITY_REQUIRED,"unwrap socket factory cause");
  for(Exception error:new Exception[]{new java.io.IOException(),new java.io.IOException("Connection refused"),new java.net.SocketTimeoutException("timeout"),new SecurityException("EPROTONOSUPPORT"),new NoSuchMethodException("createL2capSocket")})
   expect(AapProbe.classifyFailure(error)==AapProbe.Failure.OTHER,"reachability, permission and absent API are not evidence for Root");
  CountDownLatch ended=new CountDownLatch(1);
  AapProbe.Failure[] outcome={null};
  AapProbe failedProbe=new AapProbe(new BluetoothDevice(),message -> {},new AapProbe.Listener(){
   public void ready(){} public void packet(byte[] data){} public void ended(){ended.countDown();}
   public void failure(AapProbe.Failure value){outcome[0]=value;}
  });
  failedProbe.start();
  expect(ended.await(3,TimeUnit.SECONDS),"probe completed both socket attempts");
  expect(outcome[0]==AapProbe.Failure.COMPATIBILITY_REQUIRED,"ordinary secure failure preserves insecure compatibility failure");
  Handler handler=new Handler(android.os.Looper.getMainLooper());
  Object firstToken=new Object(),secondToken=new Object();
  Runnable firstTask=() -> {},secondTask=() -> {},unrelated=() -> {};
  handler.postAtTime(firstTask,firstToken,0);
  handler.postAtTime(secondTask,secondToken,0);
  handler.postDelayed(unrelated,0);
  handler.removeCallbacksAndMessages(firstToken);
  expect(!Handler.tasks.contains(firstTask) && Handler.tasks.contains(secondTask) && Handler.tasks.contains(unrelated),"socket cleanup preserves other token and untagged callbacks");
  handler.removeCallbacks(secondTask);
  handler.removeCallbacksAndMessages(null);
  AapProbe probe=new AapProbe(new BluetoothDevice(),message -> {});
  Field socket=AapProbe.class.getDeclaredField("socket");socket.setAccessible(true);
  Field writer=AapProbe.class.getDeclaredField("writer");writer.setAccessible(true);
  ExecutorService executor=(ExecutorService)writer.get(probe);
  BluetoothSocket first=new BluetoothSocket(),second=new BluetoothSocket();
  socket.set(probe,first);
  try {
   probe.requestState();
   expect(Handler.tasks.size()==1,"refresh scheduled");
   Handler.tasks.remove(0).run();
   executor.submit(() -> {}).get(3,TimeUnit.SECONDS);
   expect(first.writes==1,"scheduled refresh must write on background thread");
   CountDownLatch sent=new CountDownLatch(1);
   probe.sendPacketAsync(AapPackets.notifications(),ok -> {expect(ok,"async setting sent");sent.countDown();});
   expect(sent.await(3,TimeUnit.SECONDS),"setting completion delivered");
   probe.requestState();Runnable stale=Handler.tasks.remove(0);
   socket.set(probe,second);stale.run();
   executor.submit(() -> {}).get(3,TimeUnit.SECONDS);
   expect(first.writes==2 && second.writes==0,"old socket task must not write either socket");
   second.entered=new CountDownLatch(1);second.release=new CountDownLatch(1);
   probe.sendPacketAsync(AapPackets.notifications(),ok -> {});
   expect(second.entered.await(3,TimeUnit.SECONDS),"background write held for contention test");
   probe.requestState();Handler.tasks.remove(0).run();
   // Queuing a second write and stopping must work while the first write is blocked.
   probe.requestState();Runnable stopped=Handler.tasks.get(0);
   probe.stop();
   expect(Handler.tasks.isEmpty(),"stop removes delayed callbacks");
   stopped.run();
   expect(second.writes==0,"even already-dispatched callback cannot write after stop");
   expect(executor.isShutdown(),"stop releases writer thread");
  } finally {probe.stop();}
 }
}''',
}


class AapSchedulerTests(unittest.TestCase):
    def test_background_write_socket_replacement_and_stop(self):
        jdk = Path(os.environ['JAVA_HOME']) / 'bin' if os.environ.get('JAVA_HOME') else None
        javac = str(jdk / 'javac.exe') if jdk else shutil.which('javac')
        java = str(jdk / 'java.exe') if jdk else shutil.which('java')
        if not javac or not java:
            self.skipTest('JDK required')
        with tempfile.TemporaryDirectory(prefix='linkpods-aap-scheduler-') as directory:
            root = Path(directory)
            files = []
            for name, text in STUBS.items():
                file = root / name
                file.parent.mkdir(parents=True, exist_ok=True)
                file.write_text(text, encoding='utf-8')
                files.append(str(file))
            package = ROOT / 'apps/android/src/local/airpods/handoffprobe'
            files += [str(package / name) for name in ('AapProbe.java', 'AapPackets.java')]
            compile_result = subprocess.run([javac, '-encoding', 'UTF-8', '-d', str(root), *files], capture_output=True, text=True, timeout=30)
            self.assertEqual(compile_result.returncode, 0, compile_result.stderr)
            result = subprocess.run([java, '-cp', str(root), 'local.airpods.handoffprobe.AapSchedulerTest'], capture_output=True, text=True, timeout=10)
            self.assertEqual(result.returncode, 0, result.stderr)
