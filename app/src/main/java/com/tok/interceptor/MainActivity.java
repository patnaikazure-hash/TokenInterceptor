package com.tok.interceptor;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.os.*;
import android.view.*;
import android.widget.*;

public class MainActivity extends Activity {

    private static final int REQ_PICK_APP = 1;

    private EditText etBotToken, etChannelId, etTargetApp;
    private TextView tvStatus, tvLog;
    private Button btnPickApp, btnStartStop, btnInstallCert;
    private ScrollView scrollView;

    private ProxyManager    proxyManager;
    private IptablesManager iptablesManager;
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
        if (selectedUid == -1) {
            toast("Pick a target app first"); return;
        }

        prefs.edit()
            .putString("bot_token",  botToken)
            .putString("channel_id", channelId)
            .apply();

        tvStatus.setText("⏳ Starting...");
        btnStartStop.setEnabled(false);
        iptablesManager = new IptablesManager(selectedUid);

        new Thread(() -> {
            try {
                proxyManager.start(botToken, channelId);
                Thread.sleep(1000);
                iptablesManager.start();
                running = true;
                runOnUiThread(() -> {
                    tvStatus.setText("🟢 Intercepting — " + selectedPackage);
                    btnStartStop.setText("Stop");
                    btnStartStop.setEnabled(true);
                    log("Started. Tokens will appear in " + channelId);
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
                CertInstaller.install();
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
