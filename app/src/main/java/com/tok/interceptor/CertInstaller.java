package com.tok.interceptor;

import android.content.Context;
import android.util.Log;

import java.io.*;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.concurrent.ConcurrentHashMap;

import javax.net.ssl.*;

/**
 * Handles MITM certificate generation and system cert store installation.
 * Uses Bouncy Castle (included via Android's internal APIs) to generate
 * per-host certificates signed by our root CA.
 */
public class CertInstaller {
    private static final String TAG = "CertInstaller";
    private static final String CA_ALIAS = "tok_mitm_ca";
    private static KeyPair caKeyPair;
    private static X509Certificate caCert;
    private static final ConcurrentHashMap<String, SSLContext> sslContextCache = new ConcurrentHashMap<>();

    /**
     * Generate our root CA and install it into the system trust store (needs root).
     */
    public static boolean installCert(Context context) {
        try {
            // Generate CA key pair
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048, new SecureRandom());
            caKeyPair = kpg.generateKeyPair();

            // Generate self-signed CA cert using Android's built-in cert generation
            caCert = generateSelfSignedCert(caKeyPair, "CN=TokenInterceptor CA, O=TokInterceptor, C=US");

            // Export cert to PEM
            String pemCert = toPem(caCert.getEncoded());

            // Write to temp file
            File tmpCert = new File(context.getCacheDir(), "tok_ca.pem");
            try (FileWriter fw = new FileWriter(tmpCert)) {
                fw.write(pemCert);
            }

            // Get cert hash for Android system trust store filename
            MessageDigest md = MessageDigest.getInstance("MD5");
            md.update(caCert.getSubjectX500Principal().getEncoded());
            byte[] hash = md.digest();
            String certHash = String.format("%02x%02x%02x%02x", hash[3], hash[2], hash[1], hash[0]);
            String certFileName = certHash + ".0";

            // Install via root shell
            String[] cmds = {
                "mount -o rw,remount /system",
                "cp " + tmpCert.getAbsolutePath() + " /system/etc/security/cacerts/" + certFileName,
                "chmod 644 /system/etc/security/cacerts/" + certFileName,
                "mount -o ro,remount /system"
            };

            StringBuilder script = new StringBuilder();
            for (String cmd : cmds) script.append(cmd).append("\n");

            Process su = Runtime.getRuntime().exec("su");
            su.getOutputStream().write(script.toString().getBytes());
            su.getOutputStream().flush();
            su.getOutputStream().close();
            int exit = su.waitFor();

            Log.d(TAG, "Cert install exit code: " + exit);
            return exit == 0;

        } catch (Exception e) {
            Log.e(TAG, "Failed to install cert", e);
            return false;
        }
    }

    /**
     * Returns an SSLContext that presents a fake cert for the given hostname,
     * signed by our root CA. Used to MITM HTTPS connections.
     */
    public static SSLContext getMitmSSLContext(Context context, String hostname) throws Exception {
        // Lazy-init CA if needed
        if (caKeyPair == null || caCert == null) {
            initCA();
        }

        return sslContextCache.computeIfAbsent(hostname, host -> {
            try {
                // Generate key pair for this host
                KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
                kpg.initialize(2048, new SecureRandom());
                KeyPair hostKey = kpg.generateKeyPair();

                // Generate cert for this host, signed by our CA
                X509Certificate hostCert = generateSignedCert(hostKey, caCert, caKeyPair.getPrivate(),
                    "CN=" + host + ", O=TokInterceptor, C=US", host);

                // Build KeyStore with our fake cert
                KeyStore ks = KeyStore.getInstance("PKCS12");
                ks.load(null, null);
                ks.setKeyEntry("cert", hostKey.getPrivate(), null,
                    new java.security.cert.Certificate[]{hostCert, caCert});

                KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                kmf.init(ks, null);

                SSLContext ctx = SSLContext.getInstance("TLS");
                ctx.init(kmf.getKeyManagers(), null, new SecureRandom());
                return ctx;
            } catch (Exception e) {
                Log.e(TAG, "Failed to create SSL context for " + host, e);
                try {
                    return SSLContext.getDefault();
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            }
        });
    }

    private static void initCA() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048, new SecureRandom());
        caKeyPair = kpg.generateKeyPair();
        caCert = generateSelfSignedCert(caKeyPair, "CN=TokenInterceptor CA, O=TokInterceptor, C=US");
    }

    /**
     * Generate a self-signed X.509 cert using Android's internal sun.security APIs.
     * Falls back to a minimal ASN.1 construction if unavailable.
     */
    private static X509Certificate generateSelfSignedCert(KeyPair keyPair, String subject) throws Exception {
        return buildCert(keyPair.getPublic(), caCert, keyPair.getPrivate(), subject, subject, null, true);
    }

    private static X509Certificate generateSignedCert(KeyPair keyPair, X509Certificate issuerCert,
            PrivateKey issuerKey, String subject, String san) throws Exception {
        return buildCert(keyPair.getPublic(), issuerCert, issuerKey, subject,
            issuerCert.getSubjectDN().getName(), san, false);
    }

    @SuppressWarnings("deprecation")
    private static X509Certificate buildCert(PublicKey pubKey, X509Certificate issuerCert,
            PrivateKey signingKey, String subject, String issuer, String san, boolean isCA) throws Exception {
        // Use Android's internal X509 cert generator via reflection
        try {
            Class<?> x509Class = Class.forName("sun.security.x509.X509CertImpl");
            Class<?> infoClass = Class.forName("sun.security.x509.X509CertInfo");
            Class<?> nameClass = Class.forName("sun.security.x509.X500Name");
            Class<?> dateClass = Class.forName("sun.security.x509.CertificateValidity");
            Class<?> snClass = Class.forName("sun.security.x509.CertificateSerialNumber");
            Class<?> algIdClass = Class.forName("sun.security.x509.AlgorithmId");
            Class<?> certAlgClass = Class.forName("sun.security.x509.CertificateAlgorithmId");

            Object info = infoClass.newInstance();
            Date from = new Date();
            Date to = new Date(from.getTime() + 365L * 24 * 60 * 60 * 1000 * 2);

            Object validity = dateClass.getConstructor(Date.class, Date.class).newInstance(from, to);
            BigInteger sn = BigInteger.valueOf(System.currentTimeMillis());

            Object subjectName = nameClass.getConstructor(String.class).newInstance(subject);
            Object issuerName = nameClass.getConstructor(String.class).newInstance(issuer);

            Object algId = algIdClass.getMethod("get", String.class).invoke(null, "SHA256withRSA");
            Object certAlg = certAlgClass.getConstructor(algIdClass).newInstance(algId);

            infoClass.getMethod("set", String.class, Object.class).invoke(info, "validity", validity);
            infoClass.getMethod("set", String.class, Object.class).invoke(info, "serialNumber",
                snClass.getConstructor(BigInteger.class).newInstance(sn));
            infoClass.getMethod("set", String.class, Object.class).invoke(info, "subject",
                Class.forName("sun.security.x509.CertificateSubjectName")
                    .getConstructor(nameClass).newInstance(subjectName));
            infoClass.getMethod("set", String.class, Object.class).invoke(info, "issuer",
                Class.forName("sun.security.x509.CertificateIssuerName")
                    .getConstructor(nameClass).newInstance(issuerName));
            infoClass.getMethod("set", String.class, Object.class).invoke(info, "key",
                Class.forName("sun.security.x509.CertificateX509Key")
                    .getConstructor(PublicKey.class).newInstance(pubKey));
            infoClass.getMethod("set", String.class, Object.class).invoke(info, "algorithmID", certAlg);
            infoClass.getMethod("set", String.class, Object.class).invoke(info, "version",
                Class.forName("sun.security.x509.CertificateVersion")
                    .getConstructor(int.class).newInstance(2));

            Object cert = x509Class.getConstructor(infoClass).newInstance(info);
            x509Class.getMethod("sign", PrivateKey.class, String.class).invoke(cert, signingKey, "SHA256withRSA");
            return (X509Certificate) cert;

        } catch (Exception e) {
            // Fallback: minimal DER-encoded self-signed cert
            Log.w(TAG, "sun.security reflection failed, using minimal cert: " + e.getMessage());
            return generateMinimalCert(pubKey, signingKey, subject);
        }
    }

    /**
     * Minimal fallback cert generator that doesn't require sun.security internals.
     * Builds a basic DER-encoded X.509v1 cert.
     */
    private static X509Certificate generateMinimalCert(PublicKey pubKey, PrivateKey privKey,
            String subject) throws Exception {
        // Build minimal DER cert structure
        byte[] pubKeyBytes = pubKey.getEncoded();
        byte[] subjectBytes = encodeRdnSequence(subject);
        long now = System.currentTimeMillis();
        byte[] validity = encodeValidity(now, now + 2L * 365 * 24 * 60 * 60 * 1000);
        byte[] serialNumber = encodeInteger(BigInteger.valueOf(now));

        // SHA256withRSA OID: 1.2.840.113549.1.1.11
        byte[] sigAlg = new byte[]{
            0x30, 0x0d, 0x06, 0x09, 0x2a, (byte)0x86, 0x48, (byte)0x86, (byte)0xf7,
            0x0d, 0x01, 0x01, 0x0b, 0x05, 0x00
        };

        // TBSCertificate
        ByteArrayOutputStream tbs = new ByteArrayOutputStream();
        tbs.write(tag(0xa0, new byte[]{0x02, 0x01, 0x02})); // version: v3
        tbs.write(serialNumber);
        tbs.write(sigAlg);
        tbs.write(tag(0x30, subjectBytes)); // issuer
        tbs.write(validity);
        tbs.write(tag(0x30, subjectBytes)); // subject
        tbs.write(pubKeyBytes);
        byte[] tbsBytes = tag(0x30, tbs.toByteArray());

        // Sign
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(privKey);
        sig.update(tbsBytes);
        byte[] signature = sig.sign();

        // Certificate ::= SEQUENCE { tbsCertificate, signatureAlgorithm, signatureValue }
        ByteArrayOutputStream cert = new ByteArrayOutputStream();
        cert.write(tbsBytes);
        cert.write(sigAlg);
        cert.write(tag(0x03, prependZero(signature))); // BIT STRING
        byte[] certBytes = tag(0x30, cert.toByteArray());

        java.security.cert.CertificateFactory cf = java.security.cert.CertificateFactory.getInstance("X.509");
        return (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(certBytes));
    }

    private static byte[] tag(int tag, byte[] content) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        writeLength(out, content.length);
        out.write(content);
        return out.toByteArray();
    }

    private static void writeLength(OutputStream out, int length) throws IOException {
        if (length < 128) {
            out.write(length);
        } else if (length < 256) {
            out.write(0x81);
            out.write(length);
        } else {
            out.write(0x82);
            out.write((length >> 8) & 0xff);
            out.write(length & 0xff);
        }
    }

    private static byte[] encodeRdnSequence(String dn) throws Exception {
        // Minimal: just encode as UTF8String inside SET inside SEQUENCE
        byte[] dnBytes = dn.getBytes(StandardCharsets.UTF_8);
        byte[] utf8 = tag(0x0c, dnBytes); // UTF8String
        // OID for commonName: 2.5.4.3
        byte[] cnOid = {0x06, 0x03, 0x55, 0x04, 0x03};
        byte[] atv = tag(0x30, concat(cnOid, utf8)); // AttributeTypeAndValue
        byte[] rdn = tag(0x31, atv); // RelativeDistinguishedName
        return rdn;
    }

    private static byte[] encodeValidity(long notBefore, long notAfter) throws Exception {
        // Encode as UTCTime (YYMMDDHHMMSSZ)
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyMMddHHmmss");
        sdf.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        byte[] nb = (sdf.format(new Date(notBefore)) + "Z").getBytes(StandardCharsets.UTF_8);
        byte[] na = (sdf.format(new Date(notAfter)) + "Z").getBytes(StandardCharsets.UTF_8);
        byte[] nbTime = tag(0x17, nb);
        byte[] naTime = tag(0x17, na);
        return tag(0x30, concat(nbTime, naTime));
    }

    private static byte[] encodeInteger(BigInteger n) throws Exception {
        return tag(0x02, n.toByteArray());
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] r = new byte[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    private static byte[] prependZero(byte[] b) {
        byte[] r = new byte[b.length + 1];
        r[0] = 0;
        System.arraycopy(b, 0, r, 1, b.length);
        return r;
    }

    private static String toPem(byte[] der) {
        String b64 = android.util.Base64.encodeToString(der, android.util.Base64.DEFAULT);
        return "-----BEGIN CERTIFICATE-----\n" + b64 + "-----END CERTIFICATE-----\n";
    }

}
