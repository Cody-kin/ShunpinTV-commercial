package com.harbour.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.harbour.core.Catalog;
import com.harbour.core.LocalCommandParser;
import com.harbour.core.OfficialDestinations;
import com.harbour.core.SourceSearchLinks;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Local rules + explicit preview/selection. No model, cloud API, microphone, or URL fetching. */
public final class LocalCommandActivity extends Activity {
  private static final int BG = 0xff0b1424, PANEL = 0xff15273c, TEAL = 0xff63e2c5;
  private final LocalCommandParser parser = new LocalCommandParser();
  private EditText input;
  private LinearLayout results;
  private Catalog catalog;
  private Map<String, String> groups;
  private Button preview;
  private String previewInput = "";
  private static final int MAX_RESULTS = 24;
  private final List<Button> resultButtons = new ArrayList<>();
  private final List<Button> exampleButtons = new ArrayList<>();
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final Handler main = new Handler(Looper.getMainLooper());
  private Future<?> pendingWork;
  // These fields are read/written only on the main thread, including completion callbacks.
  private boolean started, disposed, initialized, busy, searchInterrupted;
  private int generation;

  @Override public void onCreate(Bundle state) {
    super.onCreate(state);
    getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
    LinearLayout root = column();
    root.setBackgroundColor(BG);
    root.setPadding(dp(36), dp(22), dp(36), dp(22));
    root.addView(text("本地指令", 29, TEAL));
    root.addView(text("此頁使用中文規則解析 · 可選模型在「本機模型準備度」另行匯入 · 不使用雲端 AI", 14, 0xffb9c9d8));
    LinearLayout entry = row();
    input = new EditText(this);
    input.setId(View.generateViewId());
    input.setTextColor(0xfff1f6fc);
    input.setHintTextColor(0xff9bb0c3);
    input.setTextSize(18);
    input.setSingleLine(true);
    input.setHint("例如：播放 CCTV1、找香港 M3U、匯入來源");
    input.setFilters(new InputFilter[] {new InputFilter.LengthFilter(LocalCommandParser.MAX_INPUT)});
    input.setPadding(dp(14), dp(8), dp(14), dp(8));
    input.setBackground(background(PANEL, 0xff39536b));
    entry.addView(input, new LinearLayout.LayoutParams(0, dp(52), 1));
    preview = button("預覽指令", this::preview);
    LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(dp(140), dp(52));
    previewParams.leftMargin = dp(12);
    entry.addView(preview, previewParams);
    add(root, entry, 14);
    LinearLayout examples = row();
    example(examples, "找 CCTV1");
    example(examples, "找香港 M3U");
    example(examples, "匯入來源");
    example(examples, "網上找來源");
    add(root, examples, 10);
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    results = column();
    results.setPadding(dp(18), dp(14), dp(18), dp(14));
    results.setBackground(background(PANEL, 0));
    scroll.addView(results);
    LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(-1, 0, 1);
    scrollParams.topMargin = dp(14);
    root.addView(scroll, scrollParams);
    LinearLayout footer = row();
    Button readiness = button("本機模型準備度", () -> startActivity(new Intent(this, ModelReadinessActivity.class)));
    readiness.setTag("open_model_readiness");
    LinearLayout.LayoutParams readinessParams = new LinearLayout.LayoutParams(0, dp(48), 1);
    readinessParams.rightMargin = dp(12);
    footer.addView(readiness, readinessParams);
    Button back = button("返回首頁", this::finish);
    footer.addView(back, new LinearLayout.LayoutParams(0, dp(48), 1));
    readiness.setNextFocusRightId(back.getId());
    back.setNextFocusLeftId(readiness.getId());
    add(root, footer, 10);
    input.setNextFocusRightId(preview.getId());
    preview.setNextFocusLeftId(input.getId());
    input.setOnEditorActionListener((v, action, event) -> {
      if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE || action == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
        preview(); return true;
      }
      return false;
    });
    input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
    setContentView(root);
    results.addView(text("正在載入本機目錄…載入完成後即可預覽指令。", 20, 0xfff1f6fc));
    setBusy(true);
    if (state != null) input.setText(state.getString("command_text", ""));
    acceptPrefill(getIntent());
    back.requestFocus();
    getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
  }
  @Override protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent); setIntent(intent); acceptPrefill(intent);
  }
  private void acceptPrefill(Intent intent) {
    String query = intent.getStringExtra("prefill_local_command");
    intent.removeExtra("prefill_local_command");
    if (query != null && query.length() <= LocalCommandParser.MAX_INPUT) {
      input.setText(query);
      // Populate text only. The user must explicitly preview and then select any action.
    }
  }
  @Override protected void onStart() {
    super.onStart();
    started = true;
    if (!initialized) {
      loadCatalog();
    } else {
      setBusy(false);
      if (searchInterrupted) {
        searchInterrupted = false;
        results.removeAllViews();
        resultButtons.clear();
        results.addView(text("上次查找已暫停，請再次按「預覽指令」。", 18, 0xffb9c9d8));
        preview.setNextFocusDownId(View.NO_ID);
      }
    }
  }
  @Override protected void onStop() {
    started = false;
    generation++;
    if (pendingWork != null) pendingWork.cancel(true);
    pendingWork = null;
    if (initialized && busy) searchInterrupted = true;
    busy = false;
    super.onStop();
  }
  @Override protected void onDestroy() {
    disposed = true;
    generation++;
    if (pendingWork != null) pendingWork.cancel(true);
    worker.shutdownNow();
    main.removeCallbacksAndMessages(null);
    super.onDestroy();
  }
  private boolean canPublish(int request) {
    return started && !disposed && !isFinishing() && !isDestroyed() && request == generation;
  }
  private void setBusy(boolean value) {
    busy = value;
    preview.setEnabled(!value);
    preview.setAlpha(value ? 0.45f : 1f);
    preview.setText(value ? (initialized ? "正在查找…" : "正在載入…") : "預覽指令");
    for (Button button : exampleButtons) {
      button.setEnabled(!value);
      button.setAlpha(value ? 0.45f : 1f);
    }
  }
  private void loadCatalog() {
    if (disposed || !started) return;
    final int request = ++generation;
    final Context context = getApplicationContext();
    setBusy(true);
    results.removeAllViews();
    resultButtons.clear();
    results.addView(text("正在載入本機目錄…載入完成後即可預覽指令。", 20, 0xfff1f6fc));
    pendingWork = worker.submit(() -> {
      Catalog loaded = null;
      Map<String, String> loadedGroups = Collections.emptyMap();
      try {
        loaded = new CatalogRepository(context).catalog;
        if (Thread.currentThread().isInterrupted()) return;
        loadedGroups = SourceManager.groupByChannel(context);
      } catch (Exception ignored) { }
      if (Thread.currentThread().isInterrupted()) return;
      final Catalog snapshot = loaded;
      final Map<String, String> groupSnapshot = loadedGroups;
      main.post(() -> {
        if (!canPublish(request)) return;
        pendingWork = null;
        catalog = snapshot;
        groups = groupSnapshot;
        initialized = true;
        setBusy(false);
        results.removeAllViews();
        if (catalog == null) {
          results.addView(text("本機目錄暫時無法讀取。你仍可開啟來源管理或官方搜尋；不會假造匹配結果。", 18, 0xfff1f6fc));
        } else {
          results.addView(text("已載入 " + catalog.channels.size() + " 個本機頻道。", 21, TEAL));
          results.addView(text("播放／收藏會先列出匹配頻道；選台才會執行。找新網址需要另外開啟官方網站。", 16, 0xffb9c9d8));
        }
        // Do not steal focus from Back while the user is leaving or typing.
      });
    });
  }
  @Override protected void onSaveInstanceState(Bundle out) {
    out.putString("command_text", input.getText().toString());
    super.onSaveInstanceState(out);
  }
  private void example(LinearLayout parent, String phrase) {
    Button b = button(phrase, () -> { input.setText(phrase); preview(); });
    exampleButtons.add(b);
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(46), 1);
    params.rightMargin = dp(8);
    parent.addView(b, params);
  }
  private void preview() {
    if (!started || disposed || !initialized || busy) return;
    InputMethodManager keyboard = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
    if (keyboard != null) keyboard.hideSoftInputFromWindow(input.getWindowToken(), 0);
    previewInput = input.getText().toString();
    LocalCommandParser.Command command = parser.parse(previewInput);
    results.removeAllViews();
    resultButtons.clear();
    results.addView(text(command.explanation, 17, 0xffc4d4e3));
    switch (command.action) {
      case HELP:
        results.addView(text("試試：找 CCTV1／播放 CCTV1／收藏 CCTV1／看衛視／找香港 M3U／匯入來源", 18, 0xfff1f6fc));
        break;
      case CATEGORY:
        action("確認切換：" + command.query, () -> returnMain(new Intent().putExtra("open_category", command.query)));
        break;
      case IMPORT:
        results.addView(text("只有進入來源管理並確認後才會讀取檔案或網址。已知網址請在來源管理內輸入；此處不傳送指令內容。", 16, 0xfff1f6fc));
        action("確認開啟來源管理", () -> returnMain(new Intent().putExtra("open_sources", true)));
        break;
      default:
        matches(command);
        return;
    }
    focusResults();
  }
  private void focusResults() {
    if (!resultButtons.isEmpty()) {
      for (int i = 0; i < resultButtons.size(); i++) {
        Button b = resultButtons.get(i);
        b.setNextFocusUpId(i == 0 ? preview.getId() : resultButtons.get(i - 1).getId());
        if (i + 1 < resultButtons.size()) b.setNextFocusDownId(resultButtons.get(i + 1).getId());
      }
      preview.setNextFocusDownId(resultButtons.get(0).getId());
      resultButtons.get(0).requestFocus();
    } else {
      preview.setNextFocusDownId(View.NO_ID);
      preview.requestFocus();
    }
  }
  private void matches(LocalCommandParser.Command command) {
    if (catalog == null) {
      results.addView(text("目錄讀取失敗，沒有可確認的頻道。", 20, 0xfff1f6fc));
      action("開啟來源管理", () -> returnMain(new Intent().putExtra("open_sources", true)));
      if (isSourceSearch(command)) sourceSearchAction(command);
      focusResults();
      return;
    }
    final int request = ++generation;
    final Catalog snapshot = catalog;
    final Map<String, String> groupSnapshot = groups;
    setBusy(true);
    preview.setNextFocusDownId(View.NO_ID);
    results.addView(text("正在比對 " + snapshot.channels.size() + " 個本機頻道…", 20, TEAL));
    pendingWork = worker.submit(() -> {
      try {
        List<Catalog.Channel> all = parser.match(snapshot, command, groupSnapshot);
        if (Thread.currentThread().isInterrupted()) return;
        int total = all.size();
        // The UI callback holds at most 24 results, not an entire large match list.
        List<Catalog.Channel> visible = new ArrayList<>(all.subList(0, Math.min(MAX_RESULTS, total)));
        main.post(() -> {
          if (!canPublish(request)) return;
          pendingWork = null;
          setBusy(false);
          results.removeAllViews();
          resultButtons.clear();
          results.addView(text(command.explanation, 17, 0xffc4d4e3));
          renderMatches(command, visible, total);
          focusResults();
        });
      } catch (RuntimeException e) {
        if (Thread.currentThread().isInterrupted()) return;
        main.post(() -> {
          if (!canPublish(request)) return;
          pendingWork = null;
          setBusy(false);
          results.removeAllViews();
          resultButtons.clear();
          results.addView(text("本機查找暫時失敗，請縮短指令後重新預覽。", 18, 0xfff1f6fc));
          focusResults();
        });
      }
    });
  }
  private void renderMatches(LocalCommandParser.Command command, List<Catalog.Channel> matches, int total) {
    results.addView(text("本機匹配 " + total + " 個頻道", 22, TEAL));
    if (matches.isEmpty()) {
      results.addView(text("沒有匹配結果。請縮短名稱，或先匯入你有權使用的 M3U 片單；尚未搜尋網路。", 17, 0xfff1f6fc));
      action("開啟來源管理", () -> returnMain(new Intent().putExtra("open_sources", true)));
      if (isSourceSearch(command)) sourceSearchAction(command);
      else action("開啟央視官方搜尋 ↗", () -> openOfficial(OfficialDestinations.Destination.CCTV_SEARCH));
      return;
    }
    String actionName = command.action == LocalCommandParser.Action.FAVORITE ? "加入收藏" : "播放";
    if (command.action == LocalCommandParser.Action.SEARCH || isSourceSearch(command))
      results.addView(text("以下為本機目錄預覽；選取一台後才嘗試播放。來源可用性由播放頁檢查。", 14, 0xffb9c9d8));
    for (int i = 0; i < matches.size(); i++) {
      Catalog.Channel channel = matches.get(i);
      action(actionName + "  ·  " + channel.number + "  " + channel.name, () -> returnMain(new Intent()
          .putExtra("command_action", command.action == LocalCommandParser.Action.FAVORITE ? "favorite" : "play")
          .putExtra("command_channel_id", channel.id)));
    }
    if (total > MAX_RESULTS) results.addView(text("顯示前 " + MAX_RESULTS + " 個結果；請加入更完整的頻道名稱以縮小範圍。", 14, 0xffb9c9d8));
    if (isSourceSearch(command)) {
      sourceSearchAction(command);
      action("我有來源網址：開啟來源管理", () -> returnMain(new Intent().putExtra("open_sources", true)));
    }
    if (command.action == LocalCommandParser.Action.SEARCH)
      action("將關鍵字帶回首頁搜尋", () -> returnMain(new Intent().putExtra("command_action", "search").putExtra("command_query", command.query)));
  }
  private boolean isSourceSearch(LocalCommandParser.Command command) {
    return command.action == LocalCommandParser.Action.SOURCE_SEARCH
        || command.action == LocalCommandParser.Action.WEB_SEARCH;
  }
  private void sourceSearchAction(LocalCommandParser.Command command) {
    final String url, words;
    try {
      // Validate the original instruction too: parser normalization must not hide a URL/control.
      SourceSearchLinks.keywords(previewInput);
      words = SourceSearchLinks.keywords(command.query);
      url = SourceSearchLinks.build(command.query);
    } catch (IllegalArgumentException invalid) {
      results.addView(text("無法網頁搜尋：" + invalid.getMessage(), 15, 0xffb9c9d8));
      return;
    }
    results.addView(text("按下後將送往 Bing 的關鍵字：" + words, 16, TEAL));
    results.addView(text("一般網頁搜尋，非雲端 AI；需要網路與瀏覽器。結果不代表來源已獲授權或可播放，不會自動匯入。請自行核對後，在來源管理貼上網址。", 14, 0xffb9c9d8));
    action("網頁搜尋新片單（需網路） ↗", () -> {
      try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)); }
      catch (ActivityNotFoundException | SecurityException unavailable) {
        new AlertDialog.Builder(this).setTitle("無法開啟網頁搜尋")
            .setMessage("這部電視沒有可處理網頁的應用程式，或系統不允許開啟。可在手機／電腦的 Bing 搜尋：\n" + words
                + "\n未自動搜尋或匯入任何來源。")
            .setPositiveButton("返回指令頁", null).show();
      }
    });
  }
  @androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
  private void returnMain(Intent result) {
    result.setClass(this, MainActivity.class);
    result.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    startActivity(result);
    finish();
  }
  private void openOfficial(OfficialDestinations.Destination destination) {
    if (!OfficialDestinations.isAllowed(destination.url)) return;
    try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(destination.url)).addCategory(Intent.CATEGORY_BROWSABLE)); }
    catch (ActivityNotFoundException | SecurityException e) {
      new AlertDialog.Builder(this).setTitle("沒有可開啟網頁的應用程式")
          .setMessage("可在手機或電腦開啟：\n" + destination.url + "\n此電視未找到可處理這個網站的應用程式。")
          .setPositiveButton("返回指令頁", null).show();
    }
  }
  private void action(String title, Runnable run) {
    Button b = button(title, run); resultButtons.add(b); add(results, b, 8);
  }
  private Button button(String title, Runnable run) {
    Button b = new Button(this);
    b.setId(View.generateViewId()); b.setText(title); b.setTextSize(15); b.setAllCaps(false);
    b.setPadding(dp(14), dp(6), dp(14), dp(6)); b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
    b.setMinHeight(dp(46)); b.setMinimumHeight(dp(46)); b.setFocusable(true);
    b.setTextColor(0xffedf5fc); b.setBackground(background(0xff203a50, 0));
    b.setOnFocusChangeListener((v, focused) -> {
      b.setTextColor(focused ? BG : 0xffedf5fc);
      b.setBackground(background(focused ? TEAL : 0xff203a50, focused ? TEAL : 0));
      b.setTypeface(Typeface.DEFAULT, focused ? Typeface.BOLD : Typeface.NORMAL);
    });
    b.setOnClickListener(v -> run.run()); return b;
  }
  private TextView text(String value, int size, int color) {
    TextView v = new TextView(this); v.setText(value); v.setTextSize(size); v.setTextColor(color);
    v.setLineSpacing(dp(3), 1); v.setPadding(0, dp(2), 0, dp(5)); return v;
  }
  private GradientDrawable background(int color, int border) {
    GradientDrawable bg = new GradientDrawable(); bg.setColor(color); bg.setCornerRadius(dp(10));
    if (border != 0) bg.setStroke(dp(1), border); return bg;
  }
  private LinearLayout column() { LinearLayout v = row(); v.setOrientation(LinearLayout.VERTICAL); return v; }
  private LinearLayout row() { return new LinearLayout(this); }
  private void add(LinearLayout parent, View child, int margin) {
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.topMargin = dp(margin); parent.addView(child, params);
  }
  private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
}
