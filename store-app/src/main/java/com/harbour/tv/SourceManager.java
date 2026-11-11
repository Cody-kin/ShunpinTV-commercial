package com.harbour.tv;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.text.*;
import android.view.View;
import android.widget.*;
import com.harbour.core.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.json.*;

/** Local user-supplied sources. No discovery, crawling, or license-verification claims. */
public final class SourceManager {
  private static final int OPEN = 7311, SAVE = 7312;
  private static final String PREF = "user_sources", KEY = "entries";
  private final Activity activity;
  private final Runnable changed;
  private volatile String pendingExport;
  private EditText search;
  private AlertDialog managerDialog;
  private ListView rows;
  private TextView summary;
  private List<JSONObject> visibleRows = Collections.emptyList();
  private int refreshGeneration;
  private final Set<String> selected = new HashSet<>();

  public SourceManager(Activity activity, Runnable onChanged) {
    this.activity = activity;
    changed = onChanged;
  }

  private static JSONArray load(Context context) {
    try {
      return new JSONArray(context.getSharedPreferences(PREF, 0).getString(KEY, "[]"));
    } catch (JSONException e) {
      return new JSONArray();
    }
  }

  private TextView label(String text) {
    TextView v = new TextView(activity);
    v.setText(text);
    v.setTextSize(13);
    v.setTextColor(0xFFA8BACB);
    v.setPadding(8, 8, 8, 8);
    return v;
  }

  private Button button(String text, View.OnClickListener click) {
    Button b = new Button(activity);
    b.setText(text);
    b.setAllCaps(false);
    b.setTextColor(0xFFF4F7FC);
    b.setTextSize(14);
    b.setMinHeight(dp(46));
    b.setPadding(dp(12), dp(8), dp(12), dp(8));
    android.graphics.drawable.StateListDrawable states =
        new android.graphics.drawable.StateListDrawable();
    for (boolean focused : new boolean[] {true, false}) {
      android.graphics.drawable.GradientDrawable bg =
          new android.graphics.drawable.GradientDrawable();
      bg.setColor(focused ? 0xFF214F53 : 0xFF20364B);
      bg.setCornerRadius(dp(8));
      bg.setStroke(dp(focused ? 2 : 1), focused ? 0xFF61E6CC : 0xFF31485D);
      states.addState(focused ? new int[] {android.R.attr.state_focused} : new int[] {}, bg);
    }
    b.setBackground(states);
    b.setOnClickListener(click);
    return b;
  }

  private LinearLayout column() {
    LinearLayout l = new LinearLayout(activity);
    l.setOrientation(LinearLayout.VERTICAL);
    l.setPadding(18, 12, 18, 12);
    return l;
  }

  private int dp(int value) {
    return Math.round(value * activity.getResources().getDisplayMetrics().density);
  }

