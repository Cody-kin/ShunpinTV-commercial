package com.harbour.tv;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.InputFilter;
import android.util.JsonReader;
import android.util.JsonToken;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;
import com.harbour.llm.LocalIntentEngine;
import com.harbour.llm.ModelFiles;
import java.io.*;
import java.text.Normalizer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** User-imported GGUF inference preview. Generated text never invokes a tool or starts playback. */
public final class LocalModelActivity extends Activity {
  private static final int OPEN_MODEL = 9201, BG = 0xff0b1424, TEAL = 0xff63e2c5;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ExecutorService files = Executors.newSingleThreadExecutor();
  private final AtomicBoolean cancelImport = new AtomicBoolean();
  private volatile InputStream importingStream;
  private LocalIntentEngine engine;
  private File directory;
  private TextView state, output;
  private EditText input;
  private Button importButton, load, unload, remove, generate, cancel, transfer;
  private boolean active, importing, closing;
  private int generation;
  private String pendingSearch;

  @Override public void onCreate(Bundle saved) {
    super.onCreate(saved);
    getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
    directory = new File(getFilesDir(), "local-model");
    LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL);
    body.setPadding(dp(30), dp(18), dp(30), dp(18)); body.setBackgroundColor(BG);
    body.addView(label("本機模型 · GGUF 預覽", 28, TEAL));
    body.addView(label("真實 llama.cpp CPU 推論；模型由你匯入，不自動下載。模型可能答錯，輸出不會自動執行。", 15, 0xffb9c9d8));
    state = label("正在檢查 runtime…", 15, 0xfff1f6fc); state.setFocusable(true); body.addView(state);
    LinearLayout modelActions = row(); body.addView(modelActions);
    importButton = button("匯入 GGUF", this::chooseModel); add(modelActions, importButton);
    load = button("載入模型", this::loadModel); add(modelActions, load);
    unload = button("卸載記憶體", this::unloadModel); add(modelActions, unload);
    remove = button("移除模型檔", this::deleteModel); add(modelActions, remove);
    body.addView(label("限制：單一 GGUF ≤ 768 MiB、context 512、輸入 160 字、輸出最多 64 tokens。僅支援相容文字 instruct 模型；不是所有 GGUF 都可用。", 13, 0xffb9c9d8));
    input = new EditText(this); input.setSingleLine(true); input.setTextColor(0xfff1f6fc); input.setHintTextColor(0xff9bb0c3);
    input.setTextSize(18); input.setHint("例如：幫我找香港的 M3U 播放清單");
    input.setFilters(new InputFilter[] {new InputFilter.LengthFilter(LocalIntentEngine.MAX_INPUT_CHARS)});
    input.setText(saved == null ? "" : saved.getString("model_input", "")); body.addView(input);
    LinearLayout inferenceActions = row(); body.addView(inferenceActions);
    generate = button("生成模型預覽", this::generate); add(inferenceActions, generate);
    cancel = button("取消目前操作", this::cancelOperation); add(inferenceActions, cancel);
    output = label("尚未生成。模型輸出只供檢視，不代表可播放來源或已完成操作。", 17, 0xfff1f6fc);
    output.setPadding(dp(16), dp(16), dp(16), dp(16)); output.setBackgroundColor(0xff16273c); output.setFocusable(true); body.addView(output);
    transfer = button("帶入本地指令搜尋（仍需預覽）", () -> {
      if (pendingSearch == null) return;
      Intent next = new Intent(this, LocalCommandActivity.class);
      next.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
      next.putExtra("prefill_local_command", pendingSearch);
      startActivity(next); finish();
    }); body.addView(transfer);
    body.addView(label("只有完整且有效的 action/query JSON 才能帶入；只帶入「找…」文字，不執行模型聲稱的播放／收藏／匯入。", 13, 0xffb9c9d8));
    body.addView(button("返回本地指令", this::finish));
    ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.addView(body); setContentView(scroll);
    importButton.requestFocus(); getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
  }
  @Override protected void onStart() {
    super.onStart(); active = true; closing = false;
    if (engine == null) try { engine = new LocalIntentEngine(); } catch (IllegalStateException unavailable) { engine = null; }
    refreshState();
  }
  @Override protected void onStop() {
    active = false; generation++; cancelOperation();
    if (engine != null) { engine.close(); engine = null; }
    super.onStop();
  }
  @Override protected void onDestroy() {
    closing = true; cancelOperation();
    if (engine != null) engine.close();
    files.shutdown(); main.removeCallbacksAndMessages(null); super.onDestroy();
  }
  @Override protected void onSaveInstanceState(Bundle out) { out.putString("model_input", input.getText().toString()); super.onSaveInstanceState(out); }
  private void refreshState() {
    boolean available = engine != null, loaded = available && engine.isLoaded();
    boolean busy = importing || available && engine.isBusy();
    File model = ModelFiles.model(directory);
    String details = "Runtime：" + (available ? "本 APK 已載入 llama.cpp JNI（CPU）" : "此 APK／ABI 未提供可載入的 native runtime")
        + "\n模型：" + (model.isFile() ? "已匯入 " + model.length() + " bytes；" + (loaded ? "已載入記憶體" : "尚未載入記憶體") : "尚未匯入")
        + "\n真機速度／中文準確率：未驗收；主機 smoke 不代表 TCL 效能。";
    state.setText(details);
    importButton.setEnabled(!busy && !loaded);
    load.setEnabled(available && model.isFile() && !busy && !loaded);
    unload.setEnabled(loaded && !busy);
    remove.setEnabled(model.isFile() && !busy && !loaded);
    generate.setEnabled(loaded && !busy);
    cancel.setEnabled(busy);
    transfer.setEnabled(!busy && pendingSearch != null);
  }
  private void chooseModel() {
    if (importing || engine != null && (engine.isBusy() || engine.isLoaded())) return;
    Intent choose = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
    try { startActivityForResult(choose, OPEN_MODEL); }
    catch (ActivityNotFoundException unavailable) { output.setText("這部裝置沒有文件選擇器。請先安裝／啟用可用的系統文件提供者。"); }
  }
  @Override protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (request != OPEN_MODEL || result != RESULT_OK || data == null || data.getData() == null) return;
    final Uri uri = data.getData();
    if (!"content".equals(uri.getScheme())) { output.setText("只接受系統文件選擇器的 content 文件，不會下載網址。"); return; }
    final int requestGeneration = ++generation;
    importing = true; cancelImport.set(false); pendingSearch = null; refreshState();
    output.setText("正在串流複製 GGUF 到私有儲存並計算 SHA-256，可按取消…");
    files.execute(() -> {
      String message;
      try {
        long declared = -1;
        try (Cursor cursor = getContentResolver().query(uri, new String[] {OpenableColumns.SIZE}, null, null, null)) {
          if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) declared = cursor.getLong(0);
        }
        try (InputStream source = getContentResolver().openInputStream(uri)) {
          importingStream = source;
          ModelFiles.Imported imported = ModelFiles.importGguf(source, declared, directory, cancelImport::get);
          message = "已匯入 " + imported.size + " bytes\nSHA-256：" + imported.sha256
              + "\nHash 是檔案身分，不代表可信來源；尚未載入或驗證能推論。";
        }
      } catch (Exception error) { message = "未完成匯入：" + error.getMessage(); }
      finally { importingStream = null; }
      final String text = message;
      main.post(() -> {
        importing = false;
        if (!canPublish(requestGeneration)) { if (active && !closing) refreshState(); return; }
        output.setText(text); refreshState();
      });
    });
  }
  private void loadModel() {
    if (engine == null) return;
    ActivityManager manager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
    ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
    if (manager != null) manager.getMemoryInfo(memory);
    File model = ModelFiles.model(directory);
    if (manager != null && (memory.lowMemory || memory.availMem > 0 && memory.availMem < model.length() + 256L * 1024 * 1024)) {
      output.setText("目前可用記憶體偏低，未載入模型。請停止其他播放／App 後重試；檔案大小不是實際推論 RAM 需求。"); return;
    }
    try { watch(engine.load(model), "正在載入真實 GGUF…可按取消。", true, ""); }
    catch (RuntimeException error) { output.setText(error.getMessage()); refreshState(); }
  }
  private void generate() {
    if (engine == null) return;
    String original = input.getText().toString();
    try { watch(engine.generate(original), "正在進行本機模型推論…最多 64 tokens，約 15 秒期限；可按取消。", false, original); }
    catch (RuntimeException error) { output.setText(error.getMessage()); refreshState(); }
  }
  private void watch(CompletableFuture<String> future, String message, boolean loading, String original) {
    final int request = ++generation; pendingSearch = null; output.setText(message); refreshState();
    future.whenComplete((text, error) -> main.post(() -> {
      if (!canPublish(request)) return;
      if (error != null) output.setText("操作未完成：" + (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()));
      else if (loading) output.setText("模型已載入：" + text + "\n現在可以輸入短指令，產生不自動執行的預覽。");
      else {
        output.setText("模型原始輸出（未執行）：\n" + text);
        pendingSearch = safeSearchPreview(text, original);
        if (pendingSearch == null) output.append("\n\n輸出或原始要求未通過帶入檢查（JSON、否定指令或網址）；請自行修改原指令。輸出可能因 token 上限截斷。");
      }
      refreshState();
    }));
  }
  private void unloadModel() {
    if (engine == null) return;
    final int request = ++generation;
    try {
      CompletableFuture<Void> job = engine.unload(); refreshState();
      job.whenComplete((ignored, error) -> main.post(() -> {
        if (!canPublish(request)) return;
        output.setText(error == null ? "模型已從記憶體卸載；私有 GGUF 檔仍保留。" : "卸載未完成：" + error.getMessage()); refreshState();
      }));
    } catch (RuntimeException error) { output.setText(error.getMessage()); }
  }
  private void deleteModel() {
    new AlertDialog.Builder(this).setTitle("移除這份本機模型？").setMessage("只刪除此 App 的私有 GGUF 副本，不刪除原始文件。")
        .setNegativeButton("取消", null).setPositiveButton("移除", (dialog, which) -> {
          final int request = ++generation; importing = true; refreshState();
          files.execute(() -> {
            String message;
            try { ModelFiles.delete(directory); message = "私有模型已移除。"; } catch (IOException e) { message = "移除失敗：" + e.getMessage(); }
            final String done = message;
            main.post(() -> { importing = false; if (canPublish(request)) { output.setText(done); refreshState(); } });
          });
        }).show();
  }
  private void cancelOperation() {
    cancelImport.set(true);
    if (engine != null) engine.cancel();
    InputStream stream = importingStream;
    if (stream != null) filesClose(stream);
  }
  private void filesClose(InputStream stream) {
    // Content-provider reads may block; close from a separate thread so Back never blocks the UI.
    new Thread(() -> { try { stream.close(); } catch (IOException ignored) { } }, "model-import-cancel").start();
  }
  private boolean canPublish(int request) { return active && !closing && !isFinishing() && !isDestroyed() && generation == request; }

  static String safeSearchPreview(String json, String original) {
    String normalized = Normalizer.normalize(original == null ? "" : original, Normalizer.Form.NFKC);
    if (normalized.contains("://")) return null;
    for (String negative : new String[] {"不要", "不想", "別", "别", "停止", "取消", "移除", "刪除", "删除"})
      if (normalized.contains(negative)) return null;
    return safeSearchPreview(json);
  }
  static String safeSearchPreview(String json) {
    if (json == null || json.length() > 8192) return null;
    try (JsonReader reader = new JsonReader(new StringReader(json))) {
      reader.setLenient(false); reader.beginObject(); String action = null, query = null;
      while (reader.hasNext()) {
        String name = reader.nextName();
        if (reader.peek() != JsonToken.STRING) return null;
        if (name.equals("action") && action == null) action = reader.nextString();
        else if (name.equals("query") && query == null) query = reader.nextString();
        else return null;
      }
      reader.endObject(); if (reader.peek() != JsonToken.END_DOCUMENT) return null;
      if (action == null || query == null || !Arrays.asList("SEARCH", "SOURCE_SEARCH", "PLAY", "FAVORITE", "IMPORT", "WEB_SEARCH", "HELP").contains(action)) return null;
      query = Normalizer.normalize(query, Normalizer.Form.NFKC);
      if (action.equals("HELP") || action.equals("IMPORT") || query.trim().isEmpty() || query.length() > 80 || query.contains("://")) return null;
      for (int i = 0; i < query.length(); i++) if (Character.isISOControl(query.charAt(i)) || Character.getType(query.charAt(i)) == Character.FORMAT) return null;
      return "找 " + query.trim() + ((action.equals("SOURCE_SEARCH") || action.equals("WEB_SEARCH")) ? " M3U" : "");
    } catch (Exception invalid) { return null; }
  }
  private TextView label(String text, int size, int color) { TextView view = new TextView(this); view.setText(text); view.setTextSize(size); view.setTextColor(color); view.setPadding(0, dp(6), 0, dp(6)); return view; }
  private LinearLayout row() { return new LinearLayout(this); }
  private void add(LinearLayout row, Button button) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(48), 1); p.setMargins(dp(3), dp(5), dp(3), dp(5)); row.addView(button, p); }
  private Button button(String text, Runnable run) {
    Button button = new Button(this); button.setText(text); button.setTextSize(15); button.setAllCaps(false); button.setTextColor(0xfff1f6fc);
    button.setFocusable(true); button.setMinimumHeight(dp(48)); button.setBackgroundColor(0xff234056);
    button.setOnFocusChangeListener((view, focused) -> { button.setBackgroundColor(focused ? TEAL : 0xff234056); button.setTextColor(focused ? BG : 0xfff1f6fc); });
    button.setOnClickListener(view -> run.run()); return button;
  }
  private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
}
