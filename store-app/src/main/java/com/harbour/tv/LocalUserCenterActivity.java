package com.harbour.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputFilter;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import com.harbour.core.Catalog;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;

/** Device-local profile only; no authentication, subscription, or cloud account is implied. */
@androidx.media3.common.util.UnstableApi
public final class LocalUserCenterActivity extends Activity {
  private static final int BG = 0xff0b1424, PANEL = 0xff142238, CARD = 0xff1b2b42;
  private static final int TEXT = 0xfff0f5fb, MUTED = 0xffa9bbcf, TEAL = 0xff58e0c0;
  private static final String NICKNAME = "local_nickname";
  private SharedPreferences prefs;
  private LinearLayout content;
  private TextView profileName;
  private final List<Button> navigation = new ArrayList<>();
  private final List<Button> actions = new ArrayList<>();
  private int section;
  private Catalog catalog;

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    getWindow()
        .setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
    prefs = getSharedPreferences("harbour", MODE_PRIVATE);
    try {
      catalog = new CatalogRepository(this).catalog;
    } catch (Exception ignored) {
    }
    if (state != null) section = state.getInt("section", 0);
    section = Math.max(0, Math.min(3, section));
    LinearLayout root = row();
    root.setBackgroundColor(BG);
    root.setPadding(dp(28), dp(24), dp(28), dp(24));
    LinearLayout sidebar = column();
    sidebar.setPadding(dp(18), dp(22), dp(18), dp(16));
    sidebar.setBackground(shape(PANEL, 0));
    root.addView(sidebar, new LinearLayout.LayoutParams(dp(214), -1));
    sidebar.addView(label("瞬頻 TV  /  我的電視", 13, TEAL, true));
    profileName = label(nickname(), 23, TEXT, true);
    profileName.setMaxLines(2);
    addSpace(sidebar, 12);
    sidebar.addView(profileName);
    sidebar.addView(label("本機個人檔案", 13, MUTED, false));
    addSpace(sidebar, 14);
    String[] titles = {"總覽與收藏", "最近頻道", "個人偏好", "資料與服務"};
    for (int i = 0; i < titles.length; i++) {
      final int target = i;
      Button button = button(titles[i], () -> showSection(target));
      navigation.add(button);
      addButton(sidebar, button);
    }
    View spacer = new View(this);
    sidebar.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1));
    Button back = button("返回電視", this::finish);
    addButton(sidebar, back);
    sidebar.addView(label("方向鍵移動 · OK 選擇", 11, MUTED, false));
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    scroll.setClipToPadding(false);
    content = column();
    content.setPadding(dp(26), dp(4), dp(4), dp(16));
    scroll.addView(content);
    root.addView(scroll, new LinearLayout.LayoutParams(0, -1, 1));
    setContentView(root);
    showSection(section);
    navigation.get(section).requestFocus();
  }

  @Override
  protected void onSaveInstanceState(Bundle out) {
    out.putInt("section", section);
    super.onSaveInstanceState(out);
  }

  @Override
  protected void onResume() {
    super.onResume();
    if (content != null) {
      int focusedAction = actions.indexOf(getCurrentFocus());
      boolean inNavigation = navigation.contains(getCurrentFocus());
      showSection(section);
      if (focusedAction >= 0 && focusedAction < actions.size())
        actions.get(focusedAction).requestFocus();
      else if (inNavigation || getCurrentFocus() == null) navigation.get(section).requestFocus();
    }
  }

  private void showSection(int index) {
    section = index;
    content.removeAllViews();
    actions.clear();
    profileName.setText(nickname());
    for (int i = 0; i < navigation.size(); i++) {
      Button nav = navigation.get(i);
      nav.setSelected(i == index);
      styleButton(nav, nav.hasFocus());
    }
    String[] headings = {"你的電視，你的收藏", "最近播放的頻道", "讓這部電視更像你的", "本機資料與服務"};
    content.addView(label("使用者中心  /  本機模式", 13, TEAL, true));
    addSpace(content, 8);
    content.addView(label(headings[index], 29, TEXT, true));
    addSpace(content, 8);
    content.addView(label("資料保存在這部裝置；目前未連接帳號後端。", 14, MUTED, false));
    addSpace(content, 22);
    if (index == 0) overview();
    else if (index == 1) recent();
    else if (index == 2) preferences();
    else dataAndServices();
    wireFocus();
  }

  private void overview() {
    LinearLayout stats = row();
    stat(stats, String.valueOf(favorites().size()), "已儲存收藏");
    stat(stats, lastPlayed().isEmpty() ? "0" : "1", "最近頻道");
    stat(stats, sourceCount(), "已儲存來源");
    content.addView(stats);
    LinearLayout card = card("我的收藏");
    List<String> ids = favorites();
    if (ids.isEmpty()) {
      card.addView(label("還沒有收藏頻道", 20, TEXT, true));
      card.addView(label("在電視頁選擇頻道，再按「加入收藏」，即可保存在本機。", 15, MUTED, false));
    } else {
      int visible = Math.min(8, ids.size());
      for (int i = 0; i < visible; i++)
        card.addView(label("★  " + channelName(ids.get(i)), 17, TEXT, false));
      if (ids.size() > visible)
        card.addView(label("另有 " + (ids.size() - visible) + " 個收藏；請在電視頁查看。", 14, MUTED, false));
      card.addView(label("此處顯示已儲存的收藏；來源移除後，舊項目可能已無法播放。", 13, MUTED, false));
    }
    action(card, "返回電視查看頻道", this::finish);
  }

  private void recent() {
    String id = lastPlayed();
    LinearLayout card = card("只保留最近一次頻道");
    card.addView(label(id.isEmpty() ? "尚無最近頻道" : channelName(id), 25, TEXT, true));
    card.addView(label("本機僅儲存最後播放的頻道識別碼，沒有完整觀看歷史、觀看時間或播放進度。", 16, MUTED, false));
    if (!id.isEmpty()) {
      action(
          card,
          "清除最近頻道",
          () ->
              confirm(
                  "清除最近頻道？",
                  "只移除本機最近頻道記錄，收藏與來源會保留。",
                  () -> {
                    prefs.edit().remove("lastPlayed").apply();
                    showSection(1);
                    focusFirst();
                  }));
    }
    action(card, "返回電視", this::finish);
  }

  private void preferences() {
    LinearLayout card = card("本機暱稱");
    card.addView(label(nickname(), 25, TEXT, true));
    card.addView(label("只用於這部裝置的使用者中心，不是登入帳號，也不會傳送至雲端。", 16, MUTED, false));
    action(card, "編輯暱稱", this::editNickname);
    if (!prefs.getString(NICKNAME, "").isEmpty()) {
      action(
          card,
          "重設暱稱",
          () ->
              confirm(
                  "重設本機暱稱？",
                  "將恢復為「電視訪客」，不影響收藏或來源。",
                  () -> {
                    prefs.edit().remove(NICKNAME).apply();
                    showSection(2);
                    focusFirst();
                  }));
    }
    LinearLayout note = card("遙控器操作");
    note.addView(label("使用方向鍵切換選項，按 OK 開啟或確認。按返回鍵關閉對話框或返回電視。", 16, MUTED, false));
  }

  private void dataAndServices() {
    LinearLayout local = card("來源資料管理");
    local.addView(label("已儲存來源：" + sourceCount(), 22, TEXT, true));
    local.addView(label("管理本機匯入來源與來源資料。來源數量不代表可播放頻道數或有效訂閱。", 15, MUTED, false));
    action(
        local,
        "開啟來源管理",
        () -> {
          Intent intent = new Intent(this, MainActivity.class);
          intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
          intent.putExtra("open_sources", true);
          startActivity(intent);
          finish();
        });
    LinearLayout service = card("帳號與雲端服務  ·  尚未連接");
    service.addView(label("登入與雲端同步", 18, TEXT, true));
    service.addView(label("尚未接入帳號後端；暱稱、收藏與最近頻道僅存於本機。", 15, MUTED, false));
    addSpace(service, 10);
    service.addView(label("會員訂閱與節目回看", 18, TEXT, true));
    service.addView(label("需由服務商提供帳號、訂閱資格、授權內容及回看服務；目前無法在此登入、購買或啟用。", 15, MUTED, false));
  }

  private void editNickname() {
    EditText input = new EditText(this);
    input.setSingleLine(true);
    input.setTextColor(TEXT);
    input.setTextSize(20);
    input.setHintTextColor(MUTED);
    input.setHint("最多 24 個字元");
    input.setFilters(new InputFilter[] {new InputFilter.LengthFilter(24)});
    input.setText(prefs.getString(NICKNAME, ""));
    input.setSelectAllOnFocus(true);
    input.setPadding(dp(20), dp(12), dp(20), dp(12));
    AlertDialog dialog =
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("編輯本機暱稱")
            .setMessage("留空將顯示「電視訪客」。")
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton(
                "儲存",
                (d, which) -> {
                  String value = input.getText().toString().trim();
                  prefs.edit().putString(NICKNAME, value).apply();
                  showSection(2);
                  focusFirst();
                  Toast.makeText(this, "本機暱稱已儲存", Toast.LENGTH_SHORT).show();
                })
            .create();
    dialog.show();
    input.requestFocus();
  }

  private void confirm(String title, String message, Runnable action) {
    AlertDialog dialog =
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton("取消", null)
            .setPositiveButton("確認清除", (d, which) -> action.run())
            .create();
    dialog.show();
    dialog.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus();
  }

  private String nickname() {
    String value = prefs.getString(NICKNAME, "").trim();
    return value.isEmpty() ? "電視訪客" : value;
  }

  private String lastPlayed() {
    return prefs.getString("lastPlayed", "");
  }

  private List<String> favorites() {
    List<String> ids = new ArrayList<>(prefs.getStringSet("favorites", Collections.emptySet()));
    Collections.sort(ids);
    return ids;
  }

  private String sourceCount() {
    try {
      return String.valueOf(
          new JSONArray(
                  getSharedPreferences("user_sources", MODE_PRIVATE).getString("entries", "[]"))
              .length());
    } catch (Exception e) {
      return "讀取失敗";
    }
  }

  private String channelName(String id) {
    Catalog.Channel channel = catalog == null ? null : catalog.find(id);
    return channel == null ? "頻道 ID：" + id : channel.number + "  " + channel.name;
  }

  private void stat(LinearLayout target, String value, String title) {
    LinearLayout box = column();
    box.setPadding(dp(16), dp(14), dp(16), dp(14));
    box.setBackground(shape(PANEL, 0));
    box.addView(label(value, 30, TEAL, true));
    box.addView(label(title, 14, MUTED, false));
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -1, 1);
    params.rightMargin = dp(8);
    target.addView(box, params);
  }

  private LinearLayout card(String title) {
    LinearLayout box = column();
    box.setPadding(dp(22), dp(18), dp(22), dp(18));
    box.setBackground(shape(PANEL, 0));
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
    params.topMargin = dp(14);
    content.addView(box, params);
    box.addView(label(title, 14, TEAL, true));
    addSpace(box, 10);
    return box;
  }

  private void action(LinearLayout parent, String title, Runnable runnable) {
    Button button = button(title, runnable);
    actions.add(button);
    addButton(parent, button);
  }

  private void wireFocus() {
    for (int i = 0; i < navigation.size(); i++) {
      Button nav = navigation.get(i);
      nav.setNextFocusUpId(navigation.get(Math.max(0, i - 1)).getId());
      if (i + 1 < navigation.size()) nav.setNextFocusDownId(navigation.get(i + 1).getId());
      nav.setNextFocusRightId(actions.isEmpty() ? nav.getId() : actions.get(0).getId());
    }
    for (int i = 0; i < actions.size(); i++) {
      Button button = actions.get(i);
      button.setNextFocusLeftId(navigation.get(section).getId());
      button.setNextFocusUpId(actions.get(Math.max(0, i - 1)).getId());
      button.setNextFocusDownId(actions.get(Math.min(actions.size() - 1, i + 1)).getId());
    }
  }

  private void focusFirst() {
    if (!actions.isEmpty()) actions.get(0).requestFocus();
    else navigation.get(section).requestFocus();
  }

  private Button button(String text, Runnable runnable) {
    Button button = new Button(this);
    button.setId(View.generateViewId());
    button.setText(text);
    button.setAllCaps(false);
    button.setTextSize(15);
    button.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
    button.setPadding(dp(16), dp(8), dp(16), dp(8));
    button.setMinHeight(dp(48));
    button.setMinimumHeight(dp(48));
    button.setFocusable(true);
    button.setOnClickListener(v -> runnable.run());
    button.setOnFocusChangeListener((v, focused) -> styleButton(button, focused));
    styleButton(button, false);
    return button;
  }

  private void styleButton(Button button, boolean focused) {
    button.setTextColor(focused ? BG : button.isSelected() ? TEAL : TEXT);
    button.setBackground(shape(focused ? TEAL : CARD, button.isSelected() ? TEAL : 0));
    button.setTypeface(
        Typeface.DEFAULT, focused || button.isSelected() ? Typeface.BOLD : Typeface.NORMAL);
    button.setElevation(focused ? dp(6) : 0);
  }

  private void addButton(LinearLayout parent, Button button) {
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(48));
    params.topMargin = dp(9);
    parent.addView(button, params);
  }

  private TextView label(String text, int size, int color, boolean bold) {
    TextView view = new TextView(this);
    view.setText(text);
    view.setTextSize(size);
    view.setTextColor(color);
    view.setLineSpacing(dp(3), 1);
    if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    return view;
  }

  private GradientDrawable shape(int color, int border) {
    GradientDrawable drawable = new GradientDrawable();
    drawable.setColor(color);
    drawable.setCornerRadius(dp(12));
    if (border != 0) drawable.setStroke(dp(1), border);
    return drawable;
  }

  private LinearLayout column() {
    LinearLayout layout = new LinearLayout(this);
    layout.setOrientation(LinearLayout.VERTICAL);
    return layout;
  }

  private LinearLayout row() {
    LinearLayout layout = new LinearLayout(this);
    layout.setOrientation(LinearLayout.HORIZONTAL);
    return layout;
  }

  private void addSpace(LinearLayout parent, int height) {
    parent.addView(new View(this), new LinearLayout.LayoutParams(1, dp(height)));
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }
}
