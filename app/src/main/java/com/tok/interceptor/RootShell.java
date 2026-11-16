package com.tok.interceptor;

import java.io.*;

public class RootShell {

    public static String run(String... commands) throws Exception {
        Process su = Runtime.getRuntime().exec("su");
        DataOutputStream os = new DataOutputStream(su.getOutputStream());

        for (String cmd : commands) {
            os.writeBytes(cmd + "\n");
        }
        os.writeBytes("exit\n");
        os.flush();

        BufferedReader reader = new BufferedReader(
            new InputStreamReader(su.getInputStream()));
        StringBuilder output = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            output.append(line).append("\n");
        }

        BufferedReader errReader = new BufferedReader(
            new InputStreamReader(su.getErrorStream()));
        StringBuilder err = new StringBuilder();
        while ((line = errReader.readLine()) != null) {
            err.append(line).append("\n");
        }

        su.waitFor();
        return output.toString();
    }

    public static boolean checkRoot() {
        try {
            String result = run("id");
            return result.contains("uid=0");
        } catch (Exception e) {
            return false;
        }
    }
}
