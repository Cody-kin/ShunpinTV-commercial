package com.harbour.tv;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import java.io.File;
import com.harbour.llm.LocalIntentEngine;
import com.harbour.llm.ModelFiles;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.StatFs;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Read-only device snapshots. This Activity does not load, download, or benchmark any model. */
public final class ModelReadinessActivity extends Activity {
  private static final int BG = 0xff0b1424, PANEL = 0xff16273c, TEAL = 0xff63e2c5;
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final Handler main = new Handler(Looper.getMainLooper());
  private Future<?> pending;
  private boolean active, disposed, busy;
  private int generation;
  private LinearLayout cards;
  private TextView status;
  private Button refresh;

  @Override public void onCreate(Bundle state) {
    super.onCreate(state);
    getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
    LinearLayout root = column();
    root.setPadding(dp(32), dp(20), dp(32), dp(20));
    root.setBackgroundColor(BG);
    root.addView(label("本機模型準備度", 28, TEAL));
    TextView disclosure = label("可選 llama.cpp 推論引擎；模型須自行匯入，以下資訊不能判定模型可跑。", 16, 0xfff2f7fc);
    disclosure.setTag("model_disclosure");
    root.addView(disclosure);
    status = label("正在讀取這部裝置的系統資訊…", 13, 0xffa9bdcf);
    status.setTag("readiness_status");
    root.addView(status);
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    cards = column();
    scroll.addView(cards);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    render(new DeviceSnapshot());
    LinearLayout footer = new LinearLayout(this);
    refresh = button("重新讀取裝置資訊", this::refresh);
    refresh.setTag("readiness_refresh");
    Button modelPage = button("開啟本機模型預覽", () -> startActivity(new Intent(this, LocalModelActivity.class)));
    modelPage.setTag("open_local_model");
    LinearLayout.LayoutParams modelParams = new LinearLayout.LayoutParams(0, dp(48), 1);
    modelParams.setMargins(0, dp(10), dp(12), 0);
    footer.addView(modelPage, modelParams);
    Button back = button("返回本地指令", this::finish);
    back.setTag("readiness_back");
    LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, dp(48), 1);
    left.setMargins(0, dp(10), dp(12), 0);
    footer.addView(refresh, left);
    LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, dp(48), 1);
    right.topMargin = dp(10);
    footer.addView(back, right);
    refresh.setNextFocusRightId(back.getId());
    back.setNextFocusLeftId(refresh.getId());
    root.addView(footer);
    setContentView(root);
    back.requestFocus();
  }

  @Override protected void onStart() { super.onStart(); active = true; refresh(); }
  @Override protected void onStop() {
    active = false; generation++;
    if (pending != null) pending.cancel(true);
    busy = false;
    super.onStop();
  }
  @Override protected void onDestroy() {
    disposed = true; generation++;
    if (pending != null) pending.cancel(true);
    worker.shutdownNow();
    main.removeCallbacksAndMessages(null);
    super.onDestroy();
  }

  private void refresh() {
    if (!active || disposed || busy) return;
    busy = true;
    refresh.setEnabled(false); refresh.setAlpha(0.45f);
    status.setText("正在讀取這部裝置的系統資訊…");
    final int request = ++generation;
    final Context context = getApplicationContext();
    pending = worker.submit(() -> {
      DeviceSnapshot snapshot = readSnapshot(context);
      if (Thread.currentThread().isInterrupted()) return;
      main.post(() -> {
        if (!active || disposed || isFinishing() || isDestroyed() || request != generation) return;
        busy = false; pending = null;
        render(snapshot);
        refresh.setEnabled(true); refresh.setAlpha(1f);
        status.setText("讀取時間 " + new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date())
            + " · 只讀本機資訊；未進行模型測試或上傳資料。");
      });
    });
  }

  static final class DeviceSnapshot {
    String model = value(Build.MODEL), manufacturer = value(Build.MANUFACTURER);
    String android = value(Build.VERSION.RELEASE) + " / API " + Build.VERSION.SDK_INT;
    String abis = Build.SUPPORTED_ABIS == null || Build.SUPPORTED_ABIS.length == 0
        ? "系統未提供" : String.join(", ", Build.SUPPORTED_ABIS);
    String process = Process.is64Bit() ? "64 位元" : "32 位元";
    String soc = Build.VERSION.SDK_INT >= 31 ? value(Build.SOC_MODEL) : "此 Android 版本未提供 SoC 型號";
    long totalMemory = -1, availableMemory = -1, memoryThreshold = -1;
    long totalStorage = -1, availableStorage = -1;
    Boolean lowMemory, lowRamDevice, nativeRuntime, modelImported;
  }

  static DeviceSnapshot readSnapshot(Context context) {
    DeviceSnapshot result = new DeviceSnapshot();
    result.nativeRuntime = LocalIntentEngine.runtimeAvailable();
    result.modelImported = ModelFiles.model(new File(context.getFilesDir(), "local-model")).isFile();
    try {
      ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
      if (manager != null) {
        ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
        manager.getMemoryInfo(memory);
        if (memory.totalMem > 0) {
          result.totalMemory = memory.totalMem;
          result.availableMemory = memory.availMem >= 0 ? memory.availMem : -1;
          result.memoryThreshold = memory.threshold >= 0 ? memory.threshold : -1;
          result.lowMemory = memory.lowMemory;
        }
        result.lowRamDevice = manager.isLowRamDevice();
      }
    } catch (RuntimeException unavailable) { /* Keep unknown fields visibly unknown. */ }
    try {
      StatFs storage = new StatFs(context.getFilesDir().getAbsolutePath());
      if (storage.getTotalBytes() > 0) {
        result.totalStorage = storage.getTotalBytes();
        result.availableStorage = storage.getAvailableBytes();
      }
    } catch (RuntimeException unavailable) { /* Storage failure must not erase memory data. */ }
    return result;
  }

  private void render(DeviceSnapshot snapshot) {
    cards.removeAllViews();
    LinearLayout device = card("這部裝置");
    metric(device, "裝置型號", snapshot.model, "device_model");
    metric(device, "製造商", snapshot.manufacturer, "device_manufacturer");
    metric(device, "Android 版本", snapshot.android, "device_android");
    metric(device, "支援的 ABI", snapshot.abis, "device_abis");
    metric(device, "目前 App 程序", snapshot.process, "device_process");
    metric(device, "SoC 型號（系統回報）", snapshot.soc, "device_soc");
    LinearLayout memory = card("記憶體快照");
    metric(memory, "系統可見總 RAM", bytes(snapshot.totalMemory), "memory_total");
    metric(memory, "目前可用 RAM", bytes(snapshot.availableMemory), "memory_available");
    metric(memory, "系統低記憶體門檻", bytes(snapshot.memoryThreshold), "memory_threshold");
    metric(memory, "目前 lowMemory 狀態", yesNo(snapshot.lowMemory), "memory_low");
    metric(memory, "系統低 RAM 裝置標記", yesNo(snapshot.lowRamDevice), "memory_low_device");
    memory.addView(label("可用 RAM 隨播放與其他 App 改變，不能全數分配給模型；沒有量測推論尖峰或播放丟幀。", 13, 0xffa9bdcf));
    LinearLayout storage = card("App 內部儲存所在磁碟區");
    metric(storage, "可用儲存空間", bytes(snapshot.availableStorage), "storage_available");
    metric(storage, "磁碟區總容量", bytes(snapshot.totalStorage), "storage_total");
    storage.addView(label("這是 App 內部資料所在磁碟區，不是 SD 卡總容量，也不是已保留的模型空間。", 13, 0xffa9bdcf));
    LinearLayout next = card("目前階段與下一步");
    next.addView(label("推論引擎：" + (snapshot.nativeRuntime == null ? "正在檢查" : snapshot.nativeRuntime ? "本 APK 可載入 llama.cpp JNI" : "此 APK／ABI 未提供可載入的 native runtime")
        + "\n模型檔：" + (snapshot.modelImported == null ? "正在檢查" : snapshot.modelImported ? "已匯入；請在模型預覽頁手動載入" : "未匯入，沒有預設權重")
        + "\n真機推論與中文準確率：未驗收；主機測試不代表 TCL 效能", 17, 0xfff2f7fc));
    next.addView(label("本地指令預設仍是規則解析。模型頁可匯入 GGUF、手動載入、生成預覽、取消及卸載；輸出不會自動執行。本頁不下載模型，也不會因 RAM 較多而顯示「可以運行」。", 14, 0xffa9bdcf));
    next.addView(label("方向鍵可聚焦各張資訊卡並捲動；返回鍵回到本地指令。", 13, TEAL));
  }

  static String bytes(long value) {
    if (value < 0) return "無法讀取／系統未提供";
    double unit = value >= 1073741824L ? 1073741824d : 1048576d;
    return String.format(Locale.US, "%.2f %s（%d bytes）", value / unit,
        value >= 1073741824L ? "GiB" : "MiB", value);
  }
  private static String value(String value) {
    return value == null || value.trim().isEmpty() || Build.UNKNOWN.equalsIgnoreCase(value)
        ? "系統未提供" : value;
  }
  private static String yesNo(Boolean value) { return value == null ? "無法讀取／系統未提供" : value ? "是" : "否"; }
  private void metric(LinearLayout parent, String name, String value, String tag) {
    LinearLayout row = new LinearLayout(this);
    row.setPadding(0, dp(4), 0, dp(4));
    row.addView(label(name, 14, 0xffa9bdcf), new LinearLayout.LayoutParams(dp(220), -2));
    TextView content = label(value, 16, 0xfff2f7fc); content.setTag(tag);
    row.addView(content, new LinearLayout.LayoutParams(0, -2, 1));
    parent.addView(row);
  }
  private LinearLayout card(String title) {
    LinearLayout card = column();
    card.setPadding(dp(18), dp(12), dp(18), dp(12));
    card.setFocusable(true);
    card.setBackground(background(PANEL, false));
    card.setOnFocusChangeListener((view, focused) -> card.setBackground(background(PANEL, focused)));
    TextView heading = label(title, 19, TEAL); heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    card.addView(heading);
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.topMargin = dp(12);
    cards.addView(card, params); return card;
  }
  private Button button(String name, Runnable action) {
    Button button = new Button(this); button.setId(View.generateViewId());
    button.setText(name); button.setTextSize(15); button.setAllCaps(false); button.setFocusable(true);
    button.setGravity(Gravity.CENTER); button.setTextColor(0xfff2f7fc);
    button.setBackground(background(0xff234056, false));
    button.setOnFocusChangeListener((view, focused) -> {
      button.setBackground(background(focused ? TEAL : 0xff234056, focused));
      button.setTextColor(focused ? BG : 0xfff2f7fc);
    });
    button.setOnClickListener(view -> action.run()); return button;
  }
  private TextView label(String value, int size, int color) {
    TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color);
    view.setLineSpacing(dp(2), 1); view.setPadding(0, dp(2), 0, dp(4)); return view;
  }
  private LinearLayout column() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); return view; }
  private GradientDrawable background(int color, boolean focused) {
    GradientDrawable drawable = new GradientDrawable(); drawable.setColor(color); drawable.setCornerRadius(dp(10));
    if (focused) drawable.setStroke(dp(2), TEAL); return drawable;
  }
  private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
