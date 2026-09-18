package com.patch.tool;

import android.app.Activity;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;

/** root 版执行层：所有 shell 命令走 su。 */
public final class ShellExec {

    private ShellExec() { }

    public static boolean isReady() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "id"});
            String out = readAll(p.getInputStream());
            p.waitFor();
            return out != null && out.contains("uid=0");
        } catch (Throwable t) {
            return false;
        }
    }

    /** root 版无需额外授权。 */
    public static void requestPermission(Activity a) { }

    public static String execOut(String cmd) throws Exception {
        Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
        String o = readAll(p.getInputStream());
        String e = readAll(p.getErrorStream());
        int code = p.waitFor();
        if (code != 0) throw new RuntimeException("su exit " + code + " | " + e);
        return o;
    }

    private static String readAll(InputStream is) throws Exception {
        BufferedReader br = new BufferedReader(new InputStreamReader(is));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line).append("\n");
        return sb.toString();
    }
}
