package org.einkwiki.app.transfer;

import android.content.Context;
import android.os.SystemClock;

import org.einkwiki.app.library.ZimLibraryStore;
import org.einkwiki.app.reader.ZimArchive;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** A deliberately small, one-upload-at-a-time HTTP server for browser-based LAN imports. */
public final class LanImportServer implements Closeable {
    public enum State {
        WAITING,
        RECEIVING,
        VERIFYING,
        COMPLETE,
        ERROR
    }

    public static final class Snapshot {
        public final State state;
        public final String fileName;
        public final long receivedBytes;
        public final long totalBytes;
        public final long bytesPerSecond;
        public final String message;

        Snapshot(
                State state,
                String fileName,
                long receivedBytes,
                long totalBytes,
                long bytesPerSecond,
                String message
        ) {
            this.state = state;
            this.fileName = fileName == null ? "" : fileName;
            this.receivedBytes = Math.max(0L, receivedBytes);
            this.totalBytes = Math.max(0L, totalBytes);
            this.bytesPerSecond = bytesPerSecond;
            this.message = message == null ? "" : message;
        }

        public int percent() {
            if (totalBytes <= 0L) {
                return 0;
            }
            return (int) Math.min(100L, receivedBytes * 100L / totalBytes);
        }
    }

    public interface Listener {
        void onSnapshot(Snapshot snapshot);

        void onImported(File file);
    }

    private static final int DEFAULT_PORT = 8765;
    private static final int MAX_HEADER_BYTES = 16 * 1024;
    private static final long MAX_CHUNK_BYTES = 8L * 1024L * 1024L;
    private static final int SOCKET_TIMEOUT_MS = 30_000;

    private final Context context;
    private final ZimLibraryStore store;
    private final Listener listener;
    private final Object stateLock = new Object();

    private volatile boolean closed;
    private volatile Socket activeSocket;
    private ServerSocket serverSocket;
    private Thread serverThread;
    private byte[] pageBytes;
    private Snapshot snapshot = new Snapshot(State.WAITING, "", 0L, 0L, 0L, "等待电脑连接");
    private String activeFileName = "";
    private long activeTotalBytes;
    private long speedSampleBytes;
    private long speedSampleTimeMs;

    public LanImportServer(Context context, ZimLibraryStore store, Listener listener) {
        this.context = context.getApplicationContext();
        this.store = store;
        this.listener = listener;
    }

