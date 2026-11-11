package com.harbour.tv;

import android.app.*;
import android.content.Intent;
import android.os.*;
import android.text.*;
import android.view.*;
import android.widget.*;
import com.harbour.core.Catalog;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Local-only explicit mapping. No network fetch or playback is performed here. */
public final class SourceBindingActivity extends Activity {
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final Handler main = new Handler(Looper.getMainLooper());
  private String presetId, territory;
  private CatalogRepository repo;
  private TextView status;
  private EditText search;
  private ListView list;
  private Button remove;
  private boolean saving;
  private int generation;
  private final List<Catalog.Channel> candidates = new ArrayList<>();

  @Override public void onCreate(Bundle state) {
    super.onCreate(state);
    presetId = getIntent().getStringExtra("preset_id");
    territory = getSharedPreferences("harbour", 0).getString("territory", "HK");
    LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
    root.setPadding(dp(24), dp(16), dp(24), dp(16)); root.setBackgroundColor(0xFF101F32);
    root.addView(text("連結已匯入來源", 24));
    root.addView(text("只建立你的本機對應，不會自動核實同名頻道或節目身份。保留原來源的地區、期限與 HTTP 同意條件。", 15));
    search = new EditText(this); search.setSingleLine(true); search.setFilters(new InputFilter[] {new InputFilter.LengthFilter(200)}); search.setTextColor(0xFFF4F7FC);
    search.setHintTextColor(0xFFA8BACB); search.setHint("搜尋已匯入來源名稱或主機"); root.addView(search);
    status = text("正在讀取本地來源…", 15); root.addView(status);
    remove = button("解除目前連結", () -> confirmRemove()); remove.setVisibility(View.GONE); root.addView(remove);
    list = new ListView(this); list.setItemsCanFocus(false); root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
    Button back = button("返回頻道詳情", () -> onBackPressed()); root.addView(back);
    setContentView(root); back.requestFocus();
    search.addTextChangedListener(new TextWatcher() {
      public void beforeTextChanged(CharSequence s,int start,int count,int after) {}
      public void onTextChanged(CharSequence s,int start,int before,int count) { filter(s.toString()); }
      public void afterTextChanged(Editable s) {}
    });
    worker.execute(() -> {
      try {
        CatalogRepository loaded = new CatalogRepository(getApplicationContext());
        if (!loaded.isPreset(presetId)) throw new IllegalArgumentException("此預設頻道已不存在");
        List<Catalog.Channel> users = new ArrayList<>();
        for (Catalog.Channel c : loaded.catalog.channels)
          if (c.id.startsWith("user-") && !c.sources.isEmpty()) users.add(c);
        ui(() -> {
          repo = loaded; candidates.addAll(users);
          remove.setVisibility(repo.linkedUserChannel(presetId) == null ? View.GONE : View.VISIBLE);
          filter(search.getText().toString()); search.requestFocus();
        });
      } catch (Exception e) { ui(() -> status.setText("讀取失敗，請返回重試。")); }
    });
  }
  private void filter(String query) {
    if (repo == null || saving) return;
    int request = ++generation;
    String q = query.trim().toLowerCase(Locale.ROOT);
    List<Catalog.Channel> snapshot = new ArrayList<>(candidates);
    worker.execute(() -> {
      List<Catalog.Channel> found = new ArrayList<>();
      for (Catalog.Channel c : snapshot)
        if ((c.name + " " + SourceBindingStore.hosts(c)).toLowerCase(Locale.ROOT).contains(q)) found.add(c);
      ui(() -> {
        if (request != generation || saving) return;
        String linked = repo.bindingLabel(presetId);
        status.setText("預設：" + repo.catalog.find(presetId).name + (linked.isEmpty() ? "" : "\n" + linked)
            + "\n" + found.size() + " 個符合來源。" + (found.isEmpty() ? "請返回首頁的來源管理匯入，或調整搜尋。" : "選擇後會再次確認。"));
        list.setAdapter(new BaseAdapter() {
          public int getCount() { return found.size(); }
          public Object getItem(int p) { return found.get(p); }
          public long getItemId(int p) { return p; }
          public View getView(int p, View recycled, ViewGroup parent) {
            TextView row = recycled instanceof TextView ? (TextView) recycled : text("", 17);
            Catalog.Channel c = found.get(p);
            row.setText(c.name + "\n" + SourceBindingStore.hosts(c) + " · " + sourceState(c));
            row.setPadding(dp(12), dp(10), dp(12), dp(10)); row.setMaxLines(3); return row;
          }
        });
        list.setOnItemClickListener((parent, v, position, id) -> confirmLink(found.get(position)));
      });
    });
  }
  private String sourceState(Catalog.Channel c) {
    String blocked = c.sources.get(0).blockedReason(territory, Instant.now());
    return blocked == null ? "符合本地播放條件，實播未驗證" : blocked;
  }
  private void confirmLink(Catalog.Channel user) {
    if (saving) return;
    AlertDialog dialog = new AlertDialog.Builder(this).setTitle("確認連結至「" + repo.catalog.find(presetId).name + "」？")
        .setMessage("原來源：" + user.name + "\n主機：" + SourceBindingStore.hosts(user)
            + "\n" + sourceState(user) + "\n這是你的手動對應，不表示原來源就是該電視頻道；不會立即播放。")
        .setNegativeButton("取消", null).setPositiveButton("確認連結", (d,w) -> save(user)).create();
    dialog.show(); dialog.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus();
  }
  private void confirmRemove() {
    if (repo == null || saving) return;
    AlertDialog dialog = new AlertDialog.Builder(this).setTitle("解除此頻道連結？")
        .setMessage("只解除對應，不刪除匯入來源或其 HTTP 同意設定。")
        .setNegativeButton("取消", null).setPositiveButton("解除", (d,w) -> save(null)).create();
    dialog.show(); dialog.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus();
  }
  private void save(Catalog.Channel selected) {
    saving = true; generation++; search.setEnabled(false); list.setEnabled(false); remove.setEnabled(false);
    status.setText("正在保存連結… 返回會在保存完成後離開。");
    worker.execute(() -> {
      try {
        SourceBindingStore store = new SourceBindingStore(getApplicationContext());
        if (selected == null) store.unlink(presetId);
        else {
          CatalogRepository fresh = new CatalogRepository(getApplicationContext());
          Catalog.Channel actual = fresh.catalog.find(selected.id);
          if (actual == null || !sameSources(selected, actual))
            throw new IllegalArgumentException("來源已變更，請返回重新選擇");
          store.link(fresh, presetId, selected.id);
        }
        ui(() -> { saving = false; setResult(RESULT_OK, new Intent().putExtra("preset_id", presetId)); finish(); });
      } catch (Exception e) {
        ui(() -> {
          saving = false; search.setEnabled(true); list.setEnabled(true); remove.setEnabled(true);
          status.setText("連結未保存，請返回重試。");
          new AlertDialog.Builder(this).setMessage(e instanceof IllegalArgumentException ? e.getMessage() : "無法保存連結，原資料未改動。")
              .setPositiveButton("知道了", null).show();
        });
      }
    });
  }
  private static boolean sameSources(Catalog.Channel a, Catalog.Channel b) {
    if (!a.name.equals(b.name) || a.sources.size() != b.sources.size()) return false;
    for (int i=0;i<a.sources.size();i++) {
      Catalog.Source x=a.sources.get(i), y=b.sources.get(i);
      if (!x.id.equals(y.id) || !x.uri.equals(y.uri) || x.rights!=y.rights
          || !x.territories.equals(y.territories) || !x.validUntil.equals(y.validUntil)) return false;
    }
    return true;
  }
  @Override public void onBackPressed() { if (saving) { status.setText("正在保存，完成後返回…"); } else finish(); }
  @Override public void onDestroy() { generation++; main.removeCallbacksAndMessages(null); worker.shutdown(); super.onDestroy(); }
  private void ui(Runnable r) { main.post(() -> { if (!isFinishing() && !isDestroyed()) r.run(); }); }
  private int dp(int n) { return Math.round(n*getResources().getDisplayMetrics().density); }
  private TextView text(String value,int size) { TextView t=new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(0xFFF4F7FC); return t; }
  private Button button(String name,Runnable action) { Button b=new Button(this);b.setText(name);b.setOnClickListener(v->action.run());return b; }
}
