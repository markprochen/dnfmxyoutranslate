package com.patch.tool;

import android.app.Activity;
import android.app.Dialog;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class MainActivity extends Activity {

    private EditText etSrc, etTgt;
    private TextView tvLog;

    private interface DirPick { void pick(String path); }
    private static final int MODE_DIR = 0;
    private static final int MODE_FILE = 1;
    private int browseMode = MODE_DIR;
    private DirPick browseCb;
    private Dialog browserDialog;
    private ListView lvBrowser;
    private EditText tvPath;
    private EditText etSearch;
    private Button btnSelectDir;
    private String curBrowseDir = "/storage/emulated/0";
    private boolean isSearchResult = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        etSrc = findViewById(R.id.etSrc);
        etTgt = findViewById(R.id.etTgt);
        tvLog = findViewById(R.id.tvLog);

        Button btnSrc = findViewById(R.id.btnSrc);
        Button btnTgt = findViewById(R.id.btnTgt);
        Button btnParse = findViewById(R.id.btnParse);
        Button btnApply = findViewById(R.id.btnApply);
        Button btnRestore = findViewById(R.id.btnRestore);

        btnSrc.setOnClickListener(v -> openRootBrowser(MODE_FILE, startSrc(), p -> etSrc.setText(p)));
        btnTgt.setOnClickListener(v -> openRootBrowser(MODE_DIR, startTgt(), p -> etTgt.setText(p)));
        btnParse.setOnClickListener(v -> runTask(this::doParse));
        btnApply.setOnClickListener(v -> runTask(this::doApply));
        btnRestore.setOnClickListener(v -> runTask(this::doRestore));
    }

    private String startSrc() {
        String s = etSrc.getText().toString().trim();
        if (!s.isEmpty()) {
            File f = new File(s);
            return f.isFile() ? f.getParent() : s;
        }
        return dirExists("/storage/emulated/0/Android/data") ? "/storage/emulated/0/Android/data" : "/storage/emulated/0";
    }

    private String startTgt() {
        String s = etTgt.getText().toString().trim();
        if (!s.isEmpty()) return s;
        if (dirExists("/storage/emulated/0/Android/data/com.nexon.mdnf")) return "/storage/emulated/0/Android/data/com.nexon.mdnf";
        return dirExists("/storage/emulated/0/Android/data") ? "/storage/emulated/0/Android/data" : "/storage/emulated/0";
    }

    // 统一计算"游戏 files 目录"：目标可能是游戏数据根(com.nexon.mdnf)或已到 files 级
    private String gameFiles(String tgt) {
        String t = tgt.trim();
        while (t.endsWith("/")) t = t.substring(0, t.length() - 1);
        return t.endsWith("/files") ? t : t + "/files";
    }

    private boolean dirExists(String d) {
        try { String o = execSuOut("test -d " + q(d) + " && echo 1"); return o.trim().equals("1"); }
        catch (Throwable t) { return false; }
    }

    // ================== root 文件浏览器 ==================
    private void openRootBrowser(int mode, String startDir, DirPick cb) {
        browseMode = mode;
        browseCb = cb;
        if (browserDialog != null && browserDialog.isShowing()) browserDialog.dismiss();
        browserDialog = new Dialog(this);
        browserDialog.setContentView(R.layout.dialog_browser);
        browserDialog.setTitle(mode == MODE_DIR ? "选择目录" : "选择 zip 文件");
        lvBrowser = browserDialog.findViewById(R.id.lvBrowser);
        tvPath = browserDialog.findViewById(R.id.tvPath);
        etSearch = browserDialog.findViewById(R.id.etSearch);
        btnSelectDir = browserDialog.findViewById(R.id.btnSelectDir);
        Button btnJump = browserDialog.findViewById(R.id.btnJump);
        Button btnRoot = browserDialog.findViewById(R.id.btnRoot);
        Button btnCancel = browserDialog.findViewById(R.id.btnCancel);
        Button btnSearch = browserDialog.findViewById(R.id.btnSearch);

        btnSelectDir.setVisibility(mode == MODE_DIR ? View.VISIBLE : View.GONE);
        btnSelectDir.setOnClickListener(v -> pick(curBrowseDir));
        btnCancel.setOnClickListener(v -> browserDialog.dismiss());
        btnJump.setOnClickListener(v -> {
            String t = tvPath.getText().toString().trim();
            if (t.isEmpty()) t = "/storage/emulated/0";
            loadDir(t);
        });
        btnRoot.setOnClickListener(v -> loadDir("/storage/emulated/0"));
        btnSearch.setOnClickListener(v -> doSearch());

        lvBrowser.setOnItemClickListener((adapter, view, pos, id) -> onItemClick(pos));
        browserDialog.show();
        loadDir(startDir);
    }

    private void pick(String path) {
        if (browseCb != null) browseCb.pick(path);
        log("选择: " + path);
        if (browserDialog != null) browserDialog.dismiss();
    }

    private void onItemClick(int pos) {
        Object item = lvBrowser.getAdapter().getItem(pos);
        if (!(item instanceof String)) return;
        String label = (String) item;
        // 搜索结果：条目是完整路径
        if (isSearchResult) {
            if (browseMode == MODE_DIR) loadDir(label);
            else pick(label);
            return;
        }
        if (label.equals(".. ↖")) {
            File parent = new File(curBrowseDir).getParentFile();
            if (parent != null) loadDir(parent.getAbsolutePath());
            return;
        }
        boolean isDir = label.endsWith("/");
        String name = isDir ? label.substring(0, label.length() - 1) : label;
        String full = curBrowseDir.endsWith("/") ? curBrowseDir + name : curBrowseDir + "/" + name;
        if (isDir) {
            loadDir(full);
        } else if (browseMode == MODE_FILE) {
            pick(full);
        }
    }

    // 关键字搜索：在当前目录树下用 root find 递归匹配文件名
    private void doSearch() {
        final String kw = etSearch.getText().toString().trim();
        if (kw.isEmpty()) { Toast.makeText(this, "请输入搜索关键字", Toast.LENGTH_SHORT).show(); return; }
        final String base = curBrowseDir;
        new Thread(() -> {
            List<String> rows = new ArrayList<>();
            String err = null;
            try {
                String kwS = kw.replace("'", "").replace(";", "").replace("`", "").replace("\"", "");
                String cmd;
                if (browseMode == MODE_DIR) {
                    cmd = "find " + q(base) + " -maxdepth 6 -type d -iname '*" + kwS + "*' 2>/dev/null";
                } else {
                    cmd = "find " + q(base) + " -maxdepth 6 -type f -iname '*" + kwS + "*zip' 2>/dev/null";
                }
                String out = execSuOut(cmd);
                for (String line : out.split("\n")) {
                    line = line.trim();
                    if (!line.isEmpty()) rows.add(line);
                }
            } catch (Exception e) {
                err = e.getMessage();
            }
            final List<String> finalRows = rows;
            final String finErr = err;
            runOnUiThread(() -> {
                if (finErr != null) { Toast.makeText(MainActivity.this, "搜索失败: " + finErr, Toast.LENGTH_SHORT).show(); return; }
                isSearchResult = true;
                lvBrowser.setAdapter(new ArrayAdapter<>(MainActivity.this, android.R.layout.simple_list_item_1, finalRows));
                if (finalRows.isEmpty()) {
                    tvPath.setText("搜索 '" + kw + "' 无结果");
                } else {
                    tvPath.setText("搜索 '" + kw + "': " + finalRows.size() + " 条（点击进入/选中）");
                }
            });
        }).start();
    }

    private void loadDir(final String dir) {
        isSearchResult = false;
        curBrowseDir = dir;
        tvPath.setText(dir);
        new Thread(() -> {
            List<String> items = new ArrayList<>();
            String err = null;
            try {
                String out = execSuOut("ls -Ap " + q(dir));
                for (String line : out.split("\n")) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    boolean isDir = line.endsWith("/");
                    String name = isDir ? line.substring(0, line.length() - 1) : line;
                    if (name.equals(".") || name.equals("..")) continue;
                    items.add(isDir ? name + "/" : name);
                }
            } catch (Exception e) {
                err = e.getMessage();
            }
            final List<String> finalItems = items;
            final String finalErr = err;
            runOnUiThread(() -> {
                List<String> rows = new ArrayList<>();
                if (new File(dir).getParentFile() != null) rows.add(".. ↖");
                rows.addAll(finalItems);
                lvBrowser.setAdapter(new ArrayAdapter<>(MainActivity.this, android.R.layout.simple_list_item_1, rows));
                if (finalErr != null) Toast.makeText(MainActivity.this, "无法读取: " + finalErr, Toast.LENGTH_SHORT).show();
            });
        }).start();
    }

    private void log(final String s) { runOnUiThread(() -> tvLog.append(s + "\n")); }

    private interface ThrowingRunnable { void run() throws Exception; }

    private void runTask(final ThrowingRunnable r) {
        new Thread(() -> {
            try {
                if (!isRoot()) { log("⚠️ 未获得 root，请先授予 su 权限！"); return; }
                r.run();
            } catch (Throwable t) {
                Log.e("PatchTool", "err", t);
                log("错误: " + t.getMessage());
            }
        }).start();
    }

    private boolean isRoot() {
        try {
            Process p = execSu("id");
            String out = readAll(p.getInputStream());
            p.waitFor();
            return out != null && out.contains("uid=0");
        } catch (Throwable t) { return false; }
    }

    private Process execSu(String shell) throws Exception {
        return Runtime.getRuntime().exec(new String[]{"su", "-c", shell});
    }

    private String execSuOut(String shell) throws Exception {
        Process p = execSu(shell);
        String o = readAll(p.getInputStream());
        String e = readAll(p.getErrorStream());
        int code = p.waitFor();
        if (code != 0) throw new RuntimeException("su 退出码 " + code + " | " + e);
        return o;
    }

    private String readAll(InputStream is) throws Exception {
        BufferedReader br = new BufferedReader(new InputStreamReader(is));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line).append("\n");
        return sb.toString();
    }

    private String q(String s) { return "'" + s.replace("'", "'\\''") + "'"; }

    // 保证 zip 可被 Java 读取：若在受保护区(Android/data等)，先 root 拷到 cache
    private String workZip(String zip) throws Exception {
        boolean readable = false;
        try (ZipFile z = new ZipFile(zip)) { readable = true; }
        catch (Throwable t) { readable = false; }
        if (readable) return zip;
        File dest = new File(getCacheDir(), "pull.zip");
        if (dest.exists()) dest.delete();
        execSuOut("cp -f " + q(zip) + " " + q(dest.getAbsolutePath()));
        return dest.getAbsolutePath();
    }

    // ---------- ① 解析源 ----------
    private void doParse() throws Exception {
        String src = etSrc.getText().toString().trim();
        if (src.isEmpty()) { log("请先填写源路径"); return; }
        String zip = resolveZip(src);
        if (zip == null) { log("未找到 zip"); return; }
        log("解析 zip: " + zip);
        String wrk = workZip(zip);
        List<String> top = unzipStructure(wrk);
        log("zip 顶层条目: " + top);
    }

    private String resolveZip(String src) throws Exception {
        if (src.toLowerCase().endsWith(".zip")) return src;
        return findZipInDir(src);
    }

    private String findZipInDir(String dir) throws Exception {
        String out = execSuOut("ls -1t " + q(dir) + "/*.zip 2>/dev/null | head -n1");
        out = out.trim();
        return out.isEmpty() ? null : out;
    }

    private List<String> unzipStructure(String zipPath) throws Exception {
        File dst = new File(getCacheDir(), "ziproot");
        deleteRecursive(dst);
        dst.mkdirs();
        try (ZipFile zf = new ZipFile(zipPath)) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                File out = new File(dst, e.getName());
                if (e.isDirectory()) { out.mkdirs(); continue; }
                File parent = out.getParentFile();
                if (parent != null) parent.mkdirs();
                try (InputStream is = zf.getInputStream(e); FileOutputStream fos = new FileOutputStream(out)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = is.read(buf)) > 0) fos.write(buf, 0, n);
                }
            }
        }
        File filesRoot = new File(dst, "files");
        if (!filesRoot.isDirectory()) { log("警告: zip 内未发现 files/ 根目录"); }
        List<String> top = new ArrayList<>();
        File[] children = filesRoot.isDirectory() ? filesRoot.listFiles() : null;
        if (children != null) for (File c : children) top.add(c.getName());
        return top;
    }

    // 解析 zip 内 files/ 根下的顶层覆盖项（不做真实解压，只读 zip 索引）
    private List<String> zipTopLevel(String zip) throws Exception {
        List<String> top = new ArrayList<>();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        try (ZipFile zf = new ZipFile(zip)) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                String n = en.nextElement().getName();
                if (n.startsWith("files/")) {
                    String rest = n.substring("files/".length());
                    int slash = rest.indexOf('/');
                    String first = slash >= 0 ? rest.substring(0, slash) : rest;
                    if (!first.isEmpty()) seen.add(first);
                }
            }
        }
        top.addAll(seen);
        return top;
    }

    // 只备份"将被 zip 覆盖"的顶层项到带时间戳的备份目录，并逐项打日志
    private String backupCovered(String tgt, List<String> top) throws Exception {
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String backupDir = "/sdcard/PatchTool_backup/" + ts;
        execSuOut("mkdir -p " + q(backupDir));
        int backed = 0;
        for (String entry : top) {
            String full = tgt.endsWith("/") ? tgt + entry : tgt + "/" + entry;
            boolean exists = execSuOut("test -e " + q(full) + " && echo 1").trim().equals("1");
            if (exists) {
                execSuOut("cp -rf " + q(full) + " " + q(backupDir + "/"));
                String size = execSuOut("du -sh " + q(full) + " 2>/dev/null | cut -f1").trim();
                log("  · 备份 " + entry + " (" + size + ")");
                backed++;
            } else {
                log("  · 跳过(目标无此文件) " + entry);
            }
        }
        log("备份目录: " + backupDir + "  (已备份 " + backed + " 项)");
        return backupDir;
    }

    // ---------- ② 替换 ----------
    private void doApply() throws Exception {
        String src = etSrc.getText().toString().trim();
        String tgt = etTgt.getText().toString().trim();
        if (src.isEmpty() || tgt.isEmpty()) { log("请填写源/目标"); return; }
        String gfiles = gameFiles(tgt);
        log("游戏 files 目录: " + gfiles);
        String zip = resolveZip(src);
        if (zip == null) { log("未找到 zip"); return; }
        log("解析覆盖项...");
        String wrk = workZip(zip);
        List<String> top = zipTopLevel(wrk);
        log("zip 将覆盖的顶层项: " + top);
        String backupRoot = "/sdcard/PatchTool_backup";
        boolean hasBackup = execSuOut("test -d " + q(backupRoot) + " && ls -1A " + q(backupRoot) + " 2>/dev/null | head -n1").trim().length() > 0;
        if (!hasBackup) {
            log("① 首次替换：备份原版覆盖项...");
            backupCovered(gfiles, top);
        } else {
            log("① 已有原版备份，跳过备份（避免把已替换文件备份成原版）");
        }
        log("② 解压替换中...");
        unzipStructure(wrk);
        File filesRoot = new File(new File(getCacheDir(), "ziproot"), "files");
        if (!filesRoot.isDirectory()) { log("未发现 files/ 根，已中止"); return; }
        execSuOut("mkdir -p " + q(gfiles));
        execSuOut("cp -rf " + q(filesRoot.getAbsolutePath()) + "/. " + q(gfiles) + "/");
        log("✅ 替换完成");
    }

    // ---------- 还原 ----------
    private void doRestore() throws Exception {
        String tgt = etTgt.getText().toString().trim();
        if (tgt.isEmpty()) { log("请填写目标目录"); return; }
        String gfiles = gameFiles(tgt);
        String bp = "/sdcard/PatchTool_backup";
        String latest = execSuOut("ls -1dt " + q(bp) + "/* 2>/dev/null | head -n1").trim();
        if (latest.isEmpty()) { log("无可用备份"); return; }
        log("还原自: " + latest);
        execSuOut("cp -rf " + q(latest) + "/. " + q(gfiles) + "/");
        log("✅ 还原完成");
    }

    private void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] ch = f.listFiles();
            if (ch != null) for (File c : ch) deleteRecursive(c);
        }
        f.delete();
    }
}