  public void show() {
    LinearLayout panel = new LinearLayout(activity);
    panel.setPadding(dp(14), dp(8), dp(14), dp(8));
    panel.setBackgroundColor(0xFF142237);
    ScrollView leftScroll = new ScrollView(activity);
    LinearLayout left = column();
    leftScroll.addView(left);
    panel.addView(leftScroll, new LinearLayout.LayoutParams(dp(242), dp(380)));
    left.addView(label("匯入你的片單\n請確認來源權利及地區條件"));
    Button pasteButton = button("貼上 M3U 文字", v -> paste());
    left.addView(pasteButton);
    left.addView(
        button(
            "從文件匯入 M3U",
            v -> {
              Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
              i.addCategory(Intent.CATEGORY_OPENABLE);
              i.setType("*/*");
              if(documentPickerAvailable(i))activity.startActivityForResult(i, OPEN);
              else new AlertDialog.Builder(activity).setTitle("此電視沒有文件選擇器")
                .setMessage("可使用 M3U 網址或貼上清單文字匯入。")
                .setPositiveButton("貼上文字",(d,w)->paste()).setNeutralButton("匯入網址",(d,w)->fromUrl()).setNegativeButton("返回",null).show();
            }));
    left.addView(button("匯入 M3U／HLS 網址", v -> fromUrl()));
    left.addView(button("GitHub 片單 · 一鍵拉取", v -> openGithubPlaylist()));
    left.addView(button("更改 GitHub 片單網址", v -> editGithubPlaylist()));
    if (BuildConfig.PERSONAL_BUILD) left.addView(button("個人測試 · 常用片單", v -> personalPlaylists()));
    Button sampleButton = button("Mux 測試影片 · 非直播", v -> offerNetworkSample());
    sampleButton.setContentDescription("播放 Mux 公開測試影片（非直播）");
    left.addView(sampleButton);
    left.addView(
        button(
            "勾選搜尋結果",
            v -> {
              for (JSONObject row : filtered()) selected.add(row.optString("id"));
              refresh();
            }));
    left.addView(
        button(
            "清除勾選",
            v -> {
              selected.clear();
              refresh();
            }));
    left.addView(button("組合勾選並匯出 M3U", v -> exportSelected()));
    left.addView(
        button(
            "移除勾選來源",
            v -> {
              if (selected.isEmpty()) return;
              new AlertDialog.Builder(activity)
                  .setTitle("移除本機勾選來源？")
                  .setMessage("不會刪除原始文件。")
                  .setPositiveButton(
                      "移除",
                      (d, w) -> {
                        Set<String> deleting = new HashSet<>(selected);
                        new Thread(() -> {
                          try {
                            new PlaylistSubscriptions(activity).deleteIds(deleting);
                            activity.runOnUiThread(() -> {
                              selected.removeAll(deleting);
                              if (changed != null) changed.run();
                              refresh();
                            });
                          } catch (Exception e) { activity.runOnUiThread(() -> error(e)); }
                        }, "m3u-delete").start();
                      })
                  .setNegativeButton("取消", null)
                  .show();
            }));
    LinearLayout right = column();
    panel.addView(right, new LinearLayout.LayoutParams(0, dp(380), 1));
    search = new EditText(activity);
    search.setSingleLine(true);
    search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
    search.setOnEditorActionListener((view,action,event)->{
      if(action==android.view.inputmethod.EditorInfo.IME_ACTION_DONE||(event!=null&&event.getKeyCode()==android.view.KeyEvent.KEYCODE_ENTER)){
        ((android.view.inputmethod.InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(search.getWindowToken(),0);
        if(rows!=null)rows.requestFocus();return true;
      }return false;
    });
    search.setTextColor(0xFFF4F7FC);
    search.setTextSize(14);
    search.setHint("搜尋台名、分類、國家或地區");
    search.setHintTextColor(0xFFA8BACB);
    right.addView(search);
    summary = label("");
    right.addView(summary);
    rows = new ListView(activity);
    rows.setDivider(null);
    rows.setItemsCanFocus(false);
    right.addView(rows, new LinearLayout.LayoutParams(-1, 0, 1));
    right.addView(label("OK 勾選組合 · 長按 OK 查看來源詳情\n"+(BuildConfig.PERSONAL_BUILD?"HTTP 預設開啟，可在首頁設定關閉；片單下載仍限 HTTPS":"商業預覽使用 HTTPS 來源；片單下載限 HTTPS")));
    search.addTextChangedListener(
        new TextWatcher() {
          public void beforeTextChanged(CharSequence x, int a, int b, int c) {}

          public void onTextChanged(CharSequence x, int a, int b, int c) {
            refresh();
          }

          public void afterTextChanged(Editable x) {}
        });
    managerDialog =
        new AlertDialog.Builder(activity)
            .setTitle("來源管理  /  我的片單")
            .setView(panel)
            .setPositiveButton("完成", null)
            .create();
    managerDialog.show();
    if (managerDialog.getWindow() != null)
      managerDialog
          .getWindow()
          .setLayout((int) (activity.getResources().getDisplayMetrics().widthPixels * .92), -2);
    refresh();
    left.post(() -> pasteButton.requestFocus());
  }

  private List<JSONObject> filtered() { return visibleRows; }

  private void refresh() {
    if (rows == null) return;
    final int generation = ++refreshGeneration;
    final String query = search == null ? "" : search.getText().toString().toLowerCase(Locale.ROOT);
    new Thread(() -> {
      JSONArray data = load(activity);
      List<JSONObject> found = new ArrayList<>();
      for (int i = 0; i < data.length(); i++) {
        JSONObject row = data.optJSONObject(i);
        if (row != null && (row.optString("name") + " " + row.optString("region") + " " + row.optString("m3u"))
            .toLowerCase(Locale.ROOT).contains(query)) found.add(row);
      }
      activity.runOnUiThread(() -> {
        if (generation != refreshGeneration || activity.isFinishing() || activity.isDestroyed()) return;
        visibleRows = found;
        renderRows(found);
      });
    }, "m3u-manager-filter").start();
  }

  private void renderRows(List<JSONObject> found) {
    summary.setText("符合 " + found.size() + " 個來源 · 已勾選 " + selected.size() + " 個");
    BaseAdapter adapter =
        new BaseAdapter() {
          public int getCount() {
            return found.size();
          }

          public Object getItem(int position) {
            return found.get(position);
          }

          public long getItemId(int position) {
            return position;
          }

          public View getView(int position, View recycled, android.view.ViewGroup parent) {
            JSONObject item = found.get(position);
            CheckBox box =
                recycled instanceof CheckBox ? (CheckBox) recycled : new CheckBox(activity);
            box.setFocusable(false);
            box.setClickable(false);
            box.setTextColor(0xFFF4F7FC);
            box.setTextSize(13);
            box.setMaxLines(4);
            box.setMinHeight(dp(80));
            box.setPadding(dp(8), dp(8), dp(8), dp(8));
            box.setText(
                item.optString("name")
                    + " · "
                    + item.optString("region")
                    + "\n"
                    + status(activity, item));
            box.setChecked(selected.contains(item.optString("id")));
            return box;
          }
        };
    rows.setAdapter(adapter);
    rows.setOnItemClickListener(
        (p, v, position, id) -> {
          String key = found.get(position).optString("id");
          if (!selected.add(key)) selected.remove(key);
          summary.setText("符合 " + found.size() + " 個來源 · 已勾選 " + selected.size() + " 個");
          adapter.notifyDataSetChanged();
        });
    rows.setOnItemLongClickListener(
        (p, v, position, id) -> {
          JSONObject item = found.get(position);
          AlertDialog.Builder details = new AlertDialog.Builder(activity)
              .setTitle(item.optString("name"))
              .setMessage(
                  "出處："
                      + item.optString("origin")
                      + "\n地區："
                      + item.optString("region")
                      + "\n期限："
                      + item.optString("until")
                      + "\n使用者聲明，未核實授權\n"
                      + status(activity, item))
              .setPositiveButton("知道了", null);
          try {
            URI uri = URI.create(PlaylistSubscriptions.uriOf(item));
            if (HttpPlaybackConsent.isBuildAllowed(uri)) {
              boolean enabled = new HttpPlaybackConsent(activity).permits(item.optString("id") + "-stream", uri);
              details.setNeutralButton(enabled ? "停用 HTTP 播放" : "啟用此來源 HTTP 播放",
                  (dialog, which) -> confirmHttpPlayback(item));
            }
          } catch (RuntimeException ignored) { /* Invalid sources remain reviewable, never enabled. */ }
          details.show();
          return true;
        });
  }

  public void configureHttp(String channelId) {
    try {
      JSONArray entries=new PlaylistSubscriptions(activity).entries();
      for(int i=0;i<entries.length();i++) {
        JSONObject row=entries.getJSONObject(i);
        if(channelId.equals(row.optString("id"))) {
          URI uri=URI.create(PlaylistSubscriptions.uriOf(row));
          if(!HttpPlaybackConsent.isBuildAllowed(uri)) {
            new AlertDialog.Builder(activity).setTitle("HTTP 來源設定")
              .setMessage("此版本尚未支援這個 HTTP 主機／連接埠："+uri.getAuthority()+"。不能啟用；請更新 App 或選擇 HTTPS 來源。")
              .setPositiveButton("知道了",null).show();return;
          }
          confirmHttpPlayback(row);return;
        }
      }
      error(new IOException("找不到這個已匯入來源"));
    } catch(Exception e){error(e);}
  }

  private void confirmHttpPlayback(JSONObject row) {
    try {
      URI uri = URI.create(PlaylistSubscriptions.uriOf(row));
      if (!HttpPlaybackConsent.isBuildAllowed(uri)) throw new IllegalArgumentException("此主機不在 HTTP 白名單");
      String sourceId = row.getString("id") + "-stream";
      HttpPlaybackConsent policy = new HttpPlaybackConsent(activity);
      boolean enabled = policy.permits(sourceId, uri);
      AlertDialog dialog = new AlertDialog.Builder(activity)
          .setTitle(enabled ? "停用此來源的 HTTP 播放？" : "允許此來源使用 HTTP？")
          .setMessage("來源主機：" + uri.getAuthority() + "\n來源：" + row.optString("name")
              + "\nHTTP 不加密，影片清單、分片或密鑰可能被觀察或竄改。"
              + "僅啟用這個來源及其播放所需的 HTTP 清單、轉址、分片和密鑰；可能連至其他主機。最多跟隨5次正常轉址；可隨時在此停用。"
              + "使用權、地區及期限條件仍然適用。此選擇不會立即開始播放。")
          .setNegativeButton("取消", null)
          .setPositiveButton(enabled ? "停用" : "我同意，僅啟用此來源", (d, which) -> {
            try {
              policy.setAllowed(sourceId, uri, !enabled);
              if (changed != null) changed.run();
              refresh();
            } catch (RuntimeException error) { error(error); }
          }).create();
      dialog.show();
      dialog.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus();
    } catch (Exception error) { error(error); }
  }

  private static String status(Context context, JSONObject row) {
    try {
      M3uPlaylist.Entry e = M3uPlaylist.parse(row.getString("m3u")).get(0);
      boolean allowedHttp = new HttpPlaybackConsent(context).permits(row.optString("id") + "-stream", URI.create(e.uri));
      if (e.invalidReason() != null && !allowedHttp) return e.invalidReason();
      if (!Instant.now().isBefore(Instant.parse(row.getString("until")))) return "失效：已到期";
      android.content.SharedPreferences observation =
          context.getSharedPreferences("source_status", 0);
      String last = observation.getString(row.optString("id"), null);
      return continuousPlaybackSummary(context,e.uri) + (allowedHttp ? "HTTP 明文播放已同意 · " : "") + (last == null
          ? "未測試：尚未檢查連線／內容"
          : "上次觀察：" + last + " · " + observation.getString(row.optString("id") + "_at", ""));
    } catch (Exception e) {
      return "失效：資料格式錯誤";
    }
  }

  private void paste() {
    EditText text = new EditText(activity);
    text.setHint("#EXTM3U\n#EXTINF:-1,頻道名稱\nhttps://…/stream.m3u8");
    text.setMinLines(6);
    new AlertDialog.Builder(activity)
        .setTitle("貼上自己的 M3U")
        .setView(text)
        .setPositiveButton("下一步", (d, w) -> prepare(text.getText().toString()))
        .setNegativeButton("取消", null)
        .show();
  }

  private void prepare(String text) {
    prepare(text, "貼上 M3U 文字");
  }

  private void prepare(String text, String origin) { prepare(text, origin, null); }

  private void prepare(String text, String origin, String batchId) {
    prepare(text, origin, batchId, origin.startsWith("https://") ? origin : null);
  }

  private void prepare(String text, String origin, String batchId, String parseBaseUrl) {
    if (activity.isFinishing() || activity.isDestroyed()) return;
    new Thread(() -> {
      try {
        if (!text.contains("#EXTM3U") && !text.contains("#EXTINF:"))
          throw new IllegalArgumentException("回應沒有 M3U 清單標記；原片單未改動");
        List<M3uPlaylist.Entry> parsed = parseBaseUrl != null
            ? M3uPlaylist.parseFromUrl(text, parseBaseUrl) : M3uPlaylist.parse(text);
        boolean hls = M3uPlaylist.isHlsManifest(text);
        activity.runOnUiThread(() -> showPrepared(parsed, hls, origin, batchId));
      } catch (Exception e) { activity.runOnUiThread(() -> error(e)); }
    }, "m3u-parse").start();
  }

  // New imports may omit malformed rows after an explicit preview. Updates remain atomic.
  static List<M3uPlaylist.Entry> importableEntries(List<M3uPlaylist.Entry> parsed) {
    List<M3uPlaylist.Entry> accepted = new ArrayList<>();
    for (M3uPlaylist.Entry entry : parsed) {
      try {
        URI uri = URI.create(entry.uri);
        if (uri.isAbsolute() && !uri.isOpaque() && uri.getHost() != null && uri.getUserInfo() == null)
          accepted.add(entry);
      } catch (IllegalArgumentException ignored) { /* Reported as omitted in the preview. */ }
    }
    return accepted;
  }

  private void showPrepared(List<M3uPlaylist.Entry> parsed, boolean hls, String origin, String batchId) {
    if (activity.isFinishing() || activity.isDestroyed()) return;
    final List<M3uPlaylist.Entry> incoming = batchId == null ? importableEntries(parsed) : parsed;
    int omitted = parsed.size() - incoming.size();
    if (incoming.isEmpty()) { error(new IOException("清單沒有格式有效的頻道網址；原片單未改動")); return; }
    LinearLayout form = column();
    if (omitted > 0) {
      List<String> names = new ArrayList<>();
      Set<M3uPlaylist.Entry> accepted = new HashSet<>(incoming);
      for (M3uPlaylist.Entry entry : parsed) {
        if (!accepted.contains(entry) && names.size() < 3) names.add(entry.name);
      }
      form.addView(label("將略過 " + omitted + " 個網址格式無效的條目："
          + android.text.TextUtils.join("、", names) + (omitted > names.size() ? "…" : "")
          + "。確認後只匯入其餘條目；沒有修寫無效網址。"));
    }
    Set<String> groups = new LinkedHashSet<>(), uris = new LinkedHashSet<>();
    int compatible = 0;
    for (M3uPlaylist.Entry entry : incoming) {
      if (entry.invalidReason() == null) compatible++;
      uris.add(entry.uri);
      String group = entry.attributes.getOrDefault("group-title", "").trim();
      groups.add(group.isEmpty() ? "未分組" : group);
    }
    JSONObject previous = batchId == null ? null : new PlaylistSubscriptions(activity).batch(batchId);
    if (batchId != null && previous == null) { error(new IOException("訂閱已移除")); return; }
    int excluded = previous == null || previous.optJSONArray("excluded") == null ? 0 : previous.optJSONArray("excluded").length();
    form.addView(label((hls ? (origin.startsWith("HTTP 單一") ? "指定 HTTP 網址（只保存、未下載；不代表已識別格式或可播放）" : "HLS 單一串流（不把片段當作頻道）") : "M3U 頻道目錄")
        + "\n" + uris.size() + " 個不同頻道 · " + groups.size() + " 個分組\n"
        + android.text.TextUtils.join("、", new ArrayList<>(groups).subList(0, Math.min(8, groups.size()))) + (groups.size() > 8 ? "…" : "")
        + (batchId == null ? "" : "\n更新只取代此訂閱；保留相同網址的頻道 ID。已排除 " + excluded + " 個網址。")
        + "\nHTTPS " + compatible + " 個；其他協定 " + (incoming.size() - compatible) + " 個。" + (BuildConfig.PERSONAL_BUILD ? "個人版 HTTP 預設開啟，可在首頁設定統一關閉；不支援的協定仍不能播放。" : "商業預覽僅支援 HTTPS 播放，HTTP 條目可整理但無法播放。")
        + "\n只讀取清單，尚未測試頻道播放。"));
    EditText region = new EditText(activity);
    region.setSingleLine(true);
    region.setHint("可使用地區代碼，例如 HK 或 HK,MO");
    region.setText(previous == null ? activity.getSharedPreferences("harbour", 0).getString("territory", "HK") : previous.optString("region", "HK"));
    form.addView(region);
    EditText until = new EditText(activity);
    until.setSingleLine(true);
    until.setHint("使用期限（UTC ISO-8601）");
    until.setText(previous == null ? Instant.now().plusSeconds(30L * 86400).toString() : previous.optString("until"));
    form.addView(until);
    CheckBox declaration = new CheckBox(activity);
    declaration.setText("我確認有權在以上地區及期限使用這些來源；這是本人聲明，並非平台驗證授權。");
    form.addView(declaration);
    ScrollView preview = new ScrollView(activity);
    preview.addView(form);
    AlertDialog dialog =
        new AlertDialog.Builder(activity)
            .setTitle(batchId == null ? "確認整份片單匯入" : "確認更新此訂閱")
            .setView(preview)
            .setPositiveButton("匯入", null)
            .setNegativeButton("取消", null)
            .create();
    dialog.setOnShowListener(
        d ->
            dialog
                .getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(
                    v -> {
                      try {
                        String territory =
                            region.getText().toString().trim().toUpperCase(Locale.ROOT);
                        if (!territory.matches("[A-Z]{2}(\\s*,\\s*[A-Z]{2})*"))
                          throw new IllegalArgumentException("請輸入兩字母地區代碼，以逗號分隔");
                        Instant end = Instant.parse(until.getText().toString().trim());
                        if (!end.isAfter(Instant.now()))
                          throw new IllegalArgumentException("期限必須在未來");
                        if (!declaration.isChecked())
                          throw new IllegalArgumentException("請確認使用權聲明");
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                        new Thread(() -> {
                          try {
                            new PlaylistSubscriptions(activity).replace(batchId, origin, territory, end.toString(), incoming);
                            activity.runOnUiThread(() -> {
                              if (activity.isFinishing() || activity.isDestroyed()) return;
                              if (changed != null) changed.run();
                              refresh(); dialog.dismiss();
                              Toast.makeText(activity, "已保存片單；依 group-title 分組，批次內重複網址已合併", Toast.LENGTH_LONG).show();
                            });
                          } catch (Exception e) {
                            activity.runOnUiThread(() -> {
                              dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true); error(e);
                            });
                          }
                        }, "m3u-save").start();
                      } catch (Exception e) {
                        error(e);
                      }
                    }));
    dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
    dialog.show();
  }

  /** Debug-only shortcuts. URLs are editable; normal preview, subscriptions and deletion apply. */
  private void personalPlaylists() {
    String[] names = {"best-fan · 內地完整片單", "best-fan · CCTV", "iptv-org · 全球片單", "Collect-IPTV · 最新整理片單"};
    String[] defaults = {
      "https://raw.githubusercontent.com/best-fan/iptv-sources/main/cn_all.m3u8",
      "https://raw.githubusercontent.com/best-fan/iptv-sources/main/cn_cctv.m3u8",
      "https://iptv-org.github.io/iptv/index.m3u",
      "https://raw.githubusercontent.com/zilong7728/Collect-IPTV/refs/heads/main/best_sorted.m3u"
    };
    android.content.SharedPreferences prefs = activity.getSharedPreferences("personal_playlist_shortcuts", 0);
    LinearLayout content = column();
    content.addView(label("個人測試片單：點名稱下載整份清單並預覽分組，再確認匯入。第三方來源可能失效或受地域限制；未驗證商業授權。HTTP 條目可匯入整理，個人版 HTTP 預設開啟，可在首頁設定關閉。"));
    content.addView(button("匯入已解碼測試片單", v -> {
      new Thread(() -> {
        try (InputStream stream = activity.getAssets().open("personal-tested.m3u")) {
          ByteArrayOutputStream bytes = new ByteArrayOutputStream();
          byte[] buffer = new byte[8192]; int count;
          while ((count = stream.read(buffer)) != -1) {
            if (bytes.size() + count > 1024 * 1024) throw new IOException("測試片單超過容量");
            bytes.write(buffer, 0, count);
          }
          String text = bytes.toString("UTF-8");
          activity.runOnUiThread(() -> prepare(text, "個人測試快照 · 2026-11-09 · 僅主機片段解碼，非實機保證", null));
        } catch (Exception failure) { activity.runOnUiThread(() -> error(failure)); }
      }, "personal-playlist").start();
    }));
    for (int i = 0; i < names.length; i++) {
      final String key = "url_" + i, original = defaults[i], title = names[i];
      content.addView(button("下載 · " + title, v -> download(prefs.getString(key, original), null)));
      content.addView(button("編輯網址 · " + title, v -> {
        EditText input = new EditText(activity);
        input.setSingleLine(true); input.setText(prefs.getString(key, original));
        AlertDialog edit = new AlertDialog.Builder(activity).setTitle(title).setView(input)
            .setPositiveButton("保存", null)
            .setNeutralButton("還原預設", (d, w) -> prefs.edit().remove(key).apply())
            .setNegativeButton("取消", null).create();
        edit.setOnShowListener(d -> edit.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(b -> {
          try {
            String value = input.getText().toString().trim();
            URI uri = URI.create(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null)
              throw new IllegalArgumentException("片單下載請使用完整 HTTPS 網址，不含帳密或片段標記");
            if (!prefs.edit().putString(key, value).commit()) throw new IOException("網址保存失敗");
            edit.dismiss();
          } catch (Exception failure) { error(failure); }
        }));
        edit.show();
      }));
    }
    ScrollView scroll = new ScrollView(activity); scroll.addView(content);
    new AlertDialog.Builder(activity).setTitle("常用片單 · 可編輯／整批匯入")
        .setView(scroll).setNegativeButton("返回", null).show();
  }

  private void fromUrl() {
    EditText input = new EditText(activity);
    input.setSingleLine(true);
    input.setHint("HTTPS 片單；HTTP 網址只保存，不下載或自動播放");
    new AlertDialog.Builder(activity)
        .setTitle("指定來源網址（不會搜尋網路）")
        .setView(input)
        .setPositiveButton(
            "下一步",
            (d, w) -> {
              String entered = input.getText().toString().trim();
              try {
                URI uri = URI.create(entered);
                if ("http".equalsIgnoreCase(uri.getScheme())) {
                  // Save user-provided metadata without contacting or granting this host.
                  showPrepared(Collections.singletonList(httpMetadata(entered)),
                      true, "HTTP 單一網址（只保存、未下載，非整份清單匯入）", null);
                } else download(entered, null);
              } catch (RuntimeException failure) { error(failure); }
            })
        .setNegativeButton("取消", null)
        .show();
  }

  static M3uPlaylist.Entry httpMetadata(String value) {
    URI u = URI.create(value);
    if (!"http".equalsIgnoreCase(u.getScheme()) || u.getHost() == null || u.getUserInfo() != null
        || u.getFragment() != null || value.length() > 8192)
      throw new IllegalArgumentException("請輸入完整 HTTP 網址，不含主機帳密或片段標記。");
    return M3uPlaylist.parse("#EXTM3U\n#EXTINF:-1,自備 HTTP 來源 · " + u.getHost() + "\n" + value + "\n").get(0);
  }

  public void openGithubPlaylist() {
    android.content.SharedPreferences saved = activity.getSharedPreferences("github_playlist", 0);
    String url = saved.getString("url", "");
    if (url.isEmpty()) { editGithubPlaylist(); return; }
    download(url,null);
  }

  private void editGithubPlaylist() {
    android.content.SharedPreferences saved = activity.getSharedPreferences("github_playlist", 0);
    EditText input = new EditText(activity);
    input.setSingleLine(true);
    input.setHint("https://github.com/…/blob/main/channels.m3u");
    input.setText(saved.getString("url", ""));
    AlertDialog prompt = new AlertDialog.Builder(activity).setTitle("保存 GitHub 片單")
      .setMessage("支援 GitHub 檔案頁或 raw 網址；請填入你有權使用的 M3U 清單。")
      .setView(input).setPositiveButton("保存並拉取",null).setNegativeButton("取消",null).create();
    prompt.setOnShowListener(v -> prompt.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
      String entered=input.getText().toString().trim();
      try {
        URI uri=URI.create(normalizePlaylistUrl(entered));
        if(!"https".equalsIgnoreCase(uri.getScheme()) || !"raw.githubusercontent.com".equalsIgnoreCase(uri.getHost())
            || uri.getUserInfo()!=null || uri.getFragment()!=null) throw new IllegalArgumentException();
        saved.edit().putString("url",entered).apply(); prompt.dismiss(); download(entered,null);
      } catch (IllegalArgumentException ex) { input.setError("請填入完整 GitHub 檔案頁或 raw HTTPS 網址"); }
    }));
    prompt.show();
  }

  public void openCctvPlaylist() { download("https://github.com/best-fan/iptv-sources/blob/main/cn_cctv.m3u8", null); }

  public void openUrlImport() { fromUrl(); }

  public void refreshSubscription(String batchId) {
    JSONObject batch = new PlaylistSubscriptions(activity).batch(batchId);
    if (batch == null || batch.optString("url").isEmpty()) {
      error(new IOException("此批次沒有可更新的 HTTPS 訂閱網址")); return;
    }
    download(batch.optString("url"), batchId);
  }

  private static final class SafeDownloadFailure extends IOException {
    SafeDownloadFailure(String message) { super(message); }
  }

  private void download(String enteredUrl, String batchId) {
    Toast.makeText(activity, "正在讀取指定清單…", Toast.LENGTH_SHORT).show();
    new Thread(() -> {
      try {
        String url = normalizePlaylistUrl(enteredUrl);
        URI uri = URI.create(url);
        if ("http".equalsIgnoreCase(uri.getScheme()))
          throw new SafeDownloadFailure("片單下載仍限 HTTPS。HTTP HLS 可從網址入口只保存，並在來源詳情逐來源同意後播放；來源須先明確同意 HTTP（包含轉址及分片）。");
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null)
          throw new SafeDownloadFailure("請輸入完整 HTTPS 網址，且勿將帳密放在網址主機部分。");
        HttpsPlaylistFetcher.Result fetched = HttpsPlaylistFetcher.fetch(url);
        String resolvedBatch = batchId;
        if (resolvedBatch == null) {
          JSONArray existing = new PlaylistSubscriptions(activity).batches();
          for (int i = 0; i < existing.length(); i++) {
            JSONObject b = existing.optJSONObject(i);
            if (b != null && (enteredUrl.equals(b.optString("url")) || url.equals(b.optString("url")))) {
              resolvedBatch = b.optString("id"); break;
            }
          }
        }
        final String targetBatch = resolvedBatch;
        activity.runOnUiThread(() -> prepare(fetched.text, enteredUrl, targetBatch, fetched.finalUrl));
      } catch (Exception e) {
        // Network exceptions can contain a signed URL: never display/log the raw exception.
        String safe = (e instanceof SafeDownloadFailure || e instanceof HttpsPlaylistFetcher.DownloadException) ? e.getMessage()
            : e instanceof java.net.SocketTimeoutException ? "讀取來源逾時，原片單未改動；請稍後重試或核對來源。"
            : e instanceof javax.net.ssl.SSLException ? "TLS 憑證驗證失敗，未降低安全驗證；請核對來源或網路。原片單未改動。"
            : "清單下載失敗；請檢查 HTTPS 網址、網路或容量（最多 8 MiB）。原片單未改動。";
        activity.runOnUiThread(() -> error(new IOException(safe)));
      }
    }, "m3u-subscription").start();
  }

  public void exportIds(Set<String> ids) {
    selected.clear(); selected.addAll(ids); exportSelected();
  }

  static String normalizePlaylistUrl(String raw) {
    URI uri = URI.create(raw);
    if ("https".equalsIgnoreCase(uri.getScheme())
        && "github.com".equalsIgnoreCase(uri.getHost())
        && uri.getUserInfo() == null
        && (uri.getPort() == -1 || uri.getPort() == 443)) {
      String[] parts = uri.getRawPath().split("/", 6);
      if (parts.length == 6 && parts[3].equals("blob") && parts[4].matches("[A-Za-z0-9._-]+"))
        return "https://raw.githubusercontent.com/"
            + parts[1]
            + "/"
            + parts[2]
            + "/"
            + parts[4]
            + "/"
            + parts[5];
    }
    return raw;
  }

  private boolean documentPickerAvailable(Intent intent) {
    android.content.pm.ResolveInfo info=activity.getPackageManager().resolveActivity(intent,android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
    return info!=null&&info.activityInfo!=null&&!"com.android.tv.frameworkpackagestubs".equals(info.activityInfo.packageName);
  }
  private void saveLocalExport() {
    final String exported=pendingExport;
    new Thread(()->{
      try {
        File root=activity.getExternalFilesDir(null);if(root==null)root=activity.getFilesDir();
        File dir=new File(root,"exports");if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("無法建立匯出目錄");
        File file=new File(dir,"shunpin-sources-"+System.currentTimeMillis()+".m3u");
        try(Writer writer=new OutputStreamWriter(new FileOutputStream(file),StandardCharsets.UTF_8)){writer.write(exported);}
        activity.runOnUiThread(()->{
          if(activity.isFinishing()||activity.isDestroyed())return;
          AlertDialog.Builder dialog=new AlertDialog.Builder(activity).setTitle("M3U 已保存至本機")
            .setMessage(file.getAbsolutePath()+"\n卸載 App 會移除此目錄；可先複製內容備份。")
            .setPositiveButton("知道了",null);
          if(exported.length()<200000)dialog.setNeutralButton("複製 M3U",(d,w)->{
            ((android.content.ClipboardManager)activity.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(android.content.ClipData.newPlainText("M3U",exported));
            Toast.makeText(activity,"已複製 M3U 文字",Toast.LENGTH_SHORT).show();
          });
          dialog.show();
        });
      }catch(Exception e){activity.runOnUiThread(()->error(e));}
    },"m3u-local-export").start();
  }

  private void exportSelected() {
    Set<String> exportSelection = new HashSet<>(selected);
    new Thread(() -> buildExport(exportSelection), "m3u-export").start();
  }

  private void buildExport(Set<String> exportSelection) {
    try {
      JSONArray data = load(activity);
      StringBuilder out = new StringBuilder("#EXTM3U\n");
      int count = 0;
      for (int i = 0; i < data.length(); i++) {
        JSONObject row = data.getJSONObject(i);
        if (!exportSelection.contains(row.getString("id"))) continue;
        for (M3uPlaylist.Entry e : M3uPlaylist.parse(row.getString("m3u"))) {
          out.append("#SHUNPIN-ORIGIN:")
              .append(row.optString("origin", "匯入資料").replace("\n", " ").replace("\r", " "))
              .append("\n#SHUNPIN-USER-DECLARATION:unverified\n#SHUNPIN-REGION:")
              .append(row.getString("region"))
              .append("\n#SHUNPIN-VALID-UNTIL:")
              .append(row.getString("until"))
              .append('\n')
              .append(e.metadata)
              .append(e.uri)
              .append('\n');
          count++;
        }
      }
      if (count == 0) throw new IllegalArgumentException("先勾選至少一個來源");
      pendingExport = out.toString();
      Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
      i.addCategory(Intent.CATEGORY_OPENABLE);
      i.setType("audio/x-mpegurl");
      i.putExtra(Intent.EXTRA_TITLE, "shunpin-sources.m3u");
      activity.runOnUiThread(() -> {
        if (!activity.isFinishing() && !activity.isDestroyed()) {
          if(documentPickerAvailable(i)) {try {activity.startActivityForResult(i, SAVE);}catch(ActivityNotFoundException missing){saveLocalExport();}}
          else saveLocalExport();
        }
      });
    } catch (Exception e) {
      activity.runOnUiThread(() -> error(e));
    }
  }

  public boolean onActivityResult(int requestCode, int resultCode, Intent data) {
    if (requestCode != OPEN && requestCode != SAVE) return false;
    if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return true;
    Uri uri = data.getData();
    try {
      if (requestCode == OPEN) {
        new Thread(() -> {
          try (InputStream in = activity.getContentResolver().openInputStream(uri)) {
            String text = read(in);
            activity.runOnUiThread(() -> prepare(text, "文件：" + uri.toString()));
          } catch (Exception e) { activity.runOnUiThread(() -> error(e)); }
        }, "m3u-document").start();
      } else {
        if (pendingExport == null) throw new IOException("匯出已中斷，請重新勾選匯出");
        final String exportText = pendingExport;
        pendingExport = null;
        new Thread(() -> {
          try (OutputStream out = activity.getContentResolver().openOutputStream(uri, "wt")) {
            if (out == null) throw new IOException("無法開啟輸出文件");
            out.write(exportText.getBytes(StandardCharsets.UTF_8));
          } catch (Exception e) {
            activity.runOnUiThread(() -> error(e));
            return;
          }
          activity.runOnUiThread(() -> {
            if (!activity.isFinishing() && !activity.isDestroyed())
              Toast.makeText(activity, "M3U 組合已匯出（保留 metadata）", Toast.LENGTH_LONG).show();
          });
        }, "m3u-document-save").start();
      }
    } catch (Exception e) {
      error(e);
    }
    return true;
  }

  private static String read(InputStream in) throws IOException {
    if (in == null) throw new IOException("無法開啟文件");
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] b = new byte[8192];
    int n;
    while ((n = in.read(b)) != -1) {
      if (out.size() + n > M3uPlaylist.MAX_CHARS) throw new IOException("文件超過 8 MiB 上限");
      out.write(b, 0, n);
    }
    return new String(out.toByteArray(), StandardCharsets.UTF_8);
  }

  private void error(Exception e) {
    if (activity.isFinishing() || activity.isDestroyed()) return;
    new AlertDialog.Builder(activity)
        .setTitle("來源操作未完成")
        .setMessage(e.getMessage() == null ? "請檢查來源格式" : e.getMessage())
        .setPositiveButton("知道了", null)
        .show();
  }

  private static String observationKey(String uri) {
    try {
      byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(uri.getBytes(StandardCharsets.UTF_8));
      char[] hex="0123456789abcdef".toCharArray(),key=new char[digest.length*2];
      for(int i=0;i<digest.length;i++){key[i*2]=hex[(digest[i]&255)>>>4];key[i*2+1]=hex[digest[i]&15];}return new String(key);
    } catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
  }

  static void recordContinuousPlayback(Context context,String uri) {
    context.getSharedPreferences("source_continuous_observation",0).edit()
      .putString(observationKey(uri),Instant.now().toString()).apply();
  }

  // Development-machine observations on 2026-11-11; never recorded as this device's evidence.
  static boolean hasDevelopmentPlaybackObservation(String uri) { return false; }

  static boolean hasContinuousPlaybackObservation(Context context,String uri) {
    return context.getSharedPreferences("source_continuous_observation",0).contains(observationKey(uri));
  }

  static String continuousPlaybackSummary(Context context,String uri) {
    String at=context.getSharedPreferences("source_continuous_observation",0).getString(observationKey(uri),null);
    return at==null?"未有本機連播 60 秒紀錄 · ":"本機曾連播 60 秒 · "+at+"（歷史觀察，不保證目前可播）\n";
  }

  public static void recordPlaybackStatus(Context context, String channelId, String status) {
    context
        .getSharedPreferences("source_status", 0)
        .edit()
        .putString(channelId, status)
        .putString(channelId + "_at", Instant.now().toString())
        .apply();
  }

  public static Map<String, String> groupByChannel(Context context) {
    Map<String, String> groups = new LinkedHashMap<>();
    JSONArray data = load(context);
    for (int i = 0; i < data.length(); i++) {
      try {
        JSONObject r = data.getJSONObject(i);
        M3uPlaylist.Entry e = M3uPlaylist.parse(r.getString("m3u")).get(0);
        String group = e.attributes.getOrDefault("group-title", "自訂來源");
        if (!group.trim().isEmpty()) groups.put(r.getString("id"), group);
      } catch (Exception ignored) {
      }
    }
    return groups;
  }

  private void offerNetworkSample() {
    AlertDialog confirmation = new AlertDialog.Builder(activity)
        .setTitle("公開 HLS 播放測試")
        .setMessage(
            "由 Mux 公開提供的 Big Buck Bunny"
                + " 開發測試影片。這是VOD，不是電視直播，也不是商業頻道授權；可用性取決於目前網路與所在地。將加入你的本機片單，供主動測試。")
        .setNegativeButton("取消", null)
        .setPositiveButton(
            "加入並測試",
            (d, w) -> {
              new Thread(() -> {
              try {
                String url =
                    "https://test-streams.mux.dev/x36xhzz/url_2/193039199_mp4_h264_aac_ld_7.m3u8";
                  JSONObject row = new JSONObject();
                  row.put("id", "user-mux-test");
                  row.put("name", "Big Buck Bunny · 公開網路測試");
                  row.put(
                      "m3u",
                      M3uPlaylist.export(
                          Collections.singletonList(
                              M3uPlaylist.fromStreamUrl("Big Buck Bunny · 公開網路測試", url))));
                  row.put(
                      "region",
                      activity.getSharedPreferences("harbour", 0).getString("territory", "HK"));
                  row.put("from", Instant.now().minusSeconds(1).toString());
                  row.put("until", Instant.now().plusSeconds(30L * 86400).toString());
                  row.put("declared", true);
                  row.put(
                      "origin",
                      "https://test-streams.mux.dev/ · Mux公開開發測試，非商業頻道；Big Buck Bunny © 2008"
                          + " Blender Foundation / www.bigbuckbunny.org，CC BY 3.0");
                  new PlaylistSubscriptions(activity).addStandaloneIfAbsent(row);
                activity
                    .getSharedPreferences("harbour", 0)
                    .edit()
                    .putString("requested_play", "user-mux-test")
                    .apply();
                activity.runOnUiThread(() -> {
                  if (activity.isFinishing() || activity.isDestroyed()) return;
                  if (managerDialog != null) managerDialog.dismiss();
                  if (changed != null) changed.run();
                  Toast.makeText(activity, "已要求播放公開測試影片", Toast.LENGTH_LONG).show();
                });
              } catch (Exception e) {
                activity.runOnUiThread(() -> error(e));
              }
              }, "m3u-add-sample").start();
            })
        .create();
    confirmation.show();
    confirmation.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).requestFocus();
  }

  static String replayDisclosure(Context context, String channelId) {
    if (channelId != null) {
      JSONArray rows = load(context);
      for (int i=0;i<rows.length();i++) {
        JSONObject row = rows.optJSONObject(i);
        if (row != null && channelId.equals(row.optString("id"))) {
          try { return M3uPlaylist.parse(row.getString("m3u")).get(0).replayDisclosure(); }
          catch (Exception invalid) { return "無法讀取來源回放資訊；回放未啟用。"; }
        }
      }
    }
    return "此直播來源未接入節目回放；直播暫停或時移不等於已播節目回放。";
  }

  public static Catalog merge(Context context, Catalog base) {
    List<Catalog.Channel> channels = new ArrayList<>(base.channels);
    HttpPlaybackConsent httpPolicy = new HttpPlaybackConsent(context);
    JSONArray data = load(context);
    for (int i = 0; i < data.length(); i++) {
      try {
        JSONObject row = data.getJSONObject(i);
        M3uPlaylist.Entry e = M3uPlaylist.parse(row.getString("m3u")).get(0);
        String id = row.getString("id");
        Set<String> territories =
            new HashSet<>(Arrays.asList(row.getString("region").replace(" ", "").split(",")));
        Catalog.Source source =
            new Catalog.Source(
                id + "-stream",
                e.uri,
                e.mimeType(),
                row.optBoolean("declared")
                    ? Catalog.Rights.USER_PROVIDED
                    : Catalog.Rights.UNVERIFIED,
                territories,
                Instant.parse(row.getString("from")),
                Instant.parse(row.getString("until")),
                "使用者自行聲明；未經平台核實", httpPolicy);
        String description =
            "使用者提供 · "
                + row.getString("region")
                + " · "
                + status(context, row)
                + " · "
                + e.attributes.getOrDefault("group-title", "自訂來源");
        channels.add(
            new Catalog.Channel(
                id,
                String.valueOf(800 + i),
                e.name,
                Catalog.Category.ALL,
                e.attributes.getOrDefault("tvg-language", "未指定"),
                description,
                "#64D8C8",
                Collections.singletonList(source)));
      } catch (Exception ignored) {
        /* Corrupt individual records do not hide the bundled catalog. */
      }
    }
    return new Catalog(base.provider, base.sourceReference, base.updatedAt, channels);
  }
}
