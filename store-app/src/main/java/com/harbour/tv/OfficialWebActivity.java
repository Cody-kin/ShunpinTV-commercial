package com.harbour.tv;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.http.SslError;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.*;
import android.widget.*;
import com.harbour.core.OfficialPageAdapter;
import org.json.*;

/**
 * Official-page viewer with original DOM playback observation; no extraction or native bridge. Page
 * shortcut IDs are attributed in docs/WEBTVLIVE-COMPARISON.md and WEBTVLIVE-LICENSE.txt. Integrator
 * must declare this Activity exported=false in AndroidManifest.xml.
 */
public final class OfficialWebActivity extends Activity {
  public static final String EXTRA_URL = "official_page_url";
  public static final String HOME_URL = "https://www.yangshipin.cn/tv/home";
  private final Handler handler = new Handler(Looper.getMainLooper());
  private FrameLayout root, webHost;
  private LinearLayout menu;
  private TextView status;
  private Button officialGuideButton;
  private WebView web;
  private RemotePointer pointer;
  private boolean pointerMode;
  private float pointerX, pointerY;
  private final class RemotePointer extends View {
    private final android.graphics.Paint paint=new android.graphics.Paint(3);
    RemotePointer(){super(OfficialWebActivity.this);setFocusable(false);setClickable(false);setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);}
    @Override protected void onDraw(android.graphics.Canvas canvas){super.onDraw(canvas);paint.setColor(Color.BLACK);canvas.drawCircle(pointerX,pointerY,dp(10),paint);paint.setColor(0xFF61E6CC);canvas.drawCircle(pointerX,pointerY,dp(7),paint);paint.setColor(Color.WHITE);canvas.drawCircle(pointerX,pointerY,dp(2),paint);}
  }
  private void enablePointer(){
    interacting=false;hideMenu();pointerMode=true;
    pointerX=root.getWidth()/2f;pointerY=root.getHeight()/2f;
    pointer.setVisibility(View.VISIBLE);pointer.bringToFront();pointer.invalidate();
    status.setText("滑鼠：方向鍵移動 · OK 點擊 · 返回／MENU 工具選單");
  }
  private void stopPointer(){pointerMode=false;if(pointer!=null)pointer.setVisibility(View.GONE);}
  private void clickPointer(){
    View target=customView!=null?customView:web;if(target==null)return;
    int[] host=new int[2],origin=new int[2];root.getLocationOnScreen(host);target.getLocationOnScreen(origin);
    float x=pointerX+host[0]-origin[0],y=pointerY+host[1]-origin[1];
    if(x<0||y<0||x>=target.getWidth()||y>=target.getHeight())return;
    long now=android.os.SystemClock.uptimeMillis();
    android.view.MotionEvent down=android.view.MotionEvent.obtain(now,now,android.view.MotionEvent.ACTION_DOWN,x,y,0);
    android.view.MotionEvent up=android.view.MotionEvent.obtain(now,now+40,android.view.MotionEvent.ACTION_UP,x,y,0);
    try{target.dispatchTouchEvent(down);target.dispatchTouchEvent(up);}finally{down.recycle();up.recycle();}
  }
  private void requestVideoFullscreen(){
    if(customView!=null){hideMenu();return;}
    if(web==null||!isAllowedUrl(web.getUrl()))return;
    web.evaluateJavascript("(function(){var v=Array.from(document.querySelectorAll('video')).find(function(v){var r=v.getBoundingClientRect();return r.width>0&&r.height>0;});if(!v)return 'missing';var f=v.requestFullscreen||v.webkitRequestFullscreen;if(!f)return 'unsupported';try{var p=f.call(v);if(p&&p.catch)p.catch(function(){});return 'requested';}catch(e){return 'denied';}})()",result->{
      hideMenu();status.setText("已請求影片全屏；若網站沒有回應，開啟滑鼠點擊原播放器的全屏按鈕。隔離影片需使用網站自己的按鈕。");
    });
  }
  private String currentUrl = HOME_URL;
  private boolean autoplayAuthorized;
  private int pageGeneration;
  private final OfficialPageAdapter.Progress progress = new OfficialPageAdapter.Progress();
  private boolean observedPlaying;
  private boolean failed, interacting, resumed;
  private boolean consumeCenterUp;
  private boolean consumeBackUp;
  private boolean loading, resumeAfterStop;
  private JSONObject lastSample;
  private int resourceErrors;
  private long observationStarted;
  private boolean reportedPlaybackWait;

  // Local debug evidence only: never log request paths, queries, cookies or media addresses.
  private void logResourceFailure(String kind, WebResourceRequest request, int code) {
    if (BuildConfig.DEBUG) android.util.Log.w("OfficialPageDiagnostic", kind
        + " mainFrame=" + request.isForMainFrame() + " code=" + code
        + " scheme=" + request.getUrl().getScheme() + " host=" + request.getUrl().getHost());
  }

  private void showDiagnostics() {
    android.content.pm.PackageInfo provider = WebView.getCurrentWebViewPackage();
    android.media.AudioManager audio = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
    String sound = audio == null ? "未知" : audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        + "/" + audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
    JSONObject v = lastSample;
    String detail = "WebView：" + (provider == null ? "未知" : provider.versionName)
        + "\n影片持續前進：" + (observedPlaying ? "是" : "尚未確認")
        + "\n系統媒體音量：" + sound
        + "\n子資源錯誤數：" + resourceErrors;
    if (v != null && v.optBoolean("found")) {
      detail += "\n影片尺寸：" + v.optInt("width") + " × " + v.optInt("height")
          + "\n網頁靜音：" + v.optBoolean("muted") + "；網頁音量：" + v.optDouble("volume", 0)
          + "\n影片錯誤碼：" + v.optInt("mediaError")
          + "\n音訊解碼 bytes：" + (v.isNull("audioBytes") ? "此 WebView 不提供" : v.optLong("audioBytes"));
    } else detail += "\n尚未找到可觀察的影片；可能仍在載入、受原站限制或播放器位於隔離頁面。";
    detail += "\n\n影片時間前進不代表一定有聲音或正在播放節目；請實際聆聽並確認畫面。這些資料只在本機顯示，不上傳網址、Cookie 或媒體地址。";
    new AlertDialog.Builder(this).setTitle("本機播放狀態").setMessage(detail).setPositiveButton("返回操作", null).show();
  }

  private View customView;
  private WebChromeClient.CustomViewCallback customViewCallback;

  public static Intent launchIntent(Context context) {
    return new Intent(context, OfficialWebActivity.class).putExtra("allow_official_autoplay", true);
  }

  public static Intent launchIntent(Context context, String officialUrl) {
    return launchIntent(context).putExtra(EXTRA_URL, officialUrl);
  }

  private long pageLoadDeadline;

  private final Runnable timeout =
      () -> {
        if (resumed && loading && !failed && web != null) {
          long remaining = pageLoadDeadline - android.os.SystemClock.elapsedRealtime();
          if (remaining > 0) {
            status.setText("官方頁載入較慢，繼續等待（最多共 120 秒）；未確認播放。OK 操作選單 · 返回首頁。");
            handler.postDelayed(OfficialWebActivity.this.timeout, remaining);
          } else {
            web.stopLoading();
            fail("官方頁 120 秒內未完成載入；可重試或返回首頁。未確認直播播放。");
          }
        }
      };

  public static boolean isSupportedOfficialPage(String raw) { return OfficialPageAdapter.supports(raw); }
  static boolean isAllowedUrl(String raw) { return isSupportedOfficialPage(raw); }

  private final Runnable observePlayback = new Runnable() {
    @Override public void run() {
      final WebView target = web;
      final int generation = pageGeneration;
      if (!resumed || loading || target == null || failed || !isAllowedUrl(target.getUrl())) return;
      // Report a bounded wait even if the renderer never returns a JS callback.
      if (!observedPlaying && !reportedPlaybackWait
          && android.os.SystemClock.elapsedRealtime() - observationStarted > 45000) {
        reportedPlaybackWait = true;
        status.setText("45 秒內未確認影片播放。按 OK 可查看本機播放狀態、重試或返回首頁。");
      }
      target.evaluateJavascript(OfficialPageAdapter.script(autoplayAuthorized), value -> {
        if (!resumed || target != web || generation != pageGeneration || failed || !isAllowedUrl(target.getUrl())) return;
        try {
          Object decoded = new JSONTokener(value).nextValue();
          JSONObject sample = new JSONObject(String.valueOf(decoded));
          lastSample = sample;
          boolean playing = sample.optBoolean("found") && progress.observe(sample.optString("id"),
              sample.optDouble("time", 0), sample.optInt("ready"), sample.optInt("width"),
              sample.optBoolean("paused", true), sample.optBoolean("ended", true));
          if (!sample.optBoolean("found")) progress.reset();
          if (playing) {
            observedPlaying = true;
            status.setText("官方播放器播放中（原站廣告與限制保留） · OK 操作選單 · 返回首頁");
            getSharedPreferences("official_web", MODE_PRIVATE).edit().putString("last_successful_page", currentUrl).apply();
          } else if (!reportedPlaybackWait && android.os.SystemClock.elapsedRealtime() - observationStarted > 45000) {
            reportedPlaybackWait = true; observedPlaying = false;
            status.setText("45 秒內未確認影片播放。按 OK 可查看本機播放狀態、重試或返回首頁。");
          } else if (observedPlaying) {
            observedPlaying = false;
            status.setText("官方播放器暫停／緩衝；尚未觀察到進展。OK 操作選單 · 返回首頁");
          }
        } catch (Exception ignored) { progress.reset(); }
      });
      handler.postDelayed(this, 1000);
    }
  };

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    if(!BuildConfig.PERSONAL_BUILD){finish();return;}
    autoplayAuthorized = state == null ? getIntent().getBooleanExtra("allow_official_autoplay", false) : state.getBoolean("autoplay", false);
    getWindow()
        .getDecorView()
        .setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    root = new FrameLayout(this);
    root.setBackgroundColor(Color.BLACK);
    setContentView(root);
    webHost = new FrameLayout(this);
    FrameLayout.LayoutParams wp = new FrameLayout.LayoutParams(-1, -1);
    wp.topMargin = dp(62);
    root.addView(webHost, wp);
    status = new TextView(this);
    status.setTextColor(Color.WHITE);
    status.setTextSize(14);
    status.setPadding(dp(18), dp(8), dp(18), dp(8));
    status.setBackgroundColor(0xFF102333);
    root.addView(status, new FrameLayout.LayoutParams(-1, dp(62), Gravity.TOP));
    buildMenu();
    pointer=new RemotePointer();root.addView(pointer,new FrameLayout.LayoutParams(-1,-1));pointer.setVisibility(View.GONE);
    Button tools=new Button(this);tools.setText("網頁工具");tools.setContentDescription("網頁工具：滑鼠、全屏、縮放與重載");tools.setOnClickListener(v->showMenu());
    root.addView(tools,new FrameLayout.LayoutParams(dp(118),dp(54),Gravity.TOP|Gravity.RIGHT));
    status.setPadding(dp(18),dp(8),dp(126),dp(8));
    String requested =
        state == null ? getIntent().getStringExtra(EXTRA_URL) : state.getString("url");
    boolean restoring = requested == null || state != null;
    if (requested == null)
      requested = getSharedPreferences("official_web", MODE_PRIVATE).getString("last_page", HOME_URL);
    String restored = restoring ? OfficialPageAdapter.restoreSavedUrl(requested) : requested;
    boolean expiredReplay = restoring && restored != null && !restored.equals(requested);
    requested = restored;
    if (isAllowedUrl(requested)) {
      load(requested);
      if (expiredReplay) Toast.makeText(this,"上次回看時段已過期，已返回該台官方直播頁。",Toast.LENGTH_LONG).show();
    }
    else fail("網址未通過官方頁面檢查。請從操作選單選擇央視頻電視首頁。");
  }

  @SuppressLint("SetJavaScriptEnabled") // The verified official SPA needs JS; no native bridge.
  private boolean createWeb() {
    if (web != null) return true;
    try {
      web = new WebView(this);
      web.setBackgroundColor(Color.BLACK);
      WebSettings settings = web.getSettings();
      settings.setJavaScriptEnabled(true);
      settings.setDomStorageEnabled(true);
      settings.setAllowFileAccess(false);
      settings.setAllowContentAccess(false);
      settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
      settings.setSafeBrowsingEnabled(true);
      settings.setJavaScriptCanOpenWindowsAutomatically(false);
      settings.setSupportMultipleWindows(false);
      settings.setMediaPlaybackRequiresUserGesture(!autoplayAuthorized);
      settings.setUserAgentString("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36");
      CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);
      web.setWebChromeClient(
          new WebChromeClient() {
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
              if (customView != null || web == null || !isAllowedUrl(web.getUrl())) {
                callback.onCustomViewHidden();
                return;
              }
              customView = view;
              customViewCallback = callback;
              root.addView(view, new FrameLayout.LayoutParams(-1, -1));
              webHost.setVisibility(View.INVISIBLE);
              if(pointerMode){pointer.bringToFront();}
              status.setVisibility(View.GONE);
              if (menuVisible()) ((View) menu.getParent()).bringToFront();
            }

            @Override
            public void onHideCustomView() {
              hideCustomView();
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
              request.deny();
              status.setText("網站要求額外權限，本入口未提供；可能無法播放受保護內容。MENU 開啟操作。");
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(
                String origin, GeolocationPermissions.Callback callback) {
              callback.invoke(origin, false, false);
            }
          });
      web.setWebViewClient(
          new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
              if (!request.isForMainFrame()) return false;
              if (isAllowedUrl(request.getUrl().toString())) return false;
              status.setText("已阻止離開核實的官方電視頁；登入或其他頁面可能無法在本入口開啟。MENU 操作。");
              return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap icon) {
              if (view != web) return;
              if (!isAllowedUrl(url)) {
                view.stopLoading();
                fail("已停止未通過檢查的頁面導航。");
                return;
              }
              currentUrl = url;
              pageGeneration++;
              progress.reset(); observedPlaying = false;
              lastSample = null; reportedPlaybackWait = false;
              handler.removeCallbacks(observePlayback);
              loading = true;
              pageLoadDeadline = android.os.SystemClock.elapsedRealtime() + 120000;
              failed = false;
              status.setText("正在載入官方直播原頁… OK 開啟操作；返回首頁。");
              handler.removeCallbacks(timeout);
              if (resumed) handler.postDelayed(timeout, 30000);
            }

            @Override
            public void onPageCommitVisible(WebView view, String url) {
              documentReady(view, url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
              documentReady(view, url);
            }

            @Override
            public void onReceivedError(
                WebView view, WebResourceRequest request, WebResourceError error) {
              if (view != web) return;
              logResourceFailure("network", request, error.getErrorCode());
              if (!request.isForMainFrame()) resourceErrors++;
              if (request.isForMainFrame()) fail("官方網頁載入失敗，請檢查網絡後在操作選單重試。");
            }

            @Override
            public void onReceivedHttpError(
                WebView view, WebResourceRequest request, WebResourceResponse response) {
              if (view != web) return;
              logResourceFailure("http", request, response.getStatusCode());
              if (!request.isForMainFrame()) resourceErrors++;
              if (request.isForMainFrame())
                fail("官方網頁回應 HTTP " + response.getStatusCode() + "；請稍後重試。");
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler ssl, SslError error) {
              ssl.cancel();
              if (view != web) return;
              fail("網站憑證驗證失敗，已停止連線；不會略過驗證。");
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
              hideCustomView();
              if (web == view) web = null;
              webHost.removeView(view);
              view.destroy();
              fail("系統 WebView 已停止；請在操作選單重試，仍失敗請更新系統 WebView。");
              return true;
            }
          });
      web.setDownloadListener(
          (url, agent, disposition, mime, length) -> status.setText("本入口不下載檔案或串流。MENU 開啟操作選單。"));
      webHost.addView(web, new FrameLayout.LayoutParams(-1, -1));
      return true;
    } catch (RuntimeException error) {
      if (web != null) {
        webHost.removeView(web);
        web.destroy();
        web = null;
      }
      fail("無法建立系統 WebView；請檢查系統瀏覽器元件後重試。");
      return false;
    }
  }

  // Commit makes the document observable; outstanding images/scripts must not abort it.
  // Finish is a fallback and must not reset the observation deadline or playing status.
  private void documentReady(WebView view, String url) {
    if (view != web || failed || !loading || !currentUrl.equals(url)
        || !isAllowedUrl(url)) return;
    loading = false;
    observationStarted = android.os.SystemClock.elapsedRealtime();
    handler.removeCallbacks(timeout);
    status.setText("官方頁已載入，正在等待播放器；尚未確認播放。OK 操作選單 · 返回首頁。");
    handler.removeCallbacks(observePlayback);
    if (resumed) handler.post(observePlayback);
  }

  private void load(String url) {
    if (!isAllowedUrl(url)) {
      fail("只接受已核實的官方頻道頁，或有效時段的官方回看頁。");
      return;
    }
    handler.removeCallbacks(timeout);
    handler.removeCallbacks(observePlayback);
    pageGeneration++; progress.reset(); observedPlaying = false;
    lastSample = null; resourceErrors = 0; reportedPlaybackWait = false;
    observationStarted = android.os.SystemClock.elapsedRealtime();
    hideCustomView();
    if (web != null) {
      WebView previous = web;
      web = null;
      previous.stopLoading();
      webHost.removeView(previous);
      previous.destroy();
    }
    currentUrl = url;
    if (officialGuideButton != null) officialGuideButton.setVisibility(
        OfficialPageAdapter.cctvKey(url) != null || OfficialPageAdapter.HAHA_CARTOON.equals(url) ? View.VISIBLE : View.GONE);
    getSharedPreferences("official_web", MODE_PRIVATE).edit().putString("last_page", url).apply();
    loading = true;
    pageLoadDeadline = android.os.SystemClock.elapsedRealtime() + 120000;
    failed = false;
    interacting = false;
    hideMenu();
    if (createWeb()) {
      status.setText("正在載入官方網頁；這不代表直播已開始。OK 開啟操作。");
      web.loadUrl(url);
      handler.postDelayed(timeout, 30000);
    }
  }

  private void loadFromUser(String url) {
    autoplayAuthorized = true;
    if (web != null) web.getSettings().setMediaPlaybackRequiresUserGesture(false);
    load(url);
  }

  private void fail(String message) {
    handler.removeCallbacks(observePlayback);
    progress.reset(); observedPlaying = false;
    failed = true;
    loading = false;
    handler.removeCallbacks(timeout);
    if (status != null) status.setText(message);
    if (menu != null) showMenu();
  }

  @androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
  private void buildMenu() {
    menu = new LinearLayout(this);
    menu.setOrientation(LinearLayout.VERTICAL);
    menu.setPadding(dp(16), dp(12), dp(16), dp(12));
    menu.setBackgroundColor(0xF5102333);
    TextView title = new TextView(this);
    title.setText("網頁觀看工具\n滑鼠操作與影片顯示");
    title.setTextSize(17);
    title.setTextColor(Color.WHITE);
    menu.addView(title);
    addButton("遙控滑鼠 · 方向移動／OK 點擊", this::enablePointer);
    addButton("影片全屏", this::requestVideoFullscreen);
    addButton("退出影片全屏", ()->{hideCustomView();hideMenu();});
    addButton("網頁放大", ()->{if(web!=null)web.zoomBy(1.25f);hideMenu();});
    addButton("網頁縮小", ()->{if(web!=null)web.zoomBy(0.8f);hideMenu();});
    addButton("網頁向下捲動", ()->{if(web!=null)web.scrollBy(0,web.getHeight()/2);hideMenu();});
    addButton("網頁向上捲動", ()->{if(web!=null)web.scrollBy(0,-web.getHeight()/2);hideMenu();});
    addButton("繼續觀看", this::hideMenu);
    addButton("返回首頁", () -> {
      startActivity(new Intent(this, MainActivity.class).putExtra("open_home", true)
          .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
      finish();
    });
    addButton("本機播放狀態", this::showDiagnostics);
    addButton("開啟網頁聲音", () -> {
      if (web != null && isAllowedUrl(web.getUrl())) {
        web.evaluateJavascript(OfficialPageAdapter.script(true, true), null);
        status.setText("已向原播放器請求解除靜音；電視音量請用遙控器調整，請實際聆聽確認。");
      }
      hideMenu();
    });
    addButton(
        "操作原網頁（MENU 回選單）",
        () -> {
          interacting = true;
          hideMenu();
          if (web != null) web.requestFocus();
          status.setText("原網頁操作：方向鍵與 OK 交給網站；MENU 回選單；返回退出。網站可能不支援遙控器。");
        });
    addButton("選擇 CCTV 頻道", this::chooseCctv);
    addButton("金鷹卡通 · 芒果官方選台", () -> loadFromUser(OfficialPageAdapter.MANGO_GOLDEN));
    addButton("官方節目單／回看", () -> {
      String key = OfficialPageAdapter.cctvKey(currentUrl);
      if (key == null) {
        interacting = true; hideMenu();
        if (web != null) web.requestFocus();
        status.setText("請使用原站日期／回看選單；MENU 返回操作，原站可能不支援完整遙控器操作。");
        return;
      }
      startActivity(new Intent(this, CctvGuideActivity.class).putExtra("channel_key", key));
    });
    officialGuideButton = (Button) menu.getChildAt(menu.getChildCount()-1);
    addButton("CCTV-1 官方直播", () -> loadFromUser(OfficialPageAdapter.CCTV1));
    addButton("CCTV-4 歐洲官方直播", () -> loadFromUser(OfficialPageAdapter.EUROPE));
    addButton("嘗試播放官方播放器", () -> {
      autoplayAuthorized = true;
      if (web != null) {
        web.getSettings().setMediaPlaybackRequiresUserGesture(false);
        web.evaluateJavascript("document.querySelectorAll('video').forEach(function(v){v.__harbourPlayAttempted=false;});", null);
        handler.removeCallbacks(observePlayback); handler.post(observePlayback);
      }
      hideMenu();
    });
    addButton("央視頻電視首頁", () -> loadFromUser(HOME_URL));
    addButton("CCTV-4 官方頁入口", () -> loadFromUser(HOME_URL + "?pid=600001814"));
    addButton("CCTV-14 官方頁入口", () -> loadFromUser(HOME_URL + "?pid=600001809"));
    addButton("輸入官方頁網址", this::askUrl);
    addButton("重新載入", () -> loadFromUser(currentUrl));
    addButton("退出官方頁", this::finish);
    ScrollView scroll = new ScrollView(this);
    scroll.addView(menu);
    root.addView(scroll, new FrameLayout.LayoutParams(dp(380), -1, Gravity.START));
    scroll.setVisibility(View.GONE);
  }

  private void chooseCctv() {
    try (java.io.InputStream in = getAssets().open("official_cctv_pages.json")) {
      java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
      byte[] b = new byte[4096]; int n;
      while ((n=in.read(b))!=-1) { out.write(b,0,n); if(out.size()>65536) throw new java.io.IOException(); }
      JSONArray rows = new JSONObject(out.toString("UTF-8")).getJSONArray("channels");
      String[] names = new String[rows.length()], urls = new String[rows.length()];
      for(int i=0;i<rows.length();i++) { names[i]=rows.getJSONObject(i).getString("name"); urls[i]=rows.getJSONObject(i).getString("url"); }
      new AlertDialog.Builder(this).setTitle("CCTV 官方頻道 · 原站限制適用")
          .setItems(names,(d,i)->loadFromUser(urls[i])).setNegativeButton("取消",null).show();
    } catch(Exception error) { status.setText("官方頻道目錄無法讀取，請返回首頁重試。"); }
  }

  private void addButton(String text, Runnable action) {
    Button button = new Button(this);
    button.setText(text);
    button.setAllCaps(false);
    button.setFocusable(true);
    button.setOnClickListener(v -> action.run());
    menu.addView(button, new LinearLayout.LayoutParams(-1, dp(49)));
  }

  private void askUrl() {
    EditText field = new EditText(this);
    field.setSingleLine(true);
    field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
    field.setText(currentUrl);
    AlertDialog dialog =
        new AlertDialog.Builder(this)
            .setTitle("官方直播頁網址")
            .setMessage("接受央視頻 /tv/home（可帶 pid），及已核實的央視頻道頁；過期或不合法回看時段不可開啟。")
            .setView(field)
            .setNegativeButton("取消", null)
            .setPositiveButton("開啟", null)
            .create();
    dialog.setOnShowListener(
        ignored ->
            dialog
                .getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(
                    v -> {
                      String value = field.getText().toString().trim();
                      if (!isAllowedUrl(value)) {
                        field.setError("網址不在核實的官方頁範圍");
                        return;
                      }
                      dialog.dismiss();
                      loadFromUser(value);
                    }));
    dialog.show();
  }

  private void showMenu() {
    stopPointer();
    interacting = false;
    View parent = (View) menu.getParent();
    parent.setVisibility(View.VISIBLE);
    parent.bringToFront();
    menu.getChildAt(1).requestFocus();
  }

  private void hideMenu() {
    if (menu != null) ((View) menu.getParent()).setVisibility(View.GONE);
  }

  private boolean menuVisible() {
    return ((View) menu.getParent()).getVisibility() == View.VISIBLE;
  }

  @Override
  public boolean dispatchKeyEvent(KeyEvent event) {
    int key = event.getKeyCode();
    if (key == KeyEvent.KEYCODE_BACK && consumeBackUp) {
      if (event.getAction() == KeyEvent.ACTION_UP) consumeBackUp = false;
      return true;
    }
    boolean center = key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER;
    if (center && consumeCenterUp) {
      if (event.getAction() == KeyEvent.ACTION_UP) consumeCenterUp = false;
      return true;
    }
    if(pointerMode && !menuVisible() && (center || (key>=KeyEvent.KEYCODE_DPAD_UP && key<=KeyEvent.KEYCODE_DPAD_RIGHT))) {
      if(event.getAction()==KeyEvent.ACTION_DOWN){
        if(center){if(event.getRepeatCount()==0)clickPointer();}
        else {float step=dp(event.getRepeatCount()>5?20:10);
          if(key==KeyEvent.KEYCODE_DPAD_LEFT)pointerX-=step;if(key==KeyEvent.KEYCODE_DPAD_RIGHT)pointerX+=step;
          if(key==KeyEvent.KEYCODE_DPAD_UP)pointerY-=step;if(key==KeyEvent.KEYCODE_DPAD_DOWN)pointerY+=step;
          pointerX=Math.max(dp(10),Math.min(root.getWidth()-dp(10),pointerX));pointerY=Math.max(dp(10),Math.min(root.getHeight()-dp(10),pointerY));pointer.invalidate();
        }
      }return true;
    }
    if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
      if (key == KeyEvent.KEYCODE_BACK) {
        consumeBackUp = true;
        onBackPressed();
        return true;
      }
      if (key == KeyEvent.KEYCODE_MENU || key == KeyEvent.KEYCODE_SETTINGS) {
        showMenu();
        return true;
      }
      if (center && !menuVisible() && !interacting) {
        consumeCenterUp = true;
        showMenu();
        return true;
      }
    }
    if (!menuVisible()
        && !interacting
        && (center || (key >= KeyEvent.KEYCODE_DPAD_UP && key <= KeyEvent.KEYCODE_DPAD_RIGHT)))
      return true;
    return super.dispatchKeyEvent(event);
  }

  @Override
  public void onBackPressed() {
    if (menuVisible()) { hideMenu(); return; }
    if(pointerMode){showMenu();return;}
    if (interacting) { interacting = false; showMenu(); return; }
    if (customView != null) { hideCustomView(); return; }
    finish();
  }

  private void hideCustomView() {
    if (customView == null) return;
    root.removeView(customView);
    customView = null;
    WebChromeClient.CustomViewCallback callback = customViewCallback;
    customViewCallback = null;
    webHost.setVisibility(View.VISIBLE);
    status.setVisibility(View.VISIBLE);
    if (callback != null) callback.onCustomViewHidden();
  }

  @Override
  protected void onSaveInstanceState(Bundle state) {
    state.putString("url", currentUrl);
    state.putBoolean("autoplay", autoplayAuthorized);
    super.onSaveInstanceState(state);
  }

  @Override
  protected void onResume() {
    super.onResume();
    resumed = true;
    if (resumeAfterStop) { resumeAfterStop = false; load(currentUrl); }
    if (web != null) {
      web.onResume();
      handler.removeCallbacks(observePlayback);
      if (!loading && !failed) handler.post(observePlayback);
      if (loading && !failed) {
        handler.removeCallbacks(timeout);
        handler.postDelayed(timeout, 30000);
      }
    }
  }

  @Override
  protected void onPause() {
    resumed = false;
    pageGeneration++; progress.reset(); observedPlaying = false;
    handler.removeCallbacks(observePlayback);
    handler.removeCallbacks(timeout);
    if (web != null) web.onPause();
    super.onPause();
  }

  @Override
  protected void onStop() {
    hideCustomView();
    resumeAfterStop = web != null && !failed && !isFinishing();
    // Destroy the web session to guarantee there is no hidden/background page playback.
    handler.removeCallbacks(timeout);
    handler.removeCallbacks(observePlayback);
    pageGeneration++; progress.reset();
    loading = false;
    if (web != null) {
      web.stopLoading();
      webHost.removeView(web);
      web.destroy();
      web = null;
      status.setText("官方頁已停止；按 OK 開啟選單後重新載入。");
    }
    super.onStop();
  }

  @Override
  protected void onDestroy() {
    hideCustomView();
    handler.removeCallbacksAndMessages(null);
    if (web != null) {
      webHost.removeView(web);
      web.destroy();
      web = null;
    }
    super.onDestroy();
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }
}
