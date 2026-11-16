package com.tok.interceptor;

public class CertInstaller {

    public static void install() throws Exception {
        // generate cert by running mitmdump briefly
        RootShell.run(
            "mitmdump --listen-host 127.0.0.1 --listen-port 18080 &",
            "sleep 3",
            "kill $(pgrep -f 'listen-port 18080') 2>/dev/null || true"
        );

        String certSrc = "/root/.mitmproxy/mitmproxy-ca-cert.pem";

        // get hash
        String hash = RootShell.run(
            "openssl x509 -inform PEM -subject_hash_old -in " + certSrc + " | head -1"
        ).trim();

        if (hash.isEmpty()) {
            throw new Exception("Could not get cert hash. Is mitmdump installed?");
        }

        RootShell.run(
            "mount -o rw,remount /system",
            "cp " + certSrc + " /system/etc/security/cacerts/" + hash + ".0",
            "chmod 644 /system/etc/security/cacerts/" + hash + ".0",
            "mount -o ro,remount /system"
        );
    }
}