    public List<String> start() throws IOException {
        if (serverThread != null) {
            throw new IOException("局域网导入服务已经启动");
        }
        pageBytes = readAsset("lan-import.html", 256 * 1024);
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(DEFAULT_PORT));
        List<String> urls = localUrls(serverSocket.getLocalPort());
        if (urls.isEmpty()) {
            serverSocket.close();
            serverSocket = null;
            throw new IOException("没有找到可用的局域网 IPv4 地址");
        }
        serverThread = new Thread(this::serve, "einkwiki-lan-import");
        serverThread.setPriority(Thread.NORM_PRIORITY - 1);
        serverThread.start();
        publish(new Snapshot(State.WAITING, "", 0L, 0L, 0L, "等待电脑连接"));
        return urls;
    }

    public Snapshot snapshot() {
        synchronized (stateLock) {
            return snapshot;
        }
    }

    public void cancelAndDeletePartial() throws IOException {
        String fileName;
        synchronized (stateLock) {
            fileName = activeFileName;
        }
        close();
        if (!fileName.isEmpty()) {
            store.deletePartial(fileName);
        }
    }

    private void serve() {
        while (!closed) {
            try {
                Socket socket = serverSocket.accept();
                activeSocket = socket;
                socket.setSoTimeout(SOCKET_TIMEOUT_MS);
                handle(socket);
            } catch (IOException error) {
                if (!closed) {
                    publish(errorSnapshot(readable(error)));
                }
            } finally {
                Socket socket = activeSocket;
                activeSocket = null;
                if (socket != null) {
                    try {
                        socket.close();
                    } catch (IOException ignored) {
                        // Closing a completed request is best effort.
                    }
                }
            }
        }
    }

    private void handle(Socket socket) throws IOException {
        BufferedInputStream input = new BufferedInputStream(socket.getInputStream(), 64 * 1024);
        BufferedOutputStream output = new BufferedOutputStream(socket.getOutputStream(), 32 * 1024);
        Request request = readRequest(input);
        if ("GET".equals(request.method) && "/".equals(request.path)) {
            respond(output, 200, "OK", "text/html; charset=utf-8", pageBytes);
            return;
        }
        if ("GET".equals(request.method) && "/favicon.ico".equals(request.path)) {
            respond(output, 204, "No Content", "image/x-icon", new byte[0]);
            return;
        }
        if ("GET".equals(request.method) && "/api/status".equals(request.path)) {
            Snapshot current = prepare(request.query, true);
            respondJson(output, 200, current);
            return;
        }
        if ("PUT".equals(request.method) && "/api/upload".equals(request.path)) {
            Snapshot current = receiveChunk(request, input);
            respondJson(output, 200, current);
            return;
        }
        respondText(output, 404, "Not Found", "没有这个地址");
    }

    private Snapshot prepare(Map<String, String> query, boolean announce) throws IOException {
        String name = ZimLibraryStore.validateFileName(query.get("name"));
        long total = positiveLong(query.get("size"), "文件大小无效");
        synchronized (stateLock) {
            if (!activeFileName.isEmpty()
                    && (!activeFileName.equals(name) || activeTotalBytes != total)) {
                throw new IOException("App 当前正在接收另一个文件");
            }
            activeFileName = name;
            activeTotalBytes = total;
        }
        long received = store.resumableBytes(name, total);
        speedSampleBytes = received;
        speedSampleTimeMs = SystemClock.elapsedRealtime();
        Snapshot current = new Snapshot(
                State.RECEIVING,
                name,
                received,
                total,
                0L,
                received > 0L ? "继续传输 " + name : "准备接收 " + name
        );
        if (announce) {
            publish(current);
        }
        return current;
    }

    private Snapshot receiveChunk(Request request, InputStream input) throws IOException {
        Snapshot prepared = prepare(request.query, false);
        long offset = nonNegativeLong(request.query.get("offset"), "传输偏移量无效");
        long contentLength = nonNegativeLong(
                request.headers.get("content-length"),
                "浏览器没有提供分块大小"
        );
        if (contentLength <= 0L || contentLength > MAX_CHUNK_BYTES) {
            throw new IOException("传输分块大小无效");
        }
        File partial = store.partialFile(prepared.fileName);
        long currentLength = partial.isFile() ? partial.length() : 0L;
        if (offset != currentLength) {
            throw new IOException("传输位置已变化，请重新点击开始传输");
        }
        if (offset + contentLength > prepared.totalBytes) {
            throw new IOException("传输内容超过文件大小");
        }

        byte[] buffer = new byte[128 * 1024];
        long remaining = contentLength;
        try (RandomAccessFile output = new RandomAccessFile(partial, "rw")) {
            output.seek(offset);
            while (remaining > 0L) {
                int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (read < 0) {
                    throw new EOFException("浏览器提前中断了这个分块");
                }
                output.write(buffer, 0, read);
                remaining -= read;
            }
            output.getFD().sync();
        }

        long received = offset + contentLength;
        long now = SystemClock.elapsedRealtime();
        long elapsed = Math.max(1L, now - speedSampleTimeMs);
        long speed = Math.max(0L, Math.round((received - speedSampleBytes) * 1000d / elapsed));
        speedSampleBytes = received;
        speedSampleTimeMs = now;

        if (received < prepared.totalBytes) {
            Snapshot current = new Snapshot(
                    State.RECEIVING,
                    prepared.fileName,
                    received,
                    prepared.totalBytes,
                    speed,
                    "正在接收 " + prepared.fileName
            );
            publish(current);
            return current;
        }

        publish(new Snapshot(
                State.VERIFYING,
                prepared.fileName,
                received,
                prepared.totalBytes,
                0L,
                "正在检查 ZIM 文件格式"
        ));
        try {
            ZimArchive.validate(context, partial);
            File installed = store.activate(prepared.fileName, prepared.totalBytes);
            Snapshot complete = new Snapshot(
                    State.COMPLETE,
                    prepared.fileName,
                    received,
                    prepared.totalBytes,
                    0L,
                    "导入完成，可以回到 App 阅读"
            );
            publish(complete);
            synchronized (stateLock) {
                activeFileName = "";
                activeTotalBytes = 0L;
            }
            listener.onImported(installed);
            return complete;
        } catch (Exception | LinkageError error) {
            try {
                store.deletePartial(prepared.fileName);
            } catch (IOException ignored) {
                // Preserve the validation error; cleanup can be retried by the user.
            }
            Snapshot failed = errorSnapshot("不是可读取的 ZIM 文件：" + readable(error));
            publish(failed);
            synchronized (stateLock) {
                activeFileName = "";
                activeTotalBytes = 0L;
            }
            return failed;
        }
    }

    private Snapshot errorSnapshot(String message) {
        synchronized (stateLock) {
            return new Snapshot(
                    State.ERROR,
                    activeFileName,
                    snapshot.receivedBytes,
                    activeTotalBytes,
                    0L,
                    message
            );
        }
    }

    private void publish(Snapshot next) {
        synchronized (stateLock) {
            snapshot = next;
        }
        listener.onSnapshot(next);
    }

    private static Request readRequest(InputStream input) throws IOException {
        String requestLine = readLine(input);
        String[] parts = requestLine.split(" ", 3);
        if (parts.length != 3 || !parts[2].startsWith("HTTP/1.")) {
            throw new IOException("浏览器请求格式无效");
        }
        String target = parts[1];
        int question = target.indexOf('?');
        String path = question >= 0 ? target.substring(0, question) : target;
        Map<String, String> query = question >= 0
                ? parseQuery(target.substring(question + 1))
                : Collections.emptyMap();
        Map<String, String> headers = new HashMap<>();
        int headerBytes = requestLine.length();
        while (true) {
            String line = readLine(input);
            headerBytes += line.length();
            if (headerBytes > MAX_HEADER_BYTES) {
                throw new IOException("浏览器请求头过大");
            }
            if (line.isEmpty()) {
                break;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                throw new IOException("浏览器请求头格式无效");
            }
            headers.put(
                    line.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                    line.substring(colon + 1).trim()
            );
        }
        return new Request(parts[0], path, query, headers);
    }

    private static String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(128);
        int previous = -1;
        while (output.size() <= MAX_HEADER_BYTES) {
            int value = input.read();
            if (value < 0) {
                throw new EOFException("浏览器连接已关闭");
            }
            if (previous == '\r' && value == '\n') {
                byte[] bytes = output.toByteArray();
                return new String(bytes, 0, Math.max(0, bytes.length - 1), StandardCharsets.US_ASCII);
            }
            output.write(value);
            previous = value;
        }
        throw new IOException("浏览器请求行过长");
    }

    private static Map<String, String> parseQuery(String raw) throws IOException {
        Map<String, String> result = new HashMap<>();
        if (raw.isEmpty()) {
            return result;
        }
        for (String pair : raw.split("&")) {
            int equals = pair.indexOf('=');
            String key = decode(equals < 0 ? pair : pair.substring(0, equals));
            String value = decode(equals < 0 ? "" : pair.substring(equals + 1));
            result.put(key, value);
        }
        return result;
    }

    private static String decode(String value) throws IOException {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8.name());
        } catch (IllegalArgumentException error) {
            throw new IOException("网址参数编码无效", error);
        }
    }

    private static long positiveLong(String value, String message) throws IOException {
        long parsed = nonNegativeLong(value, message);
        if (parsed <= 0L) {
            throw new IOException(message);
        }
        return parsed;
    }

    private static long nonNegativeLong(String value, String message) throws IOException {
        try {
            long parsed = Long.parseLong(value == null ? "" : value);
            if (parsed < 0L) {
                throw new NumberFormatException();
            }
            return parsed;
        } catch (NumberFormatException error) {
            throw new IOException(message, error);
        }
    }

    private static void respondJson(OutputStream output, int status, Snapshot snapshot)
            throws IOException {
        String json = "{\"state\":\"" + snapshot.state.name().toLowerCase(Locale.ROOT)
                + "\",\"fileName\":\"" + jsonEscape(snapshot.fileName)
                + "\",\"received\":" + snapshot.receivedBytes
                + ",\"total\":" + snapshot.totalBytes
                + ",\"message\":\"" + jsonEscape(snapshot.message) + "\"}";
        respond(output, status, "OK", "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
    }

    private static void respondText(
            OutputStream output,
            int status,
            String reason,
            String message
    ) throws IOException {
        respond(output, status, reason, "text/plain; charset=utf-8", message.getBytes(StandardCharsets.UTF_8));
    }

    private static void respond(
            OutputStream output,
            int status,
            String reason,
            String contentType,
            byte[] body
    ) throws IOException {
        String headers = "HTTP/1.1 " + status + " " + reason + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "X-Content-Type-Options: nosniff\r\n"
                + "Connection: close\r\n\r\n";
        output.write(headers.getBytes(StandardCharsets.US_ASCII));
        output.write(body);
        output.flush();
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }

    private static byte[] readAsset(Context context, String path, int limit) throws IOException {
        try (InputStream input = context.getAssets().open(path)) {
            return readBounded(input, limit);
        }
    }

    private byte[] readAsset(String path, int limit) throws IOException {
        return readAsset(context, path, limit);
    }

    private static byte[] readBounded(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            total += read;
            if (total > limit) {
                throw new IOException("内置导入页面过大");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static List<String> localUrls(int port) throws IOException {
        List<String> urls = new ArrayList<>();
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces != null && interfaces.hasMoreElements()) {
            NetworkInterface network = interfaces.nextElement();
            if (!network.isUp() || network.isLoopback()) {
                continue;
            }
            Enumeration<InetAddress> addresses = network.getInetAddresses();
            while (addresses.hasMoreElements()) {
                InetAddress address = addresses.nextElement();
                if (address instanceof Inet4Address
                        && !address.isLoopbackAddress()
                        && address.isSiteLocalAddress()) {
                    urls.add("http://" + address.getHostAddress() + ":" + port + "/");
                }
            }
        }
        Collections.sort(urls);
        return urls;
    }

    private static String readable(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName()
                : message;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        Socket socket = activeSocket;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Best effort cancellation.
            }
        }
        ServerSocket server = serverSocket;
        if (server != null) {
            try {
                server.close();
            } catch (IOException ignored) {
                // Best effort shutdown.
            }
        }
        Thread thread = serverThread;
        if (thread != null) {
            thread.interrupt();
        }
    }

    private static final class Request {
        final String method;
        final String path;
        final Map<String, String> query;
        final Map<String, String> headers;

        Request(
                String method,
                String path,
                Map<String, String> query,
                Map<String, String> headers
        ) {
            this.method = method;
            this.path = path;
            this.query = query;
            this.headers = headers;
        }
    }
}
