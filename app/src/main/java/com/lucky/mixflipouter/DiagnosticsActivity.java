package com.lucky.mixflipouter;

import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.shape.ShapeAppearanceModel;
import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

public final class DiagnosticsActivity extends Activity {
    private static final int REQUEST_EXPORT = 4101;
    private static final String EXTRA_SCROLL_BOTTOM = "debug_scroll_bottom";
    private static final String STATE_REPORT = "report_json";
    private static final String STATE_PENDING_EXPORT = "pending_export_json";
    private static final String STATE_EXPORT_TYPE = "pending_export_type";
    private static final int REQUEST_EXPORT_LOGS = 4102;
    private static final int REQUEST_EXPORT_PACKAGE = 4103;
    private static final int REQUEST_SHARE_LOGS = 4104;
    private static final String STATE_LOG_EXPORT_ZIP = "log_export_zip";
    private static final String STATE_DIAGNOSTIC_EXPORT_ZIP = "diagnostic_export_zip";
    private static final String STATE_LOG_DELIVERY_REQUEST = "log_delivery_request";
    private static final String LOG_ARCHIVE_PATTERN =
            "MixFlip-Logs-\\d{8}-\\d{6}(?:-[A-Za-z0-9-]{1,40})?\\.zip";
    private static final String DIAGNOSTIC_ARCHIVE_PATTERN =
            "mixflip-diagnostics-\\d{8}-\\d{6}(?:-[A-Za-z0-9-]{1,40})?\\.zip";
    private static final ConcurrentHashMap<String, ActiveArchiveLease> ACTIVE_ARCHIVE_LEASES =
            new ConcurrentHashMap<>();
    private WidgetRepository repository;
    private TextView status;
    private LinearLayout sections;
    private MaterialButton export;
    private MaterialButton exportLogs;
    private MaterialButton exportPackage;
    private ProgressBar logExportProgress;
    private ScrollView scroll;
    private JSONObject currentReport;
    private String pendingExportJson;
    private String pendingExportType;
    private File readyLogArchive;
    private File readyDiagnosticArchive;
    private int activeLogDeliveryRequest;
    private boolean logExportBusy;
    private String logExportDeliveryError;
    private static final String[] LOG_LEVEL_VALUES = {"ALL", "DEBUG", "INFO", "WARN", "ERROR"};

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        repository = new WidgetRepository(this);
        if (state != null) {
            pendingExportJson = state.getString(STATE_PENDING_EXPORT);
            pendingExportType = state.getString(STATE_EXPORT_TYPE);
            String savedArchive = state.getString(STATE_LOG_EXPORT_ZIP);
            if (savedArchive != null) {
                File candidate = new File(savedArchive);
                if (isValidArchive(candidate, LOG_ARCHIVE_PATTERN)) readyLogArchive = candidate;
            }
            String savedDiagnostic = state.getString(STATE_DIAGNOSTIC_EXPORT_ZIP);
            if (savedDiagnostic != null) {
                File candidate = new File(savedDiagnostic);
                if (isValidArchive(candidate, DIAGNOSTIC_ARCHIVE_PATTERN)) {
                    readyDiagnosticArchive = candidate;
                }
            }
            activeLogDeliveryRequest = state.getInt(STATE_LOG_DELIVERY_REQUEST, 0);
            try {
                String saved = state.getString(STATE_REPORT);
                if (saved != null) currentReport = new JSONObject(saved);
            } catch (Throwable ignored) {
            }
        }
        setContentView(createContent());
        SystemBars.apply(this);
        cleanupExpiredExports();
        if (readyLogArchive == null) readyLogArchive = findLatestArchive(
                getLogExportDirectory(), LOG_ARCHIVE_PATTERN);
        if (readyDiagnosticArchive == null) readyDiagnosticArchive = findLatestArchive(
                getDiagnosticExportDirectory(), DIAGNOSTIC_ARCHIVE_PATTERN);
        if (activeLogDeliveryRequest != 0) {
            setLogExportBusy(true, "正在完成文件交付…");
        }
        if (currentReport == null) refresh();
        else render(currentReport);
    }

    private View createContent() {
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(color(com.google.android.material.R.attr.colorSurface));
        LinearLayout root = vertical(0);
        root.setPadding(dp(20), dp(16), dp(20), dp(40));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));

        MaterialButton back = textButton("返回", v -> finish());
        root.addView(back, new LinearLayout.LayoutParams(-2, -2));
        TextView title = text("诊断", 30,
                color(com.google.android.material.R.attr.colorOnSurface));
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title, marginTop(dp(4)));
        TextView subtitle = text("检查外屏链路，并生成不含用户内容的诊断报告", 14,
                color(com.google.android.material.R.attr.colorOnSurfaceVariant));
        root.addView(subtitle, marginTop(dp(4)));

        LinearLayout actions = horizontal();
        actions.addView(outlinedButton("刷新", v -> refresh()), weighted());
        export = outlinedButton("导出 JSON", v -> beginExport());
        export.setEnabled(currentReport != null);
        LinearLayout.LayoutParams exportParams = weighted();
        exportParams.setMarginStart(dp(8));
        actions.addView(export, exportParams);
        root.addView(actions, marginTop(dp(18)));

        LinearLayout logActions = horizontal();
        MaterialButton viewLogs = outlinedButton("运行日志", v -> showLogs());
        logActions.addView(viewLogs, weighted());
        exportLogs = outlinedButton("导出日志", v -> beginLogExport());
        LinearLayout.LayoutParams logExportParams = weighted();
        logExportParams.setMarginStart(dp(8));
        logActions.addView(exportLogs, logExportParams);
        exportPackage = outlinedButton("导出诊断包", v -> beginPackageExport());
        LinearLayout.LayoutParams packageParams = weighted();
        packageParams.setMarginStart(dp(8));
        logActions.addView(exportPackage, packageParams);
        root.addView(logActions, marginTop(dp(8)));

        status = text("正在生成诊断快照…", 13,
                color(com.google.android.material.R.attr.colorOnSurfaceVariant));
        root.addView(status, marginTop(dp(12)));
        logExportProgress = new ProgressBar(this);
        logExportProgress.setIndeterminate(true);
        logExportProgress.setVisibility(View.GONE);
        root.addView(logExportProgress, marginTop(dp(8)));
        sections = vertical(0);
        root.addView(sections, marginTop(dp(12)));
        return scroll;
    }

    private void refresh() {
        status.setText("正在生成诊断快照…");
        export.setEnabled(false);
        new Thread(() -> {
            try {
                JSONObject report = DiagnosticReport.collect(this, repository);
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    currentReport = report;
                    export.setEnabled(true);
                    render(report);
                });
            } catch (Throwable error) {
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    status.setText("诊断生成失败：" + safeMessage(error));
                });
            }
        }, "diagnostics-refresh").start();
    }

    private void render(JSONObject report) {
        sections.removeAllViews();
        status.setText("报告格式 v" + report.optInt("schemaVersion", 0)
                + " · " + report.optString("generatedAt", ""));

        JSONObject device = report.optJSONObject("device");
        JSONObject app = report.optJSONObject("app");
        addSection("设备与版本",
                line("模块", value(app, "versionName"), true)
                        + line("设备", value(device, "manufacturer") + " "
                        + value(device, "model"), true)
                        + line("系统", "Android " + intValue(device, "sdk") + " · "
                        + value(device, "buildDisplay"), true)
                        + line("用户数据", bool(device, "userUnlocked") ? "已解锁" : "等待首次解锁",
                        bool(device, "userUnlocked")));

        JSONObject permissions = report.optJSONObject("permissions");
        addSection("权限与服务",
                stateLine("相机 / 手电筒", bool(permissions, "camera"))
                        + stateLine("媒体通知访问", bool(permissions, "notificationListener"))
                        + stateLine("媒体监听已绑定", bool(permissions, "notificationListenerConnected"))
                        + stateLine("勿扰模式访问", bool(permissions, "notificationPolicy"))
                        + stateLine("修改系统设置", bool(permissions, "writeSettings")));

        JSONObject environment = report.optJSONObject("environment");
        addSection("运行环境",
                line("Root", "su_binary_visible".equals(value(environment, "rootIndicator"))
                                ? "应用可见 su" : "应用侧无法判断", true)
                        + stateLine("LSPosed Hook 上报证据", bool(environment, "lsposedHookEvidence")));

        JSONObject hooks = report.optJSONObject("hooks");
        addSection("Hook 健康",
                stateLine("Provider", bool(hooks, "providerAvailable"))
                        + hookLine(hooks, "compatibility", "兼容检查")
                        + hookLine(hooks, "catalogue", "系统列表")
                        + hookLine(hooks, "runtime", "外屏运行时")
                        + hookLine(hooks, "live_refresh", "即时刷新")
                        + hookLine(hooks, "lyrics", "歌词数据接入")
                        + hookLine(hooks, "qs", "高级磁贴适配"));
        addSection("Hook 安装事件", summarizeHookEvents(report.optJSONArray("hookStatus")));

        JSONObject packages = report.optJSONObject("packages");
        JSONArray processes = report.optJSONArray("moduleProcesses");
        addSection("模块进程加载回报",
                processObservation(processes, packages, "com.miui.fliphome", "flipHome", "FlipHome")
                        + processObservation(processes, packages, "android", null, "system_server")
                        + processObservation(processes, packages, "com.tencent.wetype", "wechatIme", "微信输入法")
                        + processObservation(processes, packages, "com.bytedance.android.doubaoime", "doubaoIme", "豆包输入法")
                        + processObservation(processes, packages, "com.android.systemui", "systemUi", "SystemUI")
                        + processObservation(processes, packages, "com.miui.voiceassist", "assistant", "小爱语音助手")
                        + processObservation(processes, packages, "com.netease.cloudmusic", "netease", "网易云音乐")
                        + processObservation(processes, packages, "com.miui.home", "launcher", "系统桌面")
                        + processObservation(processes, packages, "com.miui.personalassistant", "personalAssistant", "系统小部件面板"));

        JSONObject logging = report.optJSONObject("runtimeLogging");
        JSONArray logFiles = logging == null ? null : logging.optJSONArray("fileList");
        addSection("运行日志", stateLine("本地日志接收端", bool(logging, "providerAvailable"))
                + line("日志文件", logFiles == null ? "0 个" : logFiles.length() + " 个", logFiles != null)
                + line("队列丢弃", longValue(logging, "dropped") < 0 ? "未知"
                        : Long.toString(longValue(logging, "dropped")),
                        longValue(logging, "dropped") == 0)
                + line("进程队列丢弃", longValue(logging, "clientQueueDrops") < 0 ? "未知"
                        : Long.toString(longValue(logging, "clientQueueDrops")),
                        longValue(logging, "clientQueueDrops") == 0)
                + line("写入失败", longValue(logging, "writeFailures") < 0 ? "未知"
                        : Long.toString(longValue(logging, "writeFailures")),
                        longValue(logging, "writeFailures") == 0)
                + line("持久后备待补写", longValue(logging, "fallbackPending") < 0 ? "未知"
                        : longValue(logging, "fallbackPending") + " 条",
                        longValue(logging, "fallbackPending") == 0)
                + line("最终丢失", longValue(logging, "fallbackLossCount") < 0 ? "未知"
                        : longValue(logging, "fallbackLossCount") + " 条"
                        + (value(logging, "fallbackLastLossReason").isEmpty() ? ""
                        : "（" + value(logging, "fallbackLastLossReason") + "）"),
                        longValue(logging, "fallbackLossCount") == 0)
                + line("折叠输入法替换", imeReplacementObservation(logging),
                        isImeReplacementKnown(logging)
                                && !bool(logging, "imeReplacementEnabled"))
                + line("本次启动回报", "system_server "
                        + scopeObservation(logging, "imeScopeSystemThisBoot")
                        + " · 微信 " + scopeObservation(logging, "imeScopeWeChatThisBoot")
                        + " · 豆包 " + scopeObservation(logging, "imeScopeDoubaoThisBoot"), true));

        JSONObject widgets = report.optJSONObject("widgets");
        addSection("Widget 仓库",
                line("数量", intValue(widgets, "enabledCount") + " / "
                        + intValue(widgets, "count") + " 已启用", true)
                        + line("组件", intValue(widgets, "componentCount") + " 个", true)
                        + line("配置架构", "v" + intValue(widgets, "schemaVersion"), true)
                        + line("安全模式", bool(widgets, "safeMode") ? "已开启" : "未开启",
                        !bool(widgets, "safeMode")));

        JSONObject playback = report.optJSONObject("playback");
        JSONObject lyrics = report.optJSONObject("lyrics");
        boolean superLyricInstalled = bool(lyrics, "superlyricInstalled");
        boolean superLyricCompatible = bool(lyrics, "superlyricCompatible");
        boolean superLyricReceiverRegistered = bool(lyrics, "superlyricReceiverRegistered");
        String superLyricStatus = !superLyricInstalled ? "未安装"
                : !superLyricCompatible ? "API 不兼容"
                : superLyricReceiverRegistered ? "接收器已注册" : "未注册（按需连接）";
        String source = sourceLabel(value(lyrics, "source"));
        String lastLyricTime = formatTimestamp(longValue(lyrics, "publishedAtEpochMs"));
        addSection("媒体与歌词",
                stateLine("活动媒体会话", bool(playback, "sessionAvailable"))
                        + stateLine("专辑封面", bool(playback, "artworkAvailable"))
                        + line("SuperLyric", superLyricStatus,
                        superLyricReceiverRegistered)
                        + line("SuperLyric API", value(lyrics, "superlyricCompatibilityLabel"),
                        superLyricCompatible)
                        + line("兼容模式", bool(lyrics, "compatModeEnabled") ? "已开启" : "未开启",
                        bool(lyrics, "compatModeEnabled"))
                        + line("服务状态", value(lyrics, "superlyricConnectionMessage"),
                        superLyricReceiverRegistered)
                        + line("当前播放器", value(playback, "sourcePackage"),
                        bool(playback, "sessionAvailable"))
                        + line("歌词来源", source, !source.isEmpty())
                        + line("歌词发布者", value(lyrics, "publisher"),
                        !value(lyrics, "publisher").isEmpty())
                        + stateLine("歌曲匹配", "matched".equals(value(lyrics, "matchStatus")))
                        + line("歌词行数", Integer.toString(intValue(lyrics, "lineCount")),
                        intValue(lyrics, "lineCount") > 0)
                        + line("整首歌词（在线补齐）",
                        intValue(lyrics, "contextLineCount") > 0
                                ? intValue(lyrics, "contextLineCount") + " 行" : "未获取",
                        intValue(lyrics, "contextLineCount") > 0)
                        + line("最后收到歌词", lastLyricTime, !"暂无".equals(lastLyricTime))
                        + line("网易云 fallback",
                        bool(lyrics, "legacyFallback") ? "正在使用" : "未使用", true));

        JSONObject qs = report.optJSONObject("quickSettings");
        addSection("可选高级磁贴",
                stateLine("SystemUI 桥接", bool(qs, "bridgeReady"))
                        + line("活动磁贴快照", intValue(qs, "availableTileCount") + " / "
                        + intValue(qs, "tileCount") + " 可用", true));
        if (getIntent().getBooleanExtra(EXTRA_SCROLL_BOTTOM, false)) {
            scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        }
    }

    private void addSection(String titleValue, String detail) {
        MaterialCardView card = new MaterialCardView(this);
        card.setCardElevation(0);
        card.setStrokeWidth(dp(1));
        card.setStrokeColor(color(com.google.android.material.R.attr.colorOutlineVariant));
        card.setCardBackgroundColor(color(
                com.google.android.material.R.attr.colorSurfaceContainerLow));
        card.setShapeAppearanceModel(ShapeAppearanceModel.builder()
                .setAllCornerSizes(dp(8)).build());
        LinearLayout body = vertical(dp(16));
        TextView title = text(titleValue, 17,
                color(com.google.android.material.R.attr.colorOnSurface));
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        body.addView(title);
        TextView details = text(detail.trim(), 14,
                color(com.google.android.material.R.attr.colorOnSurfaceVariant));
        details.setLineSpacing(dp(3), 1f);
        body.addView(details, marginTop(dp(8)));
        card.addView(body);
        sections.addView(card, cardMargin());
    }

    private void beginExport() {
        if (currentReport == null) return;
        try {
            pendingExportJson = currentReport.toString(2);
        } catch (Throwable error) {
            Toast.makeText(this, "无法准备诊断报告：" + safeMessage(error),
                    Toast.LENGTH_LONG).show();
            return;
        }
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        pendingExportType = "report";
        launchDocumentPicker(REQUEST_EXPORT, "application/json",
                "mixflip-diagnostics-" + stamp + ".json");
    }

    private void beginLogExport() {
        if (logExportBusy) return;
        cleanupExpiredExports();
        if (readyLogArchive != null && readyLogArchive.isFile()) {
            showLogArchiveReady();
            return;
        }
        setLogExportBusy(true, "正在整理全部运行日志并生成 ZIP…");
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        File exportDirectory = getLogExportDirectory();
        String suffix = java.util.UUID.randomUUID().toString().substring(0, 8);
        File archive = new File(exportDirectory, "MixFlip-Logs-" + stamp + "-" + suffix + ".zip");
        File partialArchive = new File(exportDirectory, archive.getName() + ".part");
        new Thread(() -> {
            String exportId = null;
            try {
                String flushWarning = ModuleLogger.flush(8000L)
                        ? "" : "一个或多个进程的待发送队列未能在等待时间内排空";
                Bundle snapshot = loggerCallRequired("begin_log_export", null);
                exportId = snapshot.getString("exportId");
                String snapshotPath = snapshot.getString("snapshotPath");
                if (exportId == null || snapshotPath == null) {
                    throw new IOException("日志服务未返回有效快照");
                }
                String providerWarning = snapshot.getString("flushWarning", "");
                if (!providerWarning.isEmpty()) {
                    flushWarning = flushWarning.isEmpty() ? providerWarning
                            : flushWarning + "; " + providerWarning;
                }
                JSONArray fileMetadata = new JSONArray(snapshot.getString("files", "[]"));
                String version = BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")";
                LogArchiveExporter.write(partialArchive, new File(snapshotPath), fileMetadata,
                        version, flushWarning);
                if (!partialArchive.renameTo(archive)) {
                    throw new IOException("无法完成日志 ZIP 文件写入");
                }
                runOnUiThread(() -> {
                    if (isDestroyed()) {
                        return;
                    }
                    readyLogArchive = archive;
                    logExportDeliveryError = null;
                    setLogExportBusy(false, getString(R.string.log_export_ready));
                    showLogArchiveReady();
                });
            } catch (Throwable error) {
                partialArchive.delete();
                archive.delete();
                ModuleLogger.logException("Runtime log ZIP export failed", error);
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    setLogExportBusy(false, "日志导出失败：" + safeMessage(error));
                    new MaterialAlertDialogBuilder(this).setTitle("日志导出失败")
                            .setMessage(safeMessage(error)).setPositiveButton("确定", null).show();
                });
            } finally {
                if (exportId != null) {
                    Bundle end = new Bundle();
                    end.putString("exportId", exportId);
                    try { loggerCallRequired("end_log_export", end); }
                    catch (Throwable ignored) { }
                }
            }
        }, "diagnostics-log-zip").start();
    }

    private void setLogExportBusy(boolean busy, String message) {
        logExportBusy = busy;
        if (exportLogs != null) exportLogs.setEnabled(!busy);
        if (exportPackage != null) exportPackage.setEnabled(!busy);
        if (logExportProgress != null) {
            logExportProgress.setVisibility(busy ? View.VISIBLE : View.GONE);
        }
        if (status != null && message != null) status.setText(message);
    }

    private File getLogExportDirectory() { return new File(getFilesDir(), "log_exports"); }

    private File getDiagnosticExportDirectory() {
        return new File(getFilesDir(), "diagnostic_exports");
    }

    private void cleanupExpiredExports() {
        cleanupExportDirectory(getLogExportDirectory(), LOG_ARCHIVE_PATTERN);
        cleanupExportDirectory(getDiagnosticExportDirectory(), DIAGNOSTIC_ARCHIVE_PATTERN);
    }

    private void cleanupExportDirectory(File directory, String pattern) {
        File[] files = directory.listFiles();
        if (files == null) return;
        long now = System.currentTimeMillis();
        for (File file : files) {
            if (file.getName().endsWith(".lease")) {
                if (LogArchiveLifecycle.isExpired(file.lastModified(), now, 0,
                        isArchiveLeaseActive(file))) file.delete();
                continue;
            }
            if (file.getName().endsWith(".zip.part")) {
                File lease = leaseFor(file);
                boolean leaseActive = lease.exists() && isArchiveLeaseActive(lease);
                if (LogArchiveLifecycle.isExpired(file.lastModified(), now,
                        lease.lastModified(), leaseActive)) {
                    file.delete();
                    lease.delete();
                }
                continue;
            }
            if (!file.isFile() || !file.getName().matches(pattern)) continue;
            File lease = leaseFor(file);
            boolean leaseActive = lease.exists() && isArchiveLeaseActive(lease);
            if (LogArchiveLifecycle.isExpired(file.lastModified(), now,
                    lease.lastModified(), leaseActive)) {
                file.delete();
                lease.delete();
            }
        }
    }

    private File findLatestArchive(File directory, String pattern) {
        File[] files = directory.listFiles();
        if (files == null) return null;
        File latest = null;
        for (File file : files) {
            if (file.isFile() && file.getName().matches(pattern)
                    && (latest == null || file.lastModified() > latest.lastModified())) {
                latest = file;
            }
        }
        return latest;
    }

    private static boolean isValidArchive(File file, String pattern) {
        return file != null && file.isFile() && file.getName().matches(pattern);
    }

    private static File leaseFor(File archive) {
        return new File(archive.getAbsolutePath() + ".lease");
    }

    private void markArchiveInUse(File archive) {
        if (archive == null) return;
        long now = System.currentTimeMillis();
        archive.setLastModified(now);
        File lease = leaseFor(archive);
        String key = lease.getAbsolutePath();
        ActiveArchiveLease existing = ACTIVE_ARCHIVE_LEASES.get(key);
        if (existing != null && existing.lock.isValid()) {
            lease.setLastModified(now);
            return;
        }
        try {
            RandomAccessFile random = new RandomAccessFile(lease, "rw");
            FileChannel channel = random.getChannel();
            FileLock lock = channel.tryLock();
            if (lock == null) {
                random.close();
                lease.setLastModified(now);
                return;
            }
            ActiveArchiveLease active = new ActiveArchiveLease(random, channel, lock);
            ActiveArchiveLease previous = ACTIVE_ARCHIVE_LEASES.putIfAbsent(key, active);
            if (previous != null) active.close();
            try (FileOutputStream output = new FileOutputStream(lease, false)) {
            output.write(Long.toString(now).getBytes(StandardCharsets.US_ASCII));
            output.getFD().sync();
            }
        } catch (Throwable error) {
            ModuleLogger.warn("Could not create diagnostic ZIP retention lease: "
                    + error.getClass().getSimpleName());
        }
    }

    private void releaseArchiveLease(File archive, boolean retain) {
        if (archive == null) return;
        File lease = leaseFor(archive);
        ActiveArchiveLease active = ACTIVE_ARCHIVE_LEASES.remove(lease.getAbsolutePath());
        if (active != null) active.close();
        if (retain && archive.exists()) archive.setLastModified(System.currentTimeMillis());
        lease.delete();
    }

    private static boolean isArchiveLeaseActive(File lease) {
        ActiveArchiveLease active = ACTIVE_ARCHIVE_LEASES.get(lease.getAbsolutePath());
        if (active != null && active.lock.isValid()) return true;
        try (RandomAccessFile random = new RandomAccessFile(lease, "rw");
             FileChannel channel = random.getChannel()) {
            FileLock probe = channel.tryLock();
            if (probe == null) return true;
            probe.release();
            return false;
        } catch (java.nio.channels.OverlappingFileLockException inUse) {
            return true;
        } catch (Throwable unavailable) {
            return true;
        }
    }

    private static final class ActiveArchiveLease {
        final RandomAccessFile random;
        final FileChannel channel;
        final FileLock lock;
        ActiveArchiveLease(RandomAccessFile random, FileChannel channel, FileLock lock) {
            this.random = random;
            this.channel = channel;
            this.lock = lock;
        }
        void close() {
            try { lock.release(); } catch (Throwable ignored) { }
            try { channel.close(); } catch (Throwable ignored) { }
            try { random.close(); } catch (Throwable ignored) { }
        }
    }

    private void showLogArchiveReady() {
        if (readyLogArchive == null || !readyLogArchive.isFile()) {
            setLogExportBusy(false, "日志 ZIP 不存在，请重新导出");
            return;
        }
        String message = "已打包全部可读取的 JSONL 日志和 manifest.json。";
        if (logExportDeliveryError != null) message += "\n\n" + logExportDeliveryError;
        new MaterialAlertDialogBuilder(this).setTitle("日志 ZIP 已就绪")
                .setMessage(message)
                .setPositiveButton("保存到…", (dialog, which) -> launchLogSavePicker())
                .setNeutralButton("分享…", (dialog, which) -> shareLogArchive())
                .setNegativeButton("完成", (dialog, which) ->
                        finishLogDelivery("日志 ZIP 已保留 24 小时，可再次尝试保存或分享",
                                LogArchiveLifecycle.Outcome.FLOW_CLOSED))
                .setCancelable(false).show();
    }

    private void launchLogSavePicker() {
        if (readyLogArchive == null) return;
        try {
            activeLogDeliveryRequest = REQUEST_EXPORT_LOGS;
            markArchiveInUse(readyLogArchive);
            setLogExportBusy(true, "请选择 ZIP 保存位置…");
            startActivityForResult(createLogSaveIntent(readyLogArchive.getName()),
                    REQUEST_EXPORT_LOGS);
        } catch (Throwable error) {
            activeLogDeliveryRequest = 0;
            releaseArchiveLease(readyLogArchive, true);
            logExportDeliveryError = "无法打开系统文件选择器：" + safeMessage(error);
            setLogExportBusy(false, null);
            showLogArchiveReady();
        }
    }

    private void saveLogArchive(Uri destination) {
        if (readyLogArchive == null || !readyLogArchive.isFile()) {
            finishLogDelivery("日志 ZIP 不存在，请重新导出",
                    LogArchiveLifecycle.Outcome.DELIVERY_FAILED);
            return;
        }
        setLogExportBusy(true, "正在保存日志 ZIP…");
        File archive = readyLogArchive;
        new Thread(() -> {
            try (InputStream input = new FileInputStream(archive);
                 OutputStream output = getContentResolver().openOutputStream(destination, "w")) {
                if (output == null) throw new IOException("系统未能打开所选文件");
                byte[] buffer = new byte[32 * 1024];
                int count;
                while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
                output.flush();
                runOnUiThread(() -> {
                    Toast.makeText(this, "日志 ZIP 已保存", Toast.LENGTH_LONG).show();
                    finishLogDelivery("日志 ZIP 已保存",
                            LogArchiveLifecycle.Outcome.SAF_SAVE_SUCCEEDED);
                });
            } catch (Throwable error) {
                ModuleLogger.logException("Saving runtime log ZIP failed", error);
                runOnUiThread(() -> {
                    releaseArchiveLease(archive, true);
                    logExportDeliveryError = "保存失败：" + safeMessage(error);
                    setLogExportBusy(false, null);
                    showLogArchiveReady();
                });
            }
        }, "diagnostics-log-save").start();
    }

    private void shareLogArchive() {
        if (readyLogArchive == null || !readyLogArchive.isFile()) {
            finishLogDelivery("日志 ZIP 不存在，请重新导出",
                    LogArchiveLifecycle.Outcome.DELIVERY_FAILED);
            return;
        }
        try {
            activeLogDeliveryRequest = REQUEST_SHARE_LOGS;
            markArchiveInUse(readyLogArchive);
            setLogExportBusy(true, "正在打开系统分享面板…");
            startActivityForResult(createLogShareIntent(this, readyLogArchive), REQUEST_SHARE_LOGS);
        } catch (Throwable error) {
            activeLogDeliveryRequest = 0;
            releaseArchiveLease(readyLogArchive, true);
            logExportDeliveryError = "无法打开系统分享面板：" + safeMessage(error);
            setLogExportBusy(false, null);
            showLogArchiveReady();
        }
    }

    private void finishLogDelivery(String statusMessage, LogArchiveLifecycle.Outcome outcome) {
        activeLogDeliveryRequest = 0;
        boolean deleteArchive = LogArchiveLifecycle.shouldDelete(outcome);
        if (readyLogArchive != null) {
            File archive = readyLogArchive;
            releaseArchiveLease(archive, !deleteArchive);
            boolean applied = LogArchiveLifecycle.completeDelivery(archive, outcome);
            if (deleteArchive && !applied) {
                statusMessage += "；临时文件未能立即删除，将在后续清理";
            }
            if (deleteArchive && applied) readyLogArchive = null;
        }
        logExportDeliveryError = null;
        setLogExportBusy(false, statusMessage);
    }

    static Intent createLogSaveIntent(String filename) {
        return new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/zip")
                .putExtra(Intent.EXTRA_TITLE, filename)
                .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    }

    static Intent createLogShareIntent(Context context, File archive) throws IOException {
        Uri uri = FileProvider.getUriForFile(context,
                context.getPackageName() + ".fileprovider", archive);
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType("application/zip")
                .putExtra(Intent.EXTRA_STREAM, uri);
        send.setClipData(ClipData.newRawUri("MixFlip runtime logs", uri));
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        Intent chooser = Intent.createChooser(send, "分享 MixFlip 运行日志 ZIP");
        chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return chooser;
    }

    private void beginPackageExport() {
        if (logExportBusy) return;
        cleanupExpiredExports();
        if (readyDiagnosticArchive != null && readyDiagnosticArchive.isFile()) {
            launchDiagnosticSavePicker();
            return;
        }
        if (currentReport == null) return;
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        String suffix = java.util.UUID.randomUUID().toString().substring(0, 8);
        File directory = getDiagnosticExportDirectory();
        File archive = new File(directory, "mixflip-diagnostics-" + stamp + "-" + suffix + ".zip");
        File partial = new File(directory, archive.getName() + ".part");
        String reportSnapshot = currentReport.toString();
        setLogExportBusy(true, "正在生成完整诊断包并固定日志快照…");
        new Thread(() -> {
            String exportId = null;
            try {
                ModuleLogger.flush(8000L);
                loggerCallRequired("flush", null);
                Bundle snapshot = loggerCallRequired("begin_log_export", null);
                exportId = snapshot.getString("exportId");
                String snapshotPath = snapshot.getString("snapshotPath");
                if (exportId == null || snapshotPath == null) {
                    throw new IOException("日志服务未返回完整诊断快照");
                }
                JSONArray files = new JSONArray(snapshot.getString("files", "[]"));
                DiagnosticPackageExporter.write(partial, reportSnapshot,
                        new File(snapshotPath), files);
                if (!partial.renameTo(archive)) {
                    throw new IOException("无法完成完整诊断包文件写入");
                }
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    readyDiagnosticArchive = archive;
                    setLogExportBusy(false, "完整诊断包已生成");
                    launchDiagnosticSavePicker();
                });
            } catch (Throwable error) {
                partial.delete();
                archive.delete();
                ModuleLogger.logException("Complete diagnostic ZIP export failed", error);
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    setLogExportBusy(false, "完整诊断包生成失败：" + safeMessage(error));
                    Toast.makeText(this, "完整诊断包生成失败：" + safeMessage(error),
                            Toast.LENGTH_LONG).show();
                });
            } finally {
                if (exportId != null) {
                    Bundle end = new Bundle();
                    end.putString("exportId", exportId);
                    try { loggerCallRequired("end_log_export", end); }
                    catch (Throwable ignored) { }
                }
            }
        }, "diagnostics-full-package").start();
    }

    private void launchDiagnosticSavePicker() {
        if (readyDiagnosticArchive == null || !readyDiagnosticArchive.isFile()) return;
        try {
            activeLogDeliveryRequest = REQUEST_EXPORT_PACKAGE;
            markArchiveInUse(readyDiagnosticArchive);
            setLogExportBusy(true, "请选择完整诊断包的保存位置…");
            startActivityForResult(createLogSaveIntent(readyDiagnosticArchive.getName()),
                    REQUEST_EXPORT_PACKAGE);
        } catch (Throwable error) {
            activeLogDeliveryRequest = 0;
            releaseArchiveLease(readyDiagnosticArchive, true);
            setLogExportBusy(false, "无法打开系统文件选择器：" + safeMessage(error));
            Toast.makeText(this, "无法打开系统文件选择器：" + safeMessage(error),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void saveDiagnosticArchive(Uri destination) {
        File archive = readyDiagnosticArchive;
        if (archive == null || !archive.isFile()) {
            setLogExportBusy(false, "完整诊断包不存在，请重新生成");
            return;
        }
        setLogExportBusy(true, "正在保存完整诊断包…");
        new Thread(() -> {
            try (InputStream input = new FileInputStream(archive);
                 OutputStream output = getContentResolver().openOutputStream(destination, "w")) {
                if (output == null) throw new IOException("系统未能打开所选文件");
                byte[] buffer = new byte[32 * 1024];
                int count;
                while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
                output.flush();
                runOnUiThread(() -> {
                    releaseArchiveLease(archive, false);
                    archive.delete();
                    if (readyDiagnosticArchive == archive) readyDiagnosticArchive = null;
                    setLogExportBusy(false, "完整诊断包已保存");
                    Toast.makeText(this, "完整诊断包已保存", Toast.LENGTH_LONG).show();
                });
            } catch (Throwable error) {
                ModuleLogger.logException("Saving complete diagnostic ZIP failed", error);
                runOnUiThread(() -> {
                    releaseArchiveLease(archive, true);
                    setLogExportBusy(false, "保存失败：" + safeMessage(error)
                            + "；诊断包仍保留，可再次尝试");
                    Toast.makeText(this, "保存失败：" + safeMessage(error),
                            Toast.LENGTH_LONG).show();
                });
            }
        }, "diagnostics-full-package-save").start();
    }

    private void launchDocumentPicker(int request, String mime, String filename) {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(mime)
                .putExtra(Intent.EXTRA_TITLE, filename)
                .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        startActivityForResult(intent, request);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_EXPORT_LOGS) {
            activeLogDeliveryRequest = 0;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                saveLogArchive(data.getData());
            } else {
                finishLogDelivery("已取消保存；日志 ZIP 保留 24 小时，可重新尝试",
                        LogArchiveLifecycle.Outcome.SAVE_CANCELLED);
            }
            return;
        }
        if (requestCode == REQUEST_SHARE_LOGS) {
            activeLogDeliveryRequest = 0;
            finishLogDelivery("分享面板已关闭；日志 ZIP 保留 24 小时",
                    LogArchiveLifecycle.Outcome.SHARE_CLOSED);
            return;
        }
        if (requestCode == REQUEST_EXPORT_PACKAGE) {
            activeLogDeliveryRequest = 0;
            if (resultCode == RESULT_OK && data != null && data.getData() != null
                    && readyDiagnosticArchive != null && readyDiagnosticArchive.isFile()) {
                saveDiagnosticArchive(data.getData());
            } else {
                releaseArchiveLease(readyDiagnosticArchive, true);
                setLogExportBusy(false, "已取消保存；完整诊断包仍保留，可再次导出或重试");
            }
            return;
        }
        if (requestCode != REQUEST_EXPORT) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null
                || pendingExportType == null) {
            pendingExportJson = null;
            pendingExportType = null;
            return;
        }
        Uri uri = data.getData();
        String snapshot = pendingExportJson;
        String type = pendingExportType;
        pendingExportJson = null;
        pendingExportType = null;
        new Thread(() -> {
            try (OutputStream raw = getContentResolver().openOutputStream(uri, "wt")) {
                OutputStream output = raw;
                if (output == null) throw new IllegalStateException("无法打开目标文件");
                output.write(snapshot.getBytes(StandardCharsets.UTF_8));
                output.flush();
                runOnUiThread(() -> Toast.makeText(this, "诊断报告已导出",
                        Toast.LENGTH_SHORT).show());
            } catch (Throwable error) {
                ModuleLogger.logException("Diagnostics export failed", error);
                runOnUiThread(() -> Toast.makeText(this,
                        "导出失败：" + safeMessage(error), Toast.LENGTH_LONG).show());
            }
        }, "diagnostics-export").start();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (currentReport != null) outState.putString(STATE_REPORT, currentReport.toString());
        outState.putString(STATE_PENDING_EXPORT, pendingExportJson);
        outState.putString(STATE_EXPORT_TYPE, pendingExportType);
        outState.putString(STATE_LOG_EXPORT_ZIP,
                readyLogArchive == null ? null : readyLogArchive.getAbsolutePath());
        outState.putString(STATE_DIAGNOSTIC_EXPORT_ZIP,
                readyDiagnosticArchive == null ? null : readyDiagnosticArchive.getAbsolutePath());
        outState.putInt(STATE_LOG_DELIVERY_REQUEST, activeLogDeliveryRequest);
    }

    private Bundle loggerCall(String method, Bundle extras) {
        try { return getContentResolver().call(LogProvider.URI, method, null, extras); }
        catch (Throwable error) {
            ModuleLogger.logException("Log provider unavailable during " + method, error);
            return null;
        }
    }

    private Bundle loggerCallRequired(String method, Bundle extras) throws IOException {
        Bundle response = getContentResolver().call(LogProvider.URI, method, null, extras);
        if (response == null) throw new IOException("日志服务未返回结果（" + method + "）");
        String error = response.getString("error");
        if (error != null) throw new IOException("日志服务失败（" + method + "）：" + error);
        return response;
    }

    private void showLogs() {
        Toast.makeText(this, "正在读取运行日志…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                ModuleLogger.flush(5000L);
                loggerCall("flush", null);
                Bundle request = new Bundle();
                request.putInt("limit", 500);
                Bundle response = loggerCall("get_recent_logs", request);
                JSONArray logs = response == null ? new JSONArray()
                        : new JSONArray(response.getString("logs", "[]"));
                runOnUiThread(() -> {
                    if (!isDestroyed()) renderLogsDialog(logs);
                });
            } catch (Throwable error) {
                runOnUiThread(() -> Toast.makeText(this,
                        "读取日志失败：" + safeMessage(error), Toast.LENGTH_LONG).show());
            }
        }, "diagnostics-log-read").start();
    }

    private void renderLogsDialog(JSONArray logs) {
        LinearLayout content = vertical(dp(12));
        LinearLayout filters = horizontal();
        Spinner levelSpinner = new Spinner(this);
        String[] levels = {"全部等级", "DEBUG", "INFO", "WARN", "ERROR"};
        levelSpinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, levels));
        filters.addView(levelSpinner, weighted());

        LinkedHashSet<String> processSet = new LinkedHashSet<>();
        processSet.add("全部进程");
        for (int i = 0; i < logs.length(); i++) {
            JSONObject item = logs.optJSONObject(i);
            if (item != null) processSet.add(item.optString("processName", "unknown"));
        }
        String[] processes = processSet.toArray(new String[0]);
        Spinner processSpinner = new Spinner(this);
        processSpinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, processes));
        LinearLayout.LayoutParams processParams = weighted();
        processParams.setMarginStart(dp(8));
        filters.addView(processSpinner, processParams);
        content.addView(filters);

        ScrollView logScroll = new ScrollView(this);
        LinearLayout rows = vertical(dp(4));
        logScroll.addView(rows, new ScrollView.LayoutParams(-1, -2));
        content.addView(logScroll, new LinearLayout.LayoutParams(-1, dp(460)));
        Runnable updateRows = () -> {
            rows.removeAllViews();
            String levelFilter = levelSpinner.getSelectedItemPosition() == 0 ? "ALL"
                    : levels[levelSpinner.getSelectedItemPosition()];
            String processFilter = processes[processSpinner.getSelectedItemPosition()];
            int shown = 0;
            for (int i = logs.length() - 1; i >= 0 && shown < 250; i--) {
                JSONObject item = logs.optJSONObject(i);
                if (item == null) continue;
                if (!"ALL".equals(levelFilter)
                        && !levelFilter.equals(item.optString("level"))) continue;
                if (!"全部进程".equals(processFilter)
                        && !processFilter.equals(item.optString("processName"))) continue;
                long timestamp = item.optLong("timestamp", 0);
                String text = formatTimestamp(timestamp) + "  "
                        + item.optString("level", "") + "  ["
                        + item.optString("processName", "unknown") + "]\n"
                        + item.optString("eventType", "") + " · "
                        + item.optString("message", "");
                TextView row = text(text, 12,
                        color(com.google.android.material.R.attr.colorOnSurface));
                row.setTextIsSelectable(true);
                row.setPadding(dp(8), dp(8), dp(8), dp(8));
                String stack = item.optString("stackTrace", "");
                if (!stack.isEmpty()) {
                    row.setOnClickListener(v -> new MaterialAlertDialogBuilder(this)
                            .setTitle("异常堆栈")
                            .setMessage(stack)
                            .setPositiveButton("关闭", null)
                            .show());
                }
                rows.addView(row, new LinearLayout.LayoutParams(-1, -2));
                shown++;
            }
            if (shown == 0) {
                rows.addView(text("没有符合筛选条件的日志", 14,
                        color(com.google.android.material.R.attr.colorOnSurfaceVariant)));
            }
        };
        AdapterView.OnItemSelectedListener listener = new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position,
                                                 long id) { updateRows.run(); }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        };
        levelSpinner.setOnItemSelectedListener(listener);
        processSpinner.setOnItemSelectedListener(listener);
        updateRows.run();
        new MaterialAlertDialogBuilder(this)
                .setTitle("运行日志（最近 500 条）")
                .setView(content)
                .setPositiveButton("关闭", null)
                .show();
    }

    private String hookLine(JSONObject hooks, String key, String label) {
        JSONObject item = hooks == null ? null : hooks.optJSONObject(key);
        return stateLine(label, bool(item, "ok"));
    }

    private String processObservation(JSONArray processes, JSONObject packages, String pkg,
                                      String packageKey, String label) {
        JSONObject item = null;
        if (processes != null) for (int i = 0; i < processes.length(); i++) {
            JSONObject candidate = processes.optJSONObject(i);
            if (candidate != null && pkg.equals(candidate.optString("packageName"))) {
                item = candidate;
                break;
            }
        }
        JSONObject installedInfo = packageKey == null || packages == null
                ? null : packages.optJSONObject(packageKey);
        boolean installed = installedInfo != null && installedInfo.optBoolean("installed", false);
        String statusText;
        if (packageKey != null && !installed) statusText = "未安装";
        else if (item == null || !item.optBoolean("bootCountKnown", false)) statusText = "未知";
        else if (!item.optBoolean("observedThisBoot", false)) statusText = "尚未观察到";
        else statusText = "已观察到 · 本次启动 " + item.optInt("loadsThisBoot", 0) + " 次";
        JSONArray names = item == null ? null : item.optJSONArray("observedProcessesThisBoot");
        if (names != null && names.length() > 0 && item.optBoolean("observedThisBoot", false)) {
            StringBuilder processNames = new StringBuilder();
            for (int i = 0; i < names.length(); i++) {
                if (i > 0) processNames.append(", ");
                processNames.append(names.optString(i));
            }
            statusText += " · " + processNames;
        }
        long last = item == null ? 0 : item.optLong("lastObservedAt", 0);
        if (last > 0 && (item == null || !item.optBoolean("observedThisBoot", false))) {
            statusText += " · 上次 " + formatTimestamp(last);
        }
        return line(label, statusText, item != null && item.optBoolean("observedThisBoot", false));
    }

    private String scopeObservation(JSONObject logging, String key) {
        if (!bool(logging, "imeScopeBootCountKnown")) return "未知";
        return bool(logging, key) ? "已观察到" : "尚未观察到";
    }

    private boolean isImeReplacementKnown(JSONObject logging) {
        return logging != null
                && logging.optBoolean("imeReplacementProviderAvailable", false)
                && !logging.isNull("imeReplacementConfigured")
                && !logging.isNull("imeReplacementEnabled");
    }

    private String imeReplacementObservation(JSONObject logging) {
        if (!isImeReplacementKnown(logging)) return "未知（设置 Provider 暂不可读）";
        if (!bool(logging, "imeReplacementConfigured")) return "未配置（默认关闭）";
        if (!bool(logging, "imeReplacementEnabled")) return "已关闭";
        String target = value(logging, "imeReplacementPackage");
        return target.isEmpty() ? "已开启，但目标未知" : target + " 已开启";
    }

    private String summarizeHookEvents(JSONArray events) {
        if (events == null || events.length() == 0) return "尚未观察到 Hook 安装事件\n";
        StringBuilder result = new StringBuilder();
        int first = Math.max(0, events.length() - 15);
        for (int i = first; i < events.length(); i++) {
            JSONObject event = events.optJSONObject(i);
            if (event == null) continue;
            String type = event.optString("eventType");
            String state;
            if ("hook_start".equals(type)) state = "开始";
            else if ("hook_success".equals(type)) state = "注册成功";
            else if ("hook_failure".equals(type)) state = "注册失败";
            else if ("hook_skipped".equals(type)) state = "主动跳过";
            else state = event.optString("message", "状态");
            String target = event.optString("hookClass", "");
            String method = event.optString("hookMethod", "");
            String signature = event.optString("signature", "");
            result.append(state).append(" · ")
                    .append(event.optString("feature", "Hook"));
            if (!target.isEmpty()) result.append("\n").append(target);
            if (!method.isEmpty()) result.append("#").append(method);
            if (!signature.isEmpty()) result.append(" ").append(signature);
            result.append("\n");
        }
        return result.toString();
    }

    private static String stateLine(String label, boolean ok) {
        return line(label, ok ? "就绪" : "未就绪", ok);
    }

    private static String line(String label, String value, boolean ok) {
        return (ok ? "✓ " : "○ ") + label + "：" + value + "\n";
    }

    private static boolean bool(JSONObject object, String key) {
        return object != null && object.optBoolean(key, false);
    }

    private static int intValue(JSONObject object, String key) {
        return object == null ? 0 : object.optInt(key, 0);
    }

    private static long longValue(JSONObject object, String key) {
        return object == null ? 0 : object.optLong(key, 0);
    }

    private static String value(JSONObject object, String key) {
        return object == null ? "" : object.optString(key, "");
    }

    private static String formatTimestamp(long timestamp) {
        return timestamp <= 0 ? "暂无" : new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                Locale.getDefault()).format(new Date(timestamp));
    }

    private static String sourceLabel(String source) {
        if (LyricSourcePolicy.SUPERLYRIC.equals(source)) return "SuperLyric";
        if (LyricSourcePolicy.COMPAT_SEARCH.equals(source)) return "在线搜索（兼容模式）";
        if ("netease-api".equals(source)) return "网易云在线 fallback";
        if (source != null && source.startsWith("netease-")) return "网易云 Hook fallback";
        return source == null ? "" : source;
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName() : message;
    }

    private MaterialButton outlinedButton(String value, View.OnClickListener listener) {
        MaterialButton button = new MaterialButton(this, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        button.setText(value);
        button.setOnClickListener(listener);
        return button;
    }

    private MaterialButton textButton(String value, View.OnClickListener listener) {
        MaterialButton button = new MaterialButton(this, null, android.R.attr.borderlessButtonStyle);
        button.setText(value);
        button.setOnClickListener(listener);
        return button;
    }

    private TextView text(String value, float size, int textColor) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(textColor);
        return view;
    }

    private LinearLayout vertical(int padding) {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setPadding(padding, padding, padding, padding);
        return view;
    }

    private LinearLayout horizontal() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.HORIZONTAL);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0, -2, 1f);
    }

    private LinearLayout.LayoutParams marginTop(int value) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = value;
        return params;
    }

    private LinearLayout.LayoutParams cardMargin() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(10);
        return params;
    }

    private int color(int attribute) {
        return com.google.android.material.color.MaterialColors.getColor(
                this, attribute, Color.BLACK);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
