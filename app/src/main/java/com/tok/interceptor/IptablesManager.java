package com.tok.interceptor;

public class IptablesManager {

    private static final int PROXY_PORT = 8080;
    private final int targetUid;

    public IptablesManager(int targetUid) {
        this.targetUid = targetUid;
    }

    public void start() throws Exception {
        RootShell.run(
            "iptables -t nat -A OUTPUT -p tcp --dport 443 "
                + "-m owner --uid-owner " + targetUid
                + " -j REDIRECT --to-ports " + PROXY_PORT,
            "iptables -t nat -A OUTPUT -p tcp --dport 80 "
                + "-m owner --uid-owner " + targetUid
                + " -j REDIRECT --to-ports " + PROXY_PORT
        );
    }

    public void stop() throws Exception {
        RootShell.run(
            "iptables -t nat -D OUTPUT -p tcp --dport 443 "
                + "-m owner --uid-owner " + targetUid
                + " -j REDIRECT --to-ports " + PROXY_PORT,
            "iptables -t nat -D OUTPUT -p tcp --dport 80 "
                + "-m owner --uid-owner " + targetUid
                + " -j REDIRECT --to-ports " + PROXY_PORT
        );
    }
}
