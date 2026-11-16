package local.airpods.handoffprobe;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;
import java.io.FileOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/** LinkPods setup, environment status and automatic handoff controls. */
public class MainActivity extends Activity {
    private static final String TAG = "LinkPods";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayList<BluetoothDevice> devices = new ArrayList<>();
    private BluetoothAdapter adapter;
    private BluetoothProfile a2dp;
    private Spinner picker;
    private TextView status;
    private TextView output;
    private boolean receiverRegistered;
    private boolean proxyRequested;
    private boolean destroyed;
    private DeviceConfig savedConfig;
    private final RequestGate environmentRequests = new RequestGate();
    private java.util.concurrent.Future<?> environmentWorker;
    private boolean environmentEnabling;
    private String environmentStatus = "Root 与控制通道尚未检测";
    private boolean nativeBackendReady;
    private String preparedLocalAddress="";

    private DeviceConfig loadDeviceConfig() {
        android.content.SharedPreferences preferences = getSharedPreferences("devices", MODE_PRIVATE);
        java.util.Map<String,String> values = new java.util.HashMap<>();
        for (String key : new String[]{"target", "local", "peer"}) values.put(key, preferences.getString(key, null));
        return DeviceConfig.restore(values);
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            BluetoothDevice target = selected();
            if (target == null || device == null || !target.equals(device)) return;
            int state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1);
            log("A2DP 事件：" + stateName(state));
            refreshStatus();
        }
    };

    private LinkPodsUi ui;
    private boolean visible;
    private String restoredTarget;
    private android.window.OnBackInvokedCallback backCallback;
    private final Runnable controlPoll = new Runnable() {
        @Override public void run() {
            if (!visible || destroyed) return;
            refreshControls();
            handler.postDelayed(this, 1000);
        }
    };

    @Override public void onCreate(Bundle state) {
        boolean dark=(getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)==android.content.res.Configuration.UI_MODE_NIGHT_YES;
        setTheme(dark?android.R.style.Theme_Material_NoActionBar:android.R.style.Theme_Material_Light_NoActionBar);
        super.onCreate(state);
        restoredTarget = state == null ? null : state.getString("selected_device");
        picker = new Spinner(this);
        ui = new LinkPodsUi(this, picker, new LinkPodsUi.Actions() {
            @Override public void openBluetooth() { startActivity(new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)); }
            @Override public void requestNearby() {
                if (!hasPermission()) requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 1);
                else { initialize(); refreshStatus(); }
            }
            @Override public void openMediaAccess() { startActivity(new Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)); }
            @Override public void requestNotifications() {
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2);
                else android.widget.Toast.makeText(MainActivity.this, "服务通知权限已允许", android.widget.Toast.LENGTH_SHORT).show();
            }
            @Override public void refreshDevices() {
                initialize(); refreshStatus();
                BluetoothDevice target=hasPermission()?selected():null;
                boolean requested=target!=null && HandoffService.refreshEarbudState(target.getAddress());
                log(requested?"已请求刷新耳机设置与电量，等待耳机回报。":"控制通道未就绪，无法刷新耳机设置与电量。");
                refreshControls();
            }
            @Override public void enableHandoff() { startAutomatic(); }
            @Override public void stopHandoff() {
                cancelEnvironment(); nativeBackendReady=false;
                stopService(new Intent(MainActivity.this,HandoffService.class));
                log("已请求停止交接，正在确认原生桥接恢复。"); queueBridgeStop(); refreshControls();
            }
            @Override public void checkEnvironment() { prepareEnvironment(false); }
            @Override public boolean setSetting(int id,int value) {
                BluetoothDevice target=selected();
                boolean accepted=target!=null && HandoffService.setEarbudSetting(target.getAddress(),id,value);
                refreshControls();return accepted;
            }
            @Override public String deviceName() { return selectedName(); }
            @Override public String connectionStatus() { return audioConnectionLabel(); }
        }, state == null ? null : state.getString("ui_page"));
        status=ui.environmentStatus; output=ui.logOutput;
        if(Build.VERSION.SDK_INT>=33) {
            backCallback=()->{if(ui==null || !ui.back())finish();};
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,backCallback);
        }
        picker.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent,View view,int position,long id) {
                refreshStatus(); refreshControls();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { refreshControls(); }
        });
        log("系统：" + Build.MODEL + " / Android " + Build.VERSION.RELEASE + " / targetSdk=" + getApplicationInfo().targetSdkVersion);
        initialize(); refreshControls();
    }

    private String selectedName() {
        BluetoothDevice target=selected();
        if(target==null || !hasPermission())return "AirPods";
        try { String name=target.getName();return name==null || name.trim().isEmpty()?"AirPods":name; }
        catch(SecurityException ignored){return "AirPods";}
    }
    private String audioConnectionLabel() {
        if(!hasPermission())return "蓝牙：等待附近设备权限";
        BluetoothDevice target=selected();
        if(target==null)return "蓝牙：请在 LinkPods 设置中选择耳机";
        if(a2dp==null)return "蓝牙：音频状态尚未读取";
        try {
            int state=a2dp.getConnectionState(target);
            if(state==BluetoothProfile.STATE_CONNECTED)return "蓝牙音频已连接";
            if(state==BluetoothProfile.STATE_CONNECTING)return "蓝牙音频正在连接";
            if(state==BluetoothProfile.STATE_DISCONNECTING)return "蓝牙音频正在断开";
            return "蓝牙音频未连接";
        } catch(SecurityException ignored){return "蓝牙：等待附近设备权限";}
    }
    private void refreshControls() {
        if(ui==null)return;
        BluetoothDevice target=hasPermission()?selected():null;
        String address=target==null?"":target.getAddress();
        EarbudSnapshot observed=target==null?EarbudSnapshot.empty(address,"请在应用设置中选择耳机"):HandoffService.earbudSnapshot(address);
        ui.bind(observed,address);
    }
    @Override protected void onResume() {
        super.onResume();visible=true;handler.removeCallbacks(controlPoll);handler.post(controlPoll);
    }
    @Override protected void onPause() {
        visible=false;handler.removeCallbacks(controlPoll);super.onPause();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        if(ui!=null)state.putString("ui_page",ui.page());
        BluetoothDevice target=selected();if(target!=null)state.putString("selected_device",target.getAddress());
        super.onSaveInstanceState(state);
    }
    @Override public void onBackPressed() {if(ui==null || !ui.back())super.onBackPressed();}

    private boolean hasPermission() {
        return Build.VERSION.SDK_INT < 31 || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == 1) {
            if (hasPermission()) initialize(); else log("请允许附近设备权限以连接耳机。");
        }
        refreshStatus();refreshControls();
    }

    private void initialize() {
        if (!hasPermission()) {
            status.setText("请在 LinkPods 设置中允许附近设备权限");
            return;
        }
        try {
            adapter = getSystemService(BluetoothManager.class).getAdapter();
            if (adapter == null || !adapter.isEnabled()) { log("请先在系统设置开启蓝牙。"); return; }
            savedConfig = loadDeviceConfig();
            String previous = selected() == null ? (restoredTarget != null ? restoredTarget : savedConfig == null ? null : savedConfig.target) : selected().getAddress();
            restoredTarget=null;
            devices.clear();
            ArrayList<String> labels = new ArrayList<>();
            int chosen = 0;
            for (BluetoothDevice device : adapter.getBondedDevices()) {
                String name = device.getName() == null ? "未命名设备" : device.getName();
                if ((previous == null && name.toLowerCase(Locale.ROOT).contains("airpod")) || device.getAddress().equals(previous)) chosen = devices.size();
                devices.add(device);
                labels.add(name);
            }
            ArrayAdapter<String> list = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
            list.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            picker.setAdapter(list);
            if (!devices.isEmpty()) picker.setSelection(chosen);
            if (!receiverRegistered) {
                IntentFilter filter = new IntentFilter("android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED");
                if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
                else registerReceiver(receiver, filter);
                receiverRegistered = true;
            }
            if (!proxyRequested) {
                proxyRequested = adapter.getProfileProxy(this, new BluetoothProfile.ServiceListener() {
                    @Override public void onServiceConnected(int profile, BluetoothProfile proxy) {
                        if (destroyed) { adapter.closeProfileProxy(profile, proxy); return; }
                        a2dp = proxy;
                        log("A2DP 服务就绪；这不代表耳机已连接。");
                        refreshStatus();
                    }
                    @Override public void onServiceDisconnected(int profile) {
                        a2dp = null;
                        proxyRequested = false;
                        log("A2DP 服务断开。");
                    }
                }, BluetoothProfile.A2DP);
            }
            log("已读取配对设备；请核对下拉列表中的 AirPods。");
            refreshStatus();
        } catch (Exception error) { failure("初始化", error); }
    }

    private BluetoothDevice selected() {
        if (picker == null) return null;
        int index = picker.getSelectedItemPosition();
        return index >= 0 && index < devices.size() ? devices.get(index) : null;
    }

    private void refreshStatus() {
        if (!hasPermission()) { status.setText("等待附近设备权限"); return; }
        BluetoothDevice target = selected();
        if (target == null || a2dp == null) { status.setText(environmentStatus + "\n" + audioConnectionLabel()); return; }
        try {
            int state = a2dp.getConnectionState(target);
            status.setText(environmentStatus + "\n" + target.getName() + "\nA2DP：" + stateName(state)
                + (state == BluetoothProfile.STATE_CONNECTED ? "\n已建立音频连接，声音请用试听确认。" : ""));
        } catch (Exception error) { failure("查询状态", error); }
    }

    private void startAutomatic() { prepareEnvironment(true,false); }
    private void confirmTemporaryRoot() {
        new android.app.AlertDialog.Builder(this).setTitle("运行临时 Root")
            .setMessage("将先复用已有 Root；没有时，仅在系统精确匹配适配方案后运行漏洞。临时 Root 重启后失效。请选择耳机后继续。")
            .setPositiveButton("继续",(dialog,which)->prepareEnvironment(true,true))
            .setNegativeButton("仅连接耳机",(dialog,which)->startAutomaticPrepared()).show();
    }
    private void prepareEnvironment(boolean enable) { prepareEnvironment(enable,false); }

    private void prepareEnvironment(boolean enable,boolean allowTemporary) {
        if (environmentRequests.pending()) { log("正在检查运行环境，请等待。"); return; }
        if (enable && (!hasPermission() || selected()==null)) { log("请先授权并选择耳机。"); return; }
        if(enable && Build.VERSION.SDK_INT>=27 && !getSystemService(android.app.NotificationManager.class).isNotificationListenerAccessGranted(
            new android.content.ComponentName(this,PlaybackListener.class))) {
            log("请在系统通知访问设置中允许 LinkPods 读取媒体播放状态，返回后再启用。");
            startActivity(new Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));return;
        }
        long request=environmentRequests.begin();
        environmentEnabling=enable;
        final String requestedTarget=selected()==null?null:selected().getAddress();
        log("检测可用 Root；若已安装 Root 管理器，可能出现授权提示。");
        environmentWorker=RootEnvironment.WORK.submit(() -> {
            RootEnvironment.Prepared prepared=RootEnvironment.prepare(this,requestedTarget,enable,allowTemporary);
            RootAccess.Result result=prepared.access;
            String local=prepared.local;
            handler.post(() -> {
                if (destroyed || !environmentRequests.complete(request)) return;
                environmentWorker=null;
                environmentEnabling=false;
                switch (result.status) {
                    case EXISTING_ROOT: environmentStatus="已验证应用可执行 UID 0 命令"; break;
                    case TEMPORARY_ROOT: environmentStatus="已验证临时 Root"; break;
                    case DENIED: environmentStatus="Root 授权未通过，不尝试绕过授权"; break;
                    case UNKNOWN: environmentStatus="Root 检测未完成，可重新检查"; break;
                    case UNSUPPORTED: environmentStatus="未获得 Root；当前系统或此操作没有可用临时 Root 方案"; break;
                    case PREPARATION_FAILED: environmentStatus=prepared.temporaryAttempted
                        ?"Root 准备流程异常，尚未确认 UID 0；请查看运行记录"
                        :"Root 运行环境准备失败，请查看运行记录；未执行临时 Root"; break;
                    default: environmentStatus="临时 Root 流程未完成，尚未确认 UID 0；请查看运行记录";
                }
                log(environmentStatus);
                if(!prepared.diagnostic.isEmpty())log(prepared.diagnostic);
                nativeBackendReady=prepared.nativeReady;
                if(nativeBackendReady)log("原生桥接状态已核对；实际交接仍需试听验收。");
                preparedLocalAddress=local;
                refreshStatus();
                if (enable) {
                    if(selected()==null || !selected().getAddress().equalsIgnoreCase(requestedTarget)) {
                        nativeBackendReady=false;queueBridgeStop();log("设备选择已变化，已请求恢复旧桥接，请重新启用。");return;
                    }
                    if(!allowTemporary && result.status==RootAccess.Status.UNSUPPORTED && prepared.temporarySupported) {confirmTemporaryRoot();return;}
                    if (!result.ready()) log("仅尝试当前系统的控制通道，不启用原生桥接；通道成功不代表双向交接已验证。");
                    startAutomaticPrepared();
                }
            });
        });
    }

    private void cancelEnvironment() {
        environmentRequests.cancel();
        if (environmentWorker!=null) environmentWorker.cancel(true);
        environmentWorker=null;
        environmentEnabling=false;
    }
    private void queueBridgeStop() {
        RootEnvironment.WORK.submit(()->{
            boolean restored=RootEnvironment.stopBridge();
            handler.post(()->{if(!destroyed)log(restored?"原生桥接已停止或当前应用未建立 Root 通道。":"停止请求未能确认原生恢复，请保留现场与日志。");});
        });
    }

    private void startAutomaticPrepared() {
        BluetoothDevice target = selected();
        if (target == null || !hasPermission()) return;
        final DeviceConfig config;
        try {
            String local = preparedLocalAddress.isEmpty() ? null : preparedLocalAddress;
            if (local == null && savedConfig != null) local = savedConfig.local;
            String peer = null;
            if (peer == null && savedConfig != null && savedConfig.target.equalsIgnoreCase(target.getAddress())) peer = savedConfig.peer;
            config = DeviceConfig.create(target.getAddress(), local, peer);
        } catch (IllegalArgumentException error) { log(error.getMessage()); return; }
        android.content.SharedPreferences.Editor editor = getSharedPreferences("devices", MODE_PRIVATE).edit();
        for (java.util.Map.Entry<String,String> entry : config.save().entrySet()) editor.putString(entry.getKey(), entry.getValue());
        editor.apply(); savedConfig = config;
        Intent service = new Intent(this, HandoffService.class)
            .putExtra("address", config.target)
            .putExtra("local_mac", config.local)
            .putExtra("peer_address", config.peer);
        service.putExtra("native_backend_ready",nativeBackendReady);
        startForegroundService(service);
        log("自动交接服务已启动；请按实际出声验证。");
    }

    private void failure(String operation, Exception error) {
        Throwable cause = error instanceof InvocationTargetException ? ((InvocationTargetException) error).getTargetException() : error;
        log(operation + "失败：" + cause.getClass().getSimpleName() + ": " + cause.getMessage());
        Log.e(TAG, operation, cause);
    }

    private void log(String message) {
        String line = new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date()) + " " + message;
        output.append(line + "\n");
        Log.i(TAG, message);
        try (FileOutputStream file = openFileOutput("linkpods.log", MODE_APPEND)) {
            file.write((line + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception error) { Log.e(TAG, "保存运行日志失败", error); }
    }

    private static String stateName(int state) {
        switch (state) {
            case BluetoothProfile.STATE_CONNECTED: return "CONNECTED";
            case BluetoothProfile.STATE_CONNECTING: return "CONNECTING";
            case BluetoothProfile.STATE_DISCONNECTING: return "DISCONNECTING";
            case BluetoothProfile.STATE_DISCONNECTED: return "DISCONNECTED";
            default: return "UNKNOWN(" + state + ")";
        }
    }

    @Override public void onDestroy() {
        destroyed = true;
        if(Build.VERSION.SDK_INT>=33 && backCallback!=null)getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
        boolean pendingEnable=environmentWorker!=null&&environmentEnabling;
        cancelEnvironment();
        if(pendingEnable)queueBridgeStop();
        handler.removeCallbacksAndMessages(null);
        if (receiverRegistered) unregisterReceiver(receiver);
        if (adapter != null && a2dp != null) adapter.closeProfileProxy(BluetoothProfile.A2DP, a2dp);
        super.onDestroy();
    }
}
