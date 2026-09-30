package com.patch.tool;

/** Result of one shell command, including its exit code and captured output. */
public final class ShellResult {
    public final int exitCode;
    public final String stdout;
    public final String stderr;

    public ShellResult(int exitCode, String stdout, String stderr) {
        this.exitCode = exitCode;
        this.stdout = stdout == null ? "" : stdout;
        this.stderr = stderr == null ? "" : stderr;
    }

    public boolean isSuccess() {
        return exitCode == 0;
    }

    public String output() {
        StringBuilder text = new StringBuilder(stdout);
        if (!stderr.isEmpty()) {
            if (text.length() > 0 && text.charAt(text.length() - 1) != '\n') text.append('\n');
            text.append(stderr);
        }
        return text.toString();
    }

    public String failure(String action) {
        String detail = output().trim();
        return action + "失败 (退出码 " + exitCode + ")" + (detail.isEmpty() ? "" : ": " + detail);
    }
}
