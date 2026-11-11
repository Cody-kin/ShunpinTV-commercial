package com.harbour.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.harbour.core.OfficialDestinations;

/** External official landing pages; not an embedded short-drama player or account integration. */
public final class OfficialServicesActivity extends Activity {
  private static final int BG = 0xff0b1424, PANEL = 0xff16273c, TEAL = 0xff63e2c5;
  @Override public void onCreate(Bundle state) {
    super.onCreate(state);
    if(!BuildConfig.PERSONAL_BUILD){finish();return;}
    getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
    LinearLayout root = column();
    root.setPadding(dp(38), dp(24), dp(38), dp(20)); root.setBackgroundColor(BG);
    root.addView(text("官方短視頻與短劇入口", 29, TEAL));
    root.addView(text("官方網站入口與紅果 App 內選集 · 需要網路", 15, 0xffb9c9d8));
    ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
    LinearLayout body = column();
    scroll.addView(body); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    Button first = service(body, OfficialDestinations.Destination.DOUYIN,
        "短視頻、直播與搜尋，以抖音網站目前提供的服務為準。",
        "可能需要官方登入或驗證；網站可能偏向手機／電腦操作，遙控器體驗不保證。");
    service(body, OfficialDestinations.Destination.HONGGUO,
        "紅果官方短劇網站與 App 資訊，以網站當前頁面為準。",
        "是否能在此電視播放、是否需 App、登入或地區條件，皆由服務商決定。");
    Button publicDrama = button("貼上紅果分享頁 · 選集與官方頁播放", () ->
        startActivity(new Intent(this, PublicDramaActivity.class)));
    LinearLayout.LayoutParams importParams = new LinearLayout.LayoutParams(-1, dp(52));
    importParams.topMargin = dp(16);
    body.addView(publicDrama, importParams);
    TextView note = text("紅果選集可在 App 內開啟官方原頁。尚未整合官方 TV 播放 API、登入或授權短劇庫；網站是否可播及遙控適配由實際結果決定。不擷取串流或解鎖內容。", 14, 0xffb9c9d8);
    note.setPadding(dp(4), dp(14), dp(4), dp(12)); body.addView(note);
    Button back = button("返回上一頁", this::finish);
    root.addView(back, new LinearLayout.LayoutParams(-1, dp(48)));
    setContentView(root); first.requestFocus();
  }
  private Button service(LinearLayout parent, OfficialDestinations.Destination destination, String description, String conditions) {
    LinearLayout card = column(); card.setBackground(background(PANEL, false));
    card.setPadding(dp(22), dp(16), dp(22), dp(16));
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.topMargin = dp(14);
    parent.addView(card, params);
    card.addView(text(destination.title, 23, 0xfff2f7fc));
    card.addView(text(destination.url, 13, TEAL));
    card.addView(text(description, 16, 0xffe4edf6));
    card.addView(text(conditions, 14, 0xffb9c9d8));
    Button open = button("開啟" + destination.title + "官方網站 ↗", () -> open(destination));
    LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(-1, dp(48)); buttonParams.topMargin = dp(10);
    card.addView(open, buttonParams); return open;
  }
  private void open(OfficialDestinations.Destination destination) {
    if (!OfficialDestinations.isAllowed(destination.url)) return;
    try {
      // HTTPS only. Android may use a browser or the verified site handler chosen by the user.
      startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(destination.url)).addCategory(Intent.CATEGORY_BROWSABLE));
    } catch (ActivityNotFoundException | SecurityException e) {
      AlertDialog dialog = new AlertDialog.Builder(this).setTitle("這部電視沒有可開啟網站的應用程式")
          .setMessage("請在手機或電腦開啟官方網址：\n" + destination.url
              + "\n\n本程式沒有內置短視頻網頁播放器。可按返回繼續使用電視功能。")
          .setPositiveButton("返回官方入口", null).create();
      dialog.show(); dialog.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus();
    }
  }
  private Button button(String title, Runnable runnable) {
    Button button = new Button(this); button.setId(View.generateViewId());
    button.setText(title); button.setTextSize(16); button.setAllCaps(false); button.setFocusable(true);
    button.setGravity(Gravity.CENTER_VERTICAL | Gravity.START); button.setPadding(dp(16), dp(6), dp(16), dp(6));
    button.setMinHeight(dp(48)); button.setMinimumHeight(dp(48));
    button.setTextColor(0xfff2f7fc); button.setBackground(background(0xff234056, false));
    button.setOnFocusChangeListener((v, focused) -> {
      button.setTextColor(focused ? BG : 0xfff2f7fc); button.setBackground(background(focused ? TEAL : 0xff234056, focused));
      button.setTypeface(Typeface.DEFAULT, focused ? Typeface.BOLD : Typeface.NORMAL);
    });
    button.setOnClickListener(v -> runnable.run()); return button;
  }
  private TextView text(String value, int size, int color) {
    TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color);
    view.setLineSpacing(dp(3), 1); view.setPadding(0, dp(2), 0, dp(5)); return view;
  }
  private LinearLayout column() { LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); return box; }
  private GradientDrawable background(int color, boolean focused) {
    GradientDrawable drawable = new GradientDrawable(); drawable.setColor(color); drawable.setCornerRadius(dp(12));
    if (focused) drawable.setStroke(dp(2), TEAL); return drawable;
  }
  private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
