package com.patch.tool;

import android.app.Activity;
import android.content.pm.PackageManager;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;

import rikka.shizuku.Shizuku;

/**
 * Shizuku 版执行层：以 adb shell (uid 2000 / shell 域) 身份执行命令，无需 root。
 * shell 域对 /storage/emulated/0（含其它应用的 Android/data）可读可写，
 * 足以完成：读取受保护的源 zip、写入游戏 Android/data/<pkg>/files、备份/还原。
 *
 * 说明：Shizuku 13.x 未公开 newProcess（private），这里通过反射调用（Shizuku 为普通库类，
 * 不受 Android hidden-API 限制），以获得一个以 shell 身份运行的 Process。
 */
public final class ShellExec {

    private static final int REQ_PERMISSION = 4213;
    private static Method sNewProcess;

    private ShellExec() { }

    public static boolean isReady() {
        try {
            if (!Shizuku.pingBinder()) return false;
            if (Shizuku.isPreV11()) return true;
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 未授权时申请 Shizuku 权限。 */
    public static void requestPermission(Activity a) {
        try {
            if (!Shizuku.pingBinder()) return;
            if (Shizuku.isPreV11()) return;
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) return;
            Shizuku.requestPermission(REQ_PERMISSION);
        } catch (Throwable ignored) { }
    }

    public static String execOut(String cmd) throws Exception {
        Process p = newProcess(new String[]{"sh", "-c", cmd}, null, null);
        String o = readAll(p.getInputStream());
        String e = readAll(p.getErrorStream());
        int code = p.waitFor();
        if (code != 0) throw new RuntimeException("sh exit " + code + " | " + e);
        return o;
    }

    private static Process newProcess(String[] cmd, String[] env, String dir) throws Exception {
        if (sNewProcess == null) {
            Method m = Shizuku.class.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
            m.setAccessible(true);
            sNewProcess = m;
        }
        return (Process) sNewProcess.invoke(null, cmd, env, dir);
    }

    private static String readAll(InputStream is) throws Exception {
        BufferedReader br = new BufferedReader(new InputStreamReader(is));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line).append("\n");
        return sb.toString();
    }
}
