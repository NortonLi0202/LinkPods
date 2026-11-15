package local.airpods.handoffprobe;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.os.Looper;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Locale;
import org.lsposed.hiddenapibypass.HiddenApiBypass;

/** Diagnostic session only: no ownership writes, key requests, or persistent settings. */
final class AapProbe {
    interface Logger { void write(String message); }
    interface Listener { void ready(); void packet(byte[] data); void ended(); }
    private final BluetoothDevice device;
    private final Logger logger;
    private final Handler timer = new Handler(Looper.getMainLooper());
    private volatile BluetoothSocket socket;
    private volatile boolean running;
    private volatile boolean stopped;
    private Listener listener;

    AapProbe(BluetoothDevice device, Logger logger) { this.device = device; this.logger = logger; }
    AapProbe(BluetoothDevice device, Logger logger, Listener listener) {
        this(device, logger); this.listener = listener;
    }
    synchronized boolean sendPacket(byte[] packet) {
        try {
            if (socket == null || !socket.isConnected()) return false;
            socket.getOutputStream().write(packet);
            socket.getOutputStream().flush();
            return true;
        } catch (Exception error) { logger.write("AAP_SEND_FAILED " + describe(error)); return false; }
    }
    boolean isRunning() { return running; }

    void start() {
        running = true;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    logger.write(listener == null
                        ? "AAP_PROBE_START：只尝试控制通道与标准会话订阅，不写入音频所有权。"
                        : "AAP_SESSION_START：自动交接服务控制通道。");
                    try {
                        boolean accessible = HiddenApiBypass.addHiddenApiExemptions("Landroid/bluetooth/BluetoothDevice;");
                        logger.write("AAP_APP_LOCAL_API_ACCESS=" + accessible + "（仅测试应用进程）");
                    } catch (LinkageError error) {
                        logger.write("AAP_APP_LOCAL_API_FAILED：" + error.getClass().getSimpleName() + ": " + error.getMessage());
                        return;
                    }
                    boolean received = attempt(false);
                    if (!received && !stopped) attempt(true);
                } finally {
                    running = false;
                    logger.write("AAP_PROBE_END：通道结果不等于原生自动切换已实现。");
                    if (listener != null) listener.ended();
                }
            }
        }, "AAPProtocolProbe").start();
    }

    void stop() {
        stopped = true;
        close(socket);
    }

    private boolean attempt(boolean secure) {
        final String mode = secure ? "secure" : "insecure";
        final BluetoothSocket current;
        try {
            String methodName = secure ? "createL2capSocket" : "createInsecureL2capSocket";
            Method factory = BluetoothDevice.class.getDeclaredMethod(methodName, int.class);
            logger.write("AAP_FACTORY_FOUND：" + methodName);
            current = (BluetoothSocket) factory.invoke(device, 0x1001);
        } catch (Exception error) {
            logger.write("AAP_FACTORY_FAILED " + mode + "：" + describe(error));
            return false;
        }
        socket = current;
        final Runnable deadline = new Runnable() {
            @Override public void run() {
                logger.write("AAP_SESSION_DEADLINE " + mode + "：探测窗口结束。");
                close(current);
            }
        };
        timer.postDelayed(deadline, 20000);
        int packets = 0;
        boolean handshakeResponse = false;
        try {
            logger.write("AAP_CONNECT_BEGIN " + mode + " PSM=0x1001");
            current.connect();
            logger.write("AAP_SOCKET_CONNECTED " + mode);
            // Same standard handshake and session-notification subscriptions as PodTide.
            current.getOutputStream().write(new byte[]{0,0,4,0,1,0,2,0,0,0,0,0,0,0,0,0});
            current.getOutputStream().write(new byte[]{4,0,4,0,15,0,(byte)255,(byte)255,(byte)239,(byte)255});
            current.getOutputStream().write(new byte[]{4,0,4,0,15,0,(byte)255,(byte)255,(byte)255,(byte)255});
            current.getOutputStream().write(new byte[]{4,0,4,0,77,0,(byte)215,0,0,0,0,0,0,0});
            current.getOutputStream().flush();
            logger.write("AAP_HANDSHAKE_SENT：等待耳机回应。");
            byte[] buffer = new byte[65535];
            InputStream input = current.getInputStream();
            while (!stopped) {
                int length = input.read(buffer);
                if (length < 0) break;
                if (length < 4) continue;
                packets++;
                int type = le16(buffer, 0);
                if (type == 1) {
                    if (!handshakeResponse) {
                        timer.removeCallbacks(deadline);
                        if (listener == null) timer.postDelayed(deadline, 90000);
                        else {
                            listener.ready();
                            timer.postDelayed(() -> {
                                sendPacket(new byte[]{4,0,4,0,77,0,(byte)215,0,0,0,0,0,0,0});
                                sendPacket(new byte[]{4,0,4,0,15,0,(byte)255,(byte)255,(byte)255,(byte)255});
                                logger.write("AAP_POST_HANDSHAKE_SUBSCRIPTIONS");
                            }, 300);
                        }
                    }
                    handshakeResponse = true;
                    logger.write("AAP_CONNECT_RESPONSE len=" + length);
                } else if (type == 4 && length >= 6) {
                    int command = le16(buffer, 4);
                    if (command == 0x1d) {
                        String fields = new String(buffer,6,length-6,java.nio.charset.StandardCharsets.ISO_8859_1);
                        java.util.Set<String> versions = new java.util.LinkedHashSet<>();
                        for (String field : fields.split("\u0000")) {
                            if (field.matches("[0-9]{1,2}[A-Z][0-9]{2,6}[a-z]?")) versions.add(field);
                            if (field.matches("A[0-9]{4}")) logger.write("AAP_MODEL " + field);
                        }
                        logger.write("AAP_FIRMWARE_CANDIDATES " + versions);
                        String[] parts=fields.split("\u0000");
                        for (int i=0;i+3<parts.length;i++) {
                            if (parts[i].equals("Apple Inc.")) {
                                logger.write("AAP_FIRMWARE version1=" + parts[i+2] + " version2=" + parts[i+3]);
                                break;
                            }
                        }
                    }
                    if (packets <= 15 || command == 0x10 || command == 0x11 || command == 0x2e || command == 0x0e) {
                        logger.write(String.format(Locale.ROOT, "AAP_MESSAGE opcode=0x%04X len=%d", command, length));
                    }
                    if (command == 9 && length >= 8 && (buffer[6] & 255) == 6) {
                        logger.write("AAP_OWNERSHIP_EVENT value=" + (buffer[7] & 255));
                    }
                    if (command == 0x11 || command == 0x2e) {
                        StringBuilder hex = new StringBuilder(length * 2);
                        for (int i = 0; i < length; i++) hex.append(String.format(Locale.ROOT, "%02x", buffer[i] & 255));
                        logger.write((command == 0x11 ? "AAP_ROUTING_WIRE " : "AAP_HOSTS_WIRE ") + hex);
                        String content = new String(buffer, 6, length - 6, java.nio.charset.StandardCharsets.ISO_8859_1);
                        if (content.contains("SetOwnershipToFalse")) logger.write("AAP_ROUTING_EVENT：收到 SetOwnershipToFalse 交接请求。");
                    }
                } else if (packets <= 15) logger.write("AAP_PACKET type=" + type + " len=" + length);
                if (listener != null) listener.packet(java.util.Arrays.copyOf(buffer, length));
            }
        } catch (Exception error) {
            logger.write("AAP_SESSION_RESULT " + mode + "：" + describe(error));
        } finally {
            timer.removeCallbacks(deadline);
            close(current);
            if (socket == current) socket = null;
            logger.write("AAP_SUMMARY " + mode + " packets=" + packets + " handshakeResponse=" + handshakeResponse);
        }
        return handshakeResponse;
    }

    private static int le16(byte[] bytes, int offset) { return (bytes[offset] & 255) | ((bytes[offset + 1] & 255) << 8); }
    private static void close(BluetoothSocket socket) {
        if (socket != null) try { socket.close(); } catch (Exception ignored) { }
    }
    private static String describe(Exception error) {
        Throwable cause = error instanceof InvocationTargetException ? ((InvocationTargetException) error).getTargetException() : error;
        return cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }
}
