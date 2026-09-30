package com.patch.tool;

import android.app.Activity;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** root 版执行层：所有 shell 命令走 su。 */
public final class ShellExec {

    private ShellExec() { }

    public static boolean isReady() {
        try {
            ShellResult result = exec("id");
            return result.isSuccess() && result.output().contains("uid=0");
        } catch (Throwable t) {
            return false;
        }
    }

    /** root 版无需额外授权。 */
    public static void requestPermission(Activity a) { }

    public static ShellResult exec(String cmd) throws Exception {
        Process p = new ProcessBuilder("su", "-c", cmd).start();
        StreamCollector stdout = new StreamCollector(p.getInputStream());
        StreamCollector stderr = new StreamCollector(p.getErrorStream());
        stdout.start();
        stderr.start();

        int code = p.waitFor();
        stdout.join();
        stderr.join();
        stdout.rethrow();
        stderr.rethrow();
        return new ShellResult(code, stdout.value(), stderr.value());
    }

    public static String execOut(String cmd) throws Exception {
        ShellResult result = exec(cmd);
        if (!result.isSuccess()) {
            throw new RuntimeException(result.failure("su 执行"));
        }
        return result.stdout;
    }

    private static final class StreamCollector extends Thread {
        private final InputStream input;
        private final StringBuilder value = new StringBuilder();
        private Exception error;

        StreamCollector(InputStream input) {
            this.input = input;
            setDaemon(true);
        }

        @Override
        public void run() {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(input, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) value.append(line).append('\n');
            } catch (Exception e) {
                error = e;
            }
        }

        String value() {
            return value.toString();
        }

        void rethrow() throws Exception {
            if (error != null) throw error;
        }
    }
}
