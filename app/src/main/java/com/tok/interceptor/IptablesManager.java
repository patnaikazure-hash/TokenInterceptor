package com.tok.interceptor;

import android.util.Log;

public class IptablesManager {

    private static final String TAG = "IptablesManager";
    private static final int PROXY_PORT = 8080;
    private final int targetUid;

    public IptablesManager(int targetUid) {
        this.targetUid = targetUid;
    }

    public String start() throws Exception {
        // Delete OUR specific rules first (avoid duplicates without touching other rules).
        // Using -D is surgical — -F would wipe the entire OUTPUT chain including system rules.
        RootShell.run(
            "iptables -t nat -D OUTPUT -p tcp --dport 443 "
                + "-m owner --uid-owner " + targetUid
                + " -j REDIRECT --to-ports " + PROXY_PORT
                + " 2>/dev/null || true",
            "iptables -t nat -D OUTPUT -p tcp --dport 80 "
                + "-m owner --uid-owner " + targetUid
                + " -j REDIRECT --to-ports " + PROXY_PORT
                + " 2>/dev/null || true",
            "ip6tables -t nat -D OUTPUT -p tcp --dport 443 "
                + "-m owner --uid-owner " + targetUid
                + " -j REDIRECT --to-ports " + PROXY_PORT
                + " 2>/dev/null || true",
            "ip6tables -t nat -D OUTPUT -p tcp --dport 80 "
                + "-m owner --uid-owner " + targetUid
                + " -j REDIRECT --to-ports " + PROXY_PORT
                + " 2>/dev/null || true"
        );

        // Add redirect rules for the target UID
        // Port 443 (HTTPS) and 80 (HTTP)
        String result = RootShell.run(
            "iptables -t nat -A OUTPUT -p tcp --dport 443 "
                + "-m owner --uid-owner " + targetUid
                + " -j REDIRECT --to-ports " + PROXY_PORT
                + " 2>&1",
            "iptables -t nat -A OUTPUT -p tcp --dport 80 "
                + "-m owner --uid-owner " + targetUid
                + " -j REDIRECT --to-ports " + PROXY_PORT
                + " 2>&1",
            // Verify rules were added
            "echo '--- iptables nat OUTPUT ---'",
            "iptables -t nat -L OUTPUT -n --line-numbers 2>&1"
        );

        Log.d(TAG, "iptables start result: " + result);
        return result;
    }

    public String stop() throws Exception {
        // Delete specific rules
        String result = RootShell.run(
            "iptables -t nat -D OUTPUT -p tcp --dport 443 "
                + "-m owner --uid-owner " + targetUid
                + " -j REDIRECT --to-ports " + PROXY_PORT
                + " 2>/dev/null || true",
            "iptables -t nat -D OUTPUT -p tcp --dport 80 "
                + "-m owner --uid-owner " + targetUid
                + " -j REDIRECT --to-ports " + PROXY_PORT
                + " 2>/dev/null || true",
            "echo 'iptables rules removed'"
        );
        Log.d(TAG, "iptables stop result: " + result);
        return result;
    }

    public String getStatus() throws Exception {
        return RootShell.run(
            "iptables -t nat -L OUTPUT -n --line-numbers 2>&1",
            "echo '---'",
            "iptables -t nat -S OUTPUT 2>&1"
        );
    }
}
