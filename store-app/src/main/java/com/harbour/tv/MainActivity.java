package com.harbour.tv;

import android.app.*;
import android.content.SharedPreferences;
import android.graphics.*;
import android.graphics.drawable.*;
import android.os.Bundle;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.ui.PlayerView;
import com.harbour.core.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** TV-first, touch-independent prototype. All public-facing copy is Traditional Chinese. */
@UnstableApi
public final class MainActivity extends Activity {
  private static final int BG = 0xFF08131F,
      SURFACE = 0xFF102333,
      TEXT = 0xFFF4F7FC,
      MUTED = 0xFFA8BACB,
      MINT = 0xFF61E6CC;
  private CatalogRepository repo;
  private final java.util.concurrent.ExecutorService catalogExecutor =
      java.util.concurrent.Executors.newSingleThreadExecutor();
  private int catalogGeneration, selectionGeneration;
  private boolean activityStarted, activityDestroyed, catalogRefreshPending;
  private Runnable deferredCatalogResult;
  private TextView catalogNotice;
  private SourceManager sourceManager;
  private LinearLayout drawer;
  private boolean drawerOpen;
  private int menuLevel;
  private String drawerCategory = "全部";
  private Button homePreview, sourceShortcut;
  private String drawerSelectionId;
  private AlertDialog exitDialog;
  private final SearchEngine search = new SearchEngine();
  private final IntentProvider intentProvider = new IntentProvider.Local();
  private SharedPreferences prefs;
  private Set<String> favorites;
  private String territory = "HK", query = "", filter = "all", selectedId, playingId;
  private boolean fullScreen, hasResults = true, resumeWantsPlayback = true, resumeTerminal;
  private FrameLayout root, previewHost, fullHost;
  private LinearLayout content, channelList;
  private LinearLayout homeAdSlot;
  private TextView heading,
      detail,
      guide,
      status,
      metrics,
      resultLabel,
      privacy,
      fullTitle,
      sourceBadge,
      previewLabel;
  private Button playButton, favoriteButton, allButton, savedButton, searchButton;
  private PlayerView playerView, fullPlayerView, attachedPlayerView;
  private boolean playbackKeepScreenOn;
  private SurfaceHolder pendingSurfaceHolder;
  private SurfaceHolder.Callback pendingSurfaceCallback;
  private PlaybackController playback;
  private java.util.List<Catalog.Channel> visibleChannels = Collections.emptyList();
  private final Map<String, Integer> channelViewIds = new HashMap<>();
  private final Map<String, Button> channelButtons = new HashMap<>();
  private String lastStatus = "選擇頻道，再按 OK 開始";
  private String frameMetric = "首畫面：尚未量測";
  private final android.os.Handler clockHandler =
      new android.os.Handler(android.os.Looper.getMainLooper());
  private TextView clock;
  private View fullOverlay;
  private final Runnable hideFullOverlay =
      () -> {
        if (fullOverlay != null) fullOverlay.setVisibility(View.GONE);
      };
  private final Runnable clockTick =
      new Runnable() {
        @Override
        public void run() {
          if (clock != null)
            clock.setText(
                DateTimeFormatter.ofPattern("HH:mm")
                    .withZone(ZoneId.of(territory.equals("HK") ? "Asia/Hong_Kong" : "Asia/Taipei"))
                    .format(Instant.now()));
          updateGuide();
          clockHandler.postDelayed(this, 30000);
        }
      };

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    // Keep-screen-on is explicitly controlled by actual playback state below.
    getWindow()
        .getDecorView()
        .setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    prefs = getSharedPreferences("harbour", MODE_PRIVATE);
    favorites = new HashSet<>(prefs.getStringSet("favorites", Collections.emptySet()));
    territory = prefs.getString("territory", "HK");
    if (!Arrays.asList("HK", "TW", "CN", "MO").contains(territory)) territory = "HK";
    loadInitialCatalog(state == null ? null : new Bundle(state));
  }

  private boolean requestedCctvUpdateStarted;
  private void updateRequestedCctvOnLaunch() { updateRequestedCctvOnLaunch(false); }
  private void updateRequestedCctvOnLaunch(boolean manual) {
    if(!BuildConfig.PERSONAL_BUILD||requestedCctvUpdateStarted||(!manual&&!prefs.getBoolean("cctv_auto_update",true)))return;
    requestedCctvUpdateStarted=true;
    new Thread(()->{
      boolean ok=false;
      try{RequestedCctvSubscription.refreshAll(getApplicationContext());ok=true;}catch(Exception ignored){/* Atomic store retains last catalogue. */}
      final boolean success=ok;
      prefs.edit().putBoolean("cctv_last_update_ok",ok).putLong("cctv_last_update_attempt",System.currentTimeMillis()).apply();
      runOnUiThread(()->{if(isFinishing()||isDestroyed())return;
        android.widget.Toast.makeText(this,success?"八份直播片單已更新 · 首頁直播片單可選台":"直播片單更新失敗，保留上次清單 · 可手動重試",android.widget.Toast.LENGTH_LONG).show();
        if(success)refreshCatalog(false);
      });
    },"requested-cctv-refresh").start();
  }

  private void loadInitialCatalog(Bundle state) {
    root = new FrameLayout(this);
    root.setBackgroundColor(BG);
    LinearLayout loading = column();
    loading.setPadding(dp(32), dp(32), dp(32), dp(32));
    loading.addView(text("正在載入頻道目錄…", 24, TEXT));
    loading.addView(text("大量片單可能需要片刻。返回鍵可離開，不會開始下載影片。", 16, MUTED));
    Button back = button("返回", this::finish);
    loading.addView(back);
    root.addView(loading, match());
    setContentView(root);
    back.requestFocus();
    int generation = ++catalogGeneration;
    catalogExecutor.execute(() -> {
      try {
        SupplementalSources.seed(getApplicationContext());
        CatalogRepository loaded = new CatalogRepository(getApplicationContext());
        deliverCatalogResult(generation, () -> {
          repo = loaded;
          initializeCatalogUi(state);
          startPlaybackIfReady();
          updateRequestedCctvOnLaunch();
        });
      } catch (Exception error) {
        deliverCatalogResult(generation, () -> {
          loading.removeAllViews();
          loading.addView(text("頻道目錄無法載入，請重試。", 22, TEXT));
          Button retry = button("重試", () -> loadInitialCatalog(state));
          loading.addView(retry);
          loading.addView(button("返回", this::finish));
          retry.requestFocus();
        });
      }
    });
  }

  private void deliverCatalogResult(int generation, Runnable result) {
    runOnUiThread(() -> {
      if (activityDestroyed || isFinishing() || generation != catalogGeneration) return;
      Runnable guarded = () -> {
        if (!activityDestroyed && !isFinishing() && generation == catalogGeneration) result.run();
      };
      if (activityStarted) guarded.run();
      else deferredCatalogResult = guarded;
    });
  }

  private void initializeCatalogUi(Bundle state) {
    favorites.removeIf(id -> repo.catalog.find(id) == null);
    selectedId =
        state == null
            ? prefs.getString(
                "selected",
                repo.catalog.channels.isEmpty() ? null : repo.catalog.channels.get(0).id)
            : state.getString("selected");
    if (repo.catalog.find(selectedId) == null)
      selectedId = repo.catalog.channels.isEmpty() ? null : repo.catalog.channels.get(0).id;
    if (state != null) {
      query = state.getString("query", "");
      filter = state.getString("filter", "all");
    }
    buildUi();
    renderChannels();
    updateDetail();
    sourceManager = new SourceManager(this, () -> refreshCatalog(true));
    String restore =
        state == null ? prefs.getString("lastPlayed", null) : state.getString("playing");
    Catalog.Channel restored = repo.catalog.find(restore);
    if (restored == null || restored.eligible(territory, Instant.now()).isEmpty()) {
      restored =
          repo.catalog.channels.stream()
              .filter(c -> !c.isDemo() && !c.eligible(territory, Instant.now()).isEmpty())
              .sorted(java.util.Comparator.comparingInt(repo::channelRank))
              .findFirst()
              .orElse(null);
    }
    if (restored != null) {
      selectedId = restored.id;
      playingId = restored.id;
      resumeWantsPlayback = state == null || state.getBoolean("wantsPlayback", true);
      lastStatus = restore == null ? "沒有觀看記錄，已選第一個可用來源" : "準備恢復上次頻道";
      prefs.edit().putString("lastPlayed", playingId).apply();
      updateDetail();
    } else {
      lastStatus = "目前沒有可用頻道 · 請匯入有權使用的來源";
    }
    status.setText(lastStatus);
    root.post(() -> homePreview.requestFocus());
    root.post(() -> {
      handleNavigationIntent(getIntent());

    });
  }

  private boolean launchOfficialOnColdStart(Bundle state) {
    android.content.Intent intent = getIntent();
    if (state != null || intent.getBooleanExtra("open_home", false)
        || intent.hasExtra("open_sources") || intent.hasExtra("open_category")
        || intent.hasExtra("command_action")) return false;
    boolean restore = "official".equals(prefs.getString("lastMode", "native"));
    String firstPage = "https://tv.cctv.com/live/cctv1/index.shtml";
    boolean firstOfficial = !prefs.getBoolean("official_initial_launch_done", false)
        && repo.catalog.channels.stream().noneMatch(c -> c.id.startsWith("user-"))
        && OfficialWebActivity.isSupportedOfficialPage(firstPage);
    if (!restore && !firstOfficial) return false;
    // Restore a website, not a native stream. Never start the previous native channel behind it.
    playingId = null;
    updateDetail();
    prefs.edit().putBoolean("official_initial_launch_done", true).putString("lastMode", "official").apply();
    root.post(() -> {
      if (!isFinishing() && !isDestroyed()) startActivity(restore
          ? OfficialWebActivity.launchIntent(this)
          : OfficialWebActivity.launchIntent(this, firstPage));
    });
    return true;
  }

  @Override
  protected void onNewIntent(android.content.Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    if (root != null) root.post(() -> handleNavigationIntent(intent));
  }

  @Override
  protected void onStart() {
    super.onStart();
    activityStarted = true;
    if (deferredCatalogResult != null) {
      Runnable pending = deferredCatalogResult;
      deferredCatalogResult = null;
      pending.run();
    }
    startPlaybackIfReady();
  }

  private void startPlaybackIfReady() {
    if (!activityStarted || catalogRefreshPending || repo == null || playerView == null || playback != null) return;
    playback =
        new PlaybackController(
            this,
            new PlaybackController.Observer() {
              @Override
              public void onStatus(String text) {
                lastStatus = text;
                android.util.Log.i("ShunpinPlayback", "status=" + text);
                if (playingId != null)
                  SourceManager.recordPlaybackStatus(MainActivity.this, playingId, text);
                status.setText(statusText());
                if (fullTitle != null) updateFullTitle();
              }

              @Override
              public void onKeepScreenOn(boolean active) {
                playbackKeepScreenOn = active;
                updatePlayerKeepScreenOn();
              }

              @Override
              public void onFirstFrame(long ms) {
                frameMetric = "首畫面：" + ms + " ms（本次裝置）";
                prefs
                    .edit()
                    .putString("last_frame_channel", playingId)
                    .putLong("last_frame_ms", ms)
                    .apply();
                android.util.Log.i(
                    "ShunpinPlayback", "firstFrame channel=" + playingId + " elapsedMs=" + ms);
                metrics.setText(frameMetric);
              }
            });
    settlePlayerTarget();
    clockHandler.post(clockTick);
    // Restore the last user-started channel; source eligibility is rechecked on every start.
    if (playingId != null) {
      Catalog.Channel c = repo.catalog.find(playingId);
      if (c != null) {
        if (resumeTerminal) playback.stop();
        else playback.start(repo.playbackChannel(c), territory, resumeWantsPlayback);
      }
    }
  }

  @Override
  protected void onStop() {
    activityStarted = false;
    clockHandler.removeCallbacks(clockTick);
    clockHandler.removeCallbacks(hideFullOverlay);
    cancelPendingSurfaceSwitch();
    if (playerView != null) playerView.setPlayer(null);
    if (fullPlayerView != null) fullPlayerView.setPlayer(null);
    attachedPlayerView = null;
    playbackKeepScreenOn = false;
    updatePlayerKeepScreenOn();
    if (playback != null) {
      resumeWantsPlayback = playback.wantsPlayback();
      resumeTerminal = playback.isTerminal();
      playback.close();
      playback = null;
    }
    super.onStop();
  }

  @Override
  protected void onDestroy() {
    activityDestroyed = true;
    catalogGeneration++;
    deferredCatalogResult = null;
    catalogExecutor.shutdownNow();
    super.onDestroy();
  }

  @Override
  protected void onSaveInstanceState(Bundle out) {
    out.putString("selected", selectedId);
    out.putString("playing", playingId);
    out.putBoolean(
        "wantsPlayback", playback == null ? resumeWantsPlayback : playback.wantsPlayback());
    out.putString("query", query);
    out.putString("filter", filter);
    super.onSaveInstanceState(out);
  }

  private void buildUi() {
    root = new FrameLayout(this);
    root.setBackground(
        new GradientDrawable(
            GradientDrawable.Orientation.TL_BR, new int[] {0xFF303B51, 0xFF182338}));
    setContentView(root);
    content = column();
    content.setPadding(dp(26), dp(16), dp(26), dp(14));
    root.addView(content, match());
    LinearLayout header = row();
    header.setGravity(Gravity.CENTER_VERTICAL);
    ImageView logo = new ImageView(this);
    logo.setImageResource(R.drawable.tv_icon);
    header.addView(logo, new LinearLayout.LayoutParams(dp(32), dp(32)));
    TextView brand = text("  瞬頻 TV", 25, TEXT);
    brand.setTypeface(null, Typeface.BOLD);
    header.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
    Button setup = button("設定", this::showSettings);
    for (Button x : new Button[] {setup}) {
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(36));
      lp.leftMargin = dp(10);
      header.addView(x, lp);
    }
    clock = text("", 19, TEXT);
    clock.setPadding(dp(18), 0, 0, 0);
    header.addView(clock);
    header.addView(roundSearchButton(), new LinearLayout.LayoutParams(dp(40), dp(40)));
    content.addView(header, new LinearLayout.LayoutParams(-1, dp(40)));
    LinearLayout tabs = row();
    tabs.setPadding(0, dp(10), 0, dp(12));
    allButton =
        button(
            "首頁",
            () -> {
              filter = "all";
              query = "";
              renderChannels();
              homePreview.requestFocus();
            });
    savedButton = button("收藏", () -> showCategoryDrawer("收藏"));
    searchButton = button("更新頻道", () -> {if(BuildConfig.PERSONAL_BUILD){requestedCctvUpdateStarted=false;updateRequestedCctvOnLaunch(true);}else startActivityForResult(new android.content.Intent(this,LineRepairActivity.class).putExtra("refresh_now",true),2404);});
    Button live = button("全部頻道", this::showDrawer);
    for (Button x : new Button[] {allButton, live, savedButton, searchButton}) {
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(38), 1);
      lp.rightMargin = dp(7);
      tabs.addView(x, lp);
    }
    allButton.setSelected(true);
    content.addView(tabs);
    LinearLayout hero = row();
    content.addView(hero, new LinearLayout.LayoutParams(-1, 0, 1));
    LinearLayout left = column();
    LinearLayout.LayoutParams leftLp = new LinearLayout.LayoutParams(dp(172), -1);
    leftLp.rightMargin = dp(13);
    hero.addView(left, leftLp);
    Button cctv = addHomeTile(left, "CCTV 央視", "瀏覽頻道目錄", 0xFF204B62, () -> showCategoryDrawer("CCTV"));
    Button cartoons = addHomeTile(left, "卡通少兒", "金鷹／嘉佳／優漫", 0xFF654C38, () -> showCategoryDrawer("卡通少兒"));
    Button satellite = addHomeTile(left, "衛視頻道", "瀏覽頻道目錄", 0xFF544567, () -> showCategoryDrawer("衛視"));
    Button hongKong = addHomeTile(left, "香港頻道", "查看來源條件", 0xFF3F5554, () -> showCategoryDrawer("香港"));
    LinearLayout center = column();
    hero.addView(center, new LinearLayout.LayoutParams(0, -1, 1));
    previewHost = new FrameLayout(this);
    previewHost.setBackground(shape(0xFF07111D, 0xFF6F8298, 1));
    previewHost.addView(new HarbourBackdrop(this), match());
    playerView = new PlayerView(this);
    playerView.setUseController(false);
    playerView.setFocusable(false);
    playerView.setKeepContentOnPlayerReset(false);
    playerView.setContentDescription("首頁續播預覽");
    previewHost.addView(playerView, match());
    homePreview =
        button(
            "",
            () -> {
              if (playingId != null) enterFullScreen();
              else sourceManager.show();
            });
    StateListDrawable previewFocus = new StateListDrawable();
    previewFocus.addState(new int[] {android.R.attr.state_focused}, shape(0x00102030, MINT, 3));
    previewFocus.addState(new int[] {}, shape(0x00102030, 0xFF708292, 1));
    homePreview.setBackground(previewFocus);
    homePreview.setContentDescription("中央續播視窗，OK 進入全屏");
    previewHost.addView(homePreview, match());
    LinearLayout videoInfo = column();
    videoInfo.setPadding(dp(18), dp(14), dp(18), dp(14));
    videoInfo.setBackground(
        new GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM, new int[] {0x00101826, 0xE8101826}));
    heading = text("準備觀看", 23, TEXT);
    heading.setTypeface(null, Typeface.BOLD);
    heading.setSingleLine(true);
    heading.setEllipsize(android.text.TextUtils.TruncateAt.END);
    videoInfo.addView(heading);
    previewLabel = text("", 13, TEXT);
    previewLabel.setSingleLine(true);
    previewLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);
    previewLabel.setPadding(0, dp(5), 0, 0);
    videoInfo.addView(previewLabel);
    previewHost.addView(videoInfo, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
    homePreview.bringToFront();
    center.addView(previewHost, new LinearLayout.LayoutParams(-1, 0, 1));
    status = text("", 13, MINT);
    status.setMaxLines(2);
    status.setEllipsize(android.text.TextUtils.TruncateAt.END);
    status.setPadding(0, dp(6), 0, 0);
    videoInfo.addView(status);
    LinearLayout right = column();
    LinearLayout.LayoutParams rightLp = new LinearLayout.LayoutParams(dp(152), -1);
    rightLp.leftMargin = dp(13);
    hero.addView(right, rightLp);
    Button officialLive = addHomeTile(right, BuildConfig.PERSONAL_BUILD?"官方直播":"線路自檢", BuildConfig.PERSONAL_BUILD?"原站播放條件":"檢查／修復", 0xFF17636D, ()->{if(BuildConfig.PERSONAL_BUILD)openOfficialPage();else startActivityForResult(new android.content.Intent(this,LineRepairActivity.class),2404);});
    Button officialClips = addHomeTile(right, BuildConfig.PERSONAL_BUILD?"官方短視頻":"匯入頻道", BuildConfig.PERSONAL_BUILD?"官方服務入口":"使用你的來源", 0xFF414764,
        () -> {if(BuildConfig.PERSONAL_BUILD)startActivity(new android.content.Intent(this, OfficialServicesActivity.class));else sourceManager.show();});
    Button personal = addHomeTile(right, "我的空間", "偏好與觀看記錄", 0xFF34485F,
        () -> startActivity(new android.content.Intent(this, LocalUserCenterActivity.class)));
    LinearLayout shelf = row();
    shelf.setPadding(0, dp(14), 0, 0);
    content.addView(shelf, new LinearLayout.LayoutParams(-1, dp(70)));
    sourceShortcut = addShelf(shelf, "來源管理", "匯入／搜尋／組合", 0xFF175661, () -> sourceManager.show());
    sourceShortcut.setContentDescription("來源管理，匯入與整理自備片單");
    addShelf(shelf, "頻道整理", "文件夾／移動／刪除", 0xFF50445E, () -> openChannelLibrary(null));
    if (BuildConfig.PERSONAL_BUILD) addShelf(shelf, "直播片單", "八份訂閱／選台", 0xFF4D4B42, this::openRequestedCctv);
    else addShelf(shelf, "GitHub 片單", "一鍵拉取／記住網址", 0xFF4D4B42,
        () -> sourceManager.openGithubPlaylist());
    addShelf(shelf, "播放診斷", "目前頻道／來源狀態", 0xFF354969, () -> showProgramPanel(playingId));
    // Explicit neighbors keep the central play/import action easy to reach with a TV remote.
    homePreview.setNextFocusUpId(live.getId());
    homePreview.setNextFocusDownId(sourceShortcut.getId());
    homePreview.setNextFocusLeftId(cctv.getId());
    homePreview.setNextFocusRightId(officialLive.getId());
    for (Button tile : new Button[] {cctv, cartoons, satellite, hongKong})
      tile.setNextFocusRightId(homePreview.getId());
    for (Button tile : new Button[] {officialLive, officialClips, personal})
      tile.setNextFocusLeftId(homePreview.getId());
    for (Button tab : new Button[] {allButton, live, savedButton, searchButton})
      tab.setNextFocusDownId(homePreview.getId());
    for (int i = 0; i < shelf.getChildCount(); i++)
      shelf.getChildAt(i).setNextFocusUpId(homePreview.getId());
    privacy = text("方向鍵移動  ·  OK 確認  ·  返回逐層退出  ·  自備來源依使用權播放", 12, MUTED);
    privacy.setPadding(0, dp(8), 0, 0);
    content.addView(privacy);
    homeAdSlot = row();
    content.addView(homeAdSlot, new LinearLayout.LayoutParams(-1, dp(40)));
    updateHomeAdPreview();
    // Detail/search model controls stay available to existing dialogs; the homepage remains
    // uncluttered.
    channelList = column();
    resultLabel = text("", 12, MUTED);
    detail = text("", 13, MUTED);
    guide = text("", 12, MUTED);
    metrics = text("", 11, MUTED);
    sourceBadge = text("", 12, MINT);
    playButton = button("播放", () -> playSelected(true));
    favoriteButton = button("收藏", this::toggleFavorite);
  }

  private Button addHomeTile(
      LinearLayout parent, String title, String subtitle, int color, Runnable action) {
    Button tile = button(title + "\n" + subtitle, action);
    styleTileText(tile, title, subtitle);
    tile.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
    tile.setTextSize(14);
    tile.setBackground(tileBackground(color));
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 0, 1);
    lp.bottomMargin = dp(9);
    parent.addView(tile, lp);
    return tile;
  }

  private Button addShelf(
      LinearLayout parent, String title, String subtitle, int color, Runnable action) {
    Button tile = button(title + "\n" + subtitle, action);
    styleTileText(tile, title, subtitle);
    tile.setTextSize(14);
    tile.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
    tile.setBackground(tileBackground(color));
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1);
    lp.rightMargin = dp(10);
    parent.addView(tile, lp);
    return tile;
  }

  private void styleTileText(Button tile, String title, String subtitle) {
    android.text.SpannableString label = new android.text.SpannableString(title + "\n" + subtitle);
    label.setSpan(
        new android.text.style.AbsoluteSizeSpan(16, true),
        0,
        title.length(),
        0);
    label.setSpan(
        new android.text.style.AbsoluteSizeSpan(12, true), title.length() + 1, label.length(), 0);
    label.setSpan(
        new android.text.style.ForegroundColorSpan(MUTED), title.length() + 1, label.length(), 0);
    tile.setText(label);
    tile.setMaxLines(2);
    tile.setEllipsize(android.text.TextUtils.TruncateAt.END);
  }

  private StateListDrawable tileBackground(int color) {
    StateListDrawable states = new StateListDrawable();
    states.addState(new int[] {android.R.attr.state_focused}, shape(color, MINT, 3));
    states.addState(new int[] {android.R.attr.state_pressed}, shape(color, MINT, 3));
    states.addState(new int[] {}, shape(color, 0x306E859C, 1));
    return states;
  }

  private void showService(String title, String message) {
    new AlertDialog.Builder(this)
        .setTitle(title)
        .setMessage(message)
        .setPositiveButton("管理自備來源", (d, w) -> sourceManager.show())
        .setNegativeButton("返回", null)
        .show();
  }

  private void renderChannels() {
    SearchEngine.Intent intent = intentProvider.parse(query);
    if (filter.equals("favorites"))
      intent =
          new SearchEngine.Intent("", Catalog.Category.ALL, SearchEngine.TimeWindow.ANY, "", true);
    List<SearchEngine.Result> results =
        search.search(repo.catalog, repo.guide, intent, favorites, Instant.now());
    visibleChannels = new ArrayList<>();
    for (SearchEngine.Result result : results) if (!result.channel.sources.isEmpty()) visibleChannels.add(result.channel);
    java.util.Set<String> resultKeys=new java.util.HashSet<>();
    visibleChannels.removeIf(c -> !resultKeys.add(CatalogRepository.stationKey(c)));
    hasResults = !visibleChannels.isEmpty();
    if (hasResults && visibleChannels.stream().noneMatch(c -> c.id.equals(selectedId))
        && (!query.isEmpty() || filter.equals("favorites") || repo.catalog.find(selectedId)==null))
      selectedId = visibleChannels.get(0).id;
    updateDetail();
  }

  private void updateDetail() {
    if (repo == null || heading == null) return;
    boolean canResume = playingId != null && repo.catalog.find(playingId) != null;
    homePreview.setContentDescription(canResume ? "中央續播視窗，OK 進入全屏" : "中央續播視窗，OK 匯入片單");
    previewLabel.setText(canResume ? "按 OK 全屏觀看" : "按 OK 匯入片單");
    Catalog.Channel c = repo.catalog.find(selectedId);
    if (c == null) {
      heading.setText("匯入片單，開始觀看");
      playButton.setEnabled(false);
      favoriteButton.setEnabled(false);
      return;
    }
    playButton.setEnabled(hasResults);
    favoriteButton.setEnabled(hasResults);
    Catalog.Channel playing = repo.catalog.find(playingId);
    if (!hasResults) {
      sourceBadge.setText("");
      heading.setText(playing == null ? "匯入片單，開始觀看" : playbackDisplayName(playing));
      detail.setText("沒有足夠資料時，智能篩選不會編造結果。");
      guide.setText("可返回「全部頻道」繼續觀看。");
      return;
    }
    heading.setText(playing == null ? "匯入片單，開始觀看" : playbackDisplayName(playing));
    detail.setText(c.description + (c.isDemo() ? "" : "\n" + sourceSummary(c)));
    sourceBadge.setText(
        c.isDemo()
            ? "自有示範素材"
            : c.sources.stream().anyMatch(x -> x.rights == Catalog.Rights.USER_PROVIDED)
                ? "使用者自備 · 未核實"
                : c.sources.isEmpty() && OfficialWebActivity.isSupportedOfficialPage(repo.officialPage(c.id)) ? "官方網頁入口"
                : c.eligible(territory, Instant.now()).isEmpty() ? "來源不可用" : "已配置授權記錄");
    favoriteButton.setText(favorites.contains(c.id) ? "★ 已收藏" : "☆ 加入收藏");
    playButton.setText(c.id.equals(playingId) ? "重新播放" : c.isDemo() ? "播放示範" : "播放選中頻道");
    prefs.edit().putString("selected", c.id).apply();
    updateGuide();
  }

  private void updateGuide() {
    if (repo == null || guide == null || selectedId == null || !hasResults) return;
    Guide.Program now = repo.guide.now(selectedId, Instant.now());
    if (now == null)
      guide.setText(
          repo.catalog.find(selectedId).isDemo() ? R.string.no_guide_demo : R.string.no_guide);
    else {
      DateTimeFormatter f =
          DateTimeFormatter.ofPattern("HH:mm")
              .withZone(ZoneId.of(territory.equals("HK") ? "Asia/Hong_Kong" : "Asia/Taipei"));
      guide.setText(
          getString(
              R.string.program_detail,
              now.title,
              f.format(now.start),
              f.format(now.end),
              now.sourceId,
              f.format(now.fetchedAt)));
    }
  }

  private String playbackDisplayName(Catalog.Channel channel) {
    return (repo.linkedUserChannel(channel.id) == null ? "" : "自備連結 · ") + channel.name;
  }

  private String statusText() {
    // The channel name already appears in the preview heading; preserve room for the real status.
    return lastStatus;
  }

  private void playSelected(boolean expand) {
    selectionGeneration++;
    Catalog.Channel c = repo.playbackChannel(repo.catalog.find(selectedId));
    if (c == null || playback == null) return;
    if (c.sources.isEmpty()) {
      String official = repo.officialPage(c.id);
      if (OfficialWebActivity.isSupportedOfficialPage(official)) {
        closeDrawer();
        openOfficialPage(official);
      } else showProgramPanel(c.id);
      return;
    }
    if(BuildConfig.PERSONAL_BUILD && c.sources.stream().anyMatch(x -> x.uri.startsWith("http://"))
        && c.eligible(territory, Instant.now()).isEmpty() && !new HttpPlaybackConsent(this).globalEnabled()) {
      showHttpSettings(() -> playSelected(expand)); return;
    }
    playingId = c.id;
    prefs.edit().putString("lastPlayed", c.id).putString("lastMode", "native").apply();
    closeDrawer();
    resumeWantsPlayback = true;
    resumeTerminal = false;
    setPlaybackVisibility(true);
    playback.start(repo.playbackChannel(c), territory);
    updateDetail();
    if (expand) enterFullScreen();
  }

  private void toggleFavorite() {
    if (favorites.contains(selectedId)) favorites.remove(selectedId);
    else favorites.add(selectedId);
    prefs.edit().putStringSet("favorites", new HashSet<>(favorites)).apply();
    renderChannels();
  }

  private void focusChannel(String id) {
    Button b = channelButtons.get(id);
    if (b != null && b.isAttachedToWindow()) b.requestFocus();
    else if (homePreview != null) homePreview.requestFocus();
    else allButton.requestFocus();
  }

  private void showSearch() {
    LinearLayout box = column();
    box.setPadding(dp(24), dp(12), dp(24), 0);
    TextView note = text(intentProvider.disclosure() + "\n只查現有目錄與節目單，不生成節目事實。", 14, MUTED);
    box.addView(note);
    EditText field = new EditText(this);
    field.setTextColor(TEXT);
    field.setSingleLine(true);
    field.setInputType(InputType.TYPE_CLASS_TEXT);
    field.setHintTextColor(MUTED);
    field.setHint("輸入已匯入的頻道、語言或群組");
    field.setText(query);
    field.setSelectAllOnFocus(true);
    field.setContentDescription("智能找台搜尋文字");
    field.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
    box.addView(field, new LinearLayout.LayoutParams(-1, dp(58)));
    LinearLayout suggestions = row();
    for (String s : new String[] {"新聞", "少兒", "正在播"}) {
      Button b = button(s, () -> field.setText(s));
      LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(46), 1);
      p.rightMargin = dp(6);
      suggestions.addView(b, p);
    }
    box.addView(suggestions);
    AlertDialog d =
        new AlertDialog.Builder(this)
            .setTitle("智能找台")
            .setView(box)
            .setNegativeButton("取消", (dialog, which) -> searchButton.requestFocus())
            .setPositiveButton(
                "搜尋",
                (dialog, which) -> {
                  query = field.getText().toString().trim();
                  if (query.length() > 120) query = query.substring(0, 120);
                  filter = "search";
                  renderChannels();
                  showSearchResults();
                })
            .create();
    d.setOnCancelListener(dialog -> searchButton.requestFocus());
    d.show();
    field.setOnEditorActionListener((v,action,event)->{
      if(action==android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH || (event!=null&&event.getKeyCode()==KeyEvent.KEYCODE_ENTER&&event.getAction()==KeyEvent.ACTION_UP)) {
        ((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(field.getWindowToken(),0);
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();return true;
      }
      return false;
    });
    field.requestFocus();
  }

  private Button roundSearchButton() {
    Button b = button("", this::showSearch);
    b.setContentDescription("搜尋頻道");
    b.setMinWidth(0); b.setMinimumWidth(0); b.setPadding(0,0,0,0);
    b.setBackground(new android.graphics.drawable.Drawable() {
      final android.graphics.Paint paint = new android.graphics.Paint(3);
      public void draw(android.graphics.Canvas canvas) {
        android.graphics.Rect r=getBounds(); float cx=r.exactCenterX(),cy=r.exactCenterY(),radius=Math.min(r.width(),r.height())*.46f;
        paint.setStyle(android.graphics.Paint.Style.FILL);paint.setColor(b.isFocused()?0xFF286B70:0xFF193747);canvas.drawCircle(cx,cy,radius,paint);
        paint.setStyle(android.graphics.Paint.Style.STROKE);paint.setStrokeWidth(dp(2));paint.setColor(MINT);
        canvas.drawCircle(cx-dp(2),cy-dp(2),dp(7),paint);canvas.drawLine(cx+dp(3),cy+dp(3),cx+dp(10),cy+dp(10),paint);
        if(b.isFocused())canvas.drawCircle(cx,cy,radius-dp(1),paint);
      }
      public void setAlpha(int alpha){} public void setColorFilter(android.graphics.ColorFilter f){}
      public int getOpacity(){return android.graphics.PixelFormat.TRANSLUCENT;}
    });
    b.setOnFocusChangeListener((v,focus)->v.invalidate());
    return b;
  }

  private void showHttpSettings(Runnable afterEnable) {
    if(!BuildConfig.PERSONAL_BUILD)return;
    HttpPlaybackConsent policy=new HttpPlaybackConsent(this);
    LinearLayout box=column();box.setPadding(dp(24),dp(12),dp(24),dp(12));
    box.addView(text("允許所有已匯入頻道使用 HTTP，包括播放所需的轉址、分片及密鑰。HTTP 不加密，內容可能被觀察或竄改。可隨時在設定關閉；不會略過 HTTPS 憑證驗證。",15,TEXT));
    android.widget.Switch toggle=new android.widget.Switch(this);
    toggle.setText("允許 HTTP 播放");toggle.setTextColor(TEXT);toggle.setChecked(policy.globalEnabled());box.addView(toggle);
    android.widget.CheckBox remember=new android.widget.CheckBox(this);
    remember.setText("記住選擇，下次不再詢問");remember.setTextColor(TEXT);remember.setChecked(true);box.addView(remember);
    new AlertDialog.Builder(this).setTitle("HTTP 播放設定").setView(box)
      .setNegativeButton("取消",null).setPositiveButton("儲存",(d,w)->{
        policy.setGlobalAllowed(toggle.isChecked(),remember.isChecked());
        if(!toggle.isChecked()&&playback!=null){playback.stop();playingId=null;setPlaybackVisibility(false);}
        refreshCatalog(false,()->{
          if(toggle.isChecked()&&afterEnable!=null)afterEnable.run();
          else if(drawerOpen&&menuLevel==1)showCategoryDrawer(drawerCategory);
        });
      }).show();
  }

  private void updateHomeAdPreview() {
    if (homeAdSlot == null) return;
    homeAdSlot.removeAllViews();
    boolean visible = !BuildConfig.PERSONAL_BUILD && prefs.getBoolean("home_ad_preview", false);
    homeAdSlot.setVisibility(visible ? View.VISIBLE : View.GONE);
    if (!visible) return;
    homeAdSlot.setBackgroundColor(SURFACE);
    TextView label = text("廣告 · 版位預覽（未投放）", 14, MUTED);
    label.setGravity(Gravity.CENTER_VERTICAL);
    homeAdSlot.addView(label, new LinearLayout.LayoutParams(0, -1, 1));
    homeAdSlot.addView(button("關閉", () -> {
      prefs.edit().putBoolean("home_ad_preview", false).apply();
      updateHomeAdPreview();
      sourceShortcut.requestFocus();
    }), new LinearLayout.LayoutParams(dp(100), -1));
  }

  private void showSettings() {
    new AlertDialog.Builder(this)
        .setTitle("設定與說明")
        .setItems(
            new String[] {"測試地區：" + territory, "素材、AI 與隱私說明", "清除收藏", "HTTP 播放：" + (new HttpPlaybackConsent(this).globalEnabled()?"開啟":"關閉"), "線路自檢／修復", "首頁廣告版位預覽" },
            (d, which) -> {
              if (which == 5) {
                if(BuildConfig.PERSONAL_BUILD) { Toast.makeText(this,"個人版不顯示廣告",Toast.LENGTH_SHORT).show(); return; }
                prefs.edit().putBoolean("home_ad_preview", !prefs.getBoolean("home_ad_preview",false)).apply();
                updateHomeAdPreview(); return;
              }
              if (which == 4) { startActivityForResult(new android.content.Intent(this,LineRepairActivity.class),2404);return; }
              if (which == 3) { if(BuildConfig.PERSONAL_BUILD)showHttpSettings(null);else Toast.makeText(this,"商業預覽僅使用 HTTPS 來源",Toast.LENGTH_SHORT).show();return; }
              if (which == 0)
                new AlertDialog.Builder(this)
                    .setTitle("選擇測試地區")
                    .setSingleChoiceItems(
                        new String[] {"香港 HK", "台灣 TW", "中國大陸 CN", "澳門 MO"},
                        Arrays.asList("HK", "TW", "CN", "MO").indexOf(territory),
                        (dialog, index) -> {
                          territory = new String[] {"HK", "TW", "CN", "MO"}[index];
                          prefs.edit().putString("territory", territory).apply();
                          if (playback != null) playback.stop();
                          playingId = null;
                          setPlaybackVisibility(false);
                          exitFullScreen();
                          lastStatus = "測試地區已切換，請重新選擇播放";
                          status.setText(lastStatus);
                          updateDetail();
                          dialog.dismiss();
                        })
                    .setNegativeButton("取消", null)
                    .show();
              else if (which == 1)
                new AlertDialog.Builder(this)
                    .setTitle("這個原型做了甚麼？")
                    .setMessage(
                        "版本 "+BuildConfig.VERSION_NAME+"\n\n"
                        +(BuildConfig.PERSONAL_BUILD?"個人測試版，包含使用者指定的來源。":"商業預覽版，不預裝電視頻道，廣告功能尚未啟用。")
                        +"\n\n片單、收藏、觀看選擇與線路質檢結果存於本機。匯入及播放會連線至來源伺服器；沒有廣告或分析 SDK。"
                        +"\n\n自檢是短時畫面觀察，不保證節目身份、音訊或持續可用。回放須由來源提供歷史節目。"
                        +"\n\nWebTvLive 參考代碼：MIT 授權，說明隨工程附上。")
                    .setPositiveButton("知道了", null)
                    .show();
              else
                new AlertDialog.Builder(this)
                    .setTitle("清除所有收藏？")
                    .setMessage("只會移除此裝置儲存的收藏頻道。這項操作無法復原。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton(
                        "清除",
                        (dialog, w) -> {
                          favorites.clear();
                          prefs.edit().remove("favorites").apply();
                          renderChannels();
                        })
                    .show();
            })
        .setNegativeButton("返回", null)
        .show();
  }

  private void enterFullScreen() {
    if (fullScreen) {
      closeDrawer();
      return;
    }
    fullScreen = true;
    // A return-to-preview handoff may still be waiting for its Surface.
    if (fullHost != null) {
      settlePlayerTarget();
      fullHost.requestFocus();
      updateFullTitle();
      return;
    }
    fullHost = new FrameLayout(this);
    fullHost.setBackgroundColor(Color.BLACK);
    root.addView(fullHost, match());
    fullPlayerView = new PlayerView(this);
    fullPlayerView.setUseController(false);
    fullPlayerView.setFocusable(false);
    fullPlayerView.setKeepContentOnPlayerReset(false);
    fullPlayerView.setContentDescription("全屏播放視窗");
    fullHost.addView(fullPlayerView, match());
    LinearLayout overlay = column();
    fullOverlay = overlay;
    overlay.setPadding(dp(32), dp(20), dp(32), dp(20));
    overlay.setBackground(
        new GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM, new int[] {0xD908131F, 0x0008131F}));
    fullTitle = text("", 20, TEXT);
    fullTitle.setTypeface(null, Typeface.BOLD);
    overlay.addView(fullTitle);
    TextView hint = text("↑ ↓ 切換頻道   OK 開啟左側導航   返回鍵回首頁", 13, MUTED);
    hint.setPadding(0, dp(6), 0, 0);
    overlay.addView(hint);
    fullHost.addView(overlay, new FrameLayout.LayoutParams(-1, dp(110), Gravity.TOP));
    fullHost.setFocusable(true);
    fullHost.setFocusableInTouchMode(true);
    fullHost.requestFocus();
    settlePlayerTarget();
    updateFullTitle();
  }

  private void updateFullTitle() {
    if (fullTitle != null) {
      Catalog.Channel c = repo.catalog.find(playingId);
      if (c == null || c.sources.isEmpty() || drawerOpen) {
        fullOverlay.setVisibility(View.GONE);
        clockHandler.removeCallbacks(hideFullOverlay);
        return;
      }
      fullTitle.setText(getString(R.string.playback_status, playbackDisplayName(c), lastStatus));
      fullOverlay.setVisibility(View.VISIBLE);
      clockHandler.removeCallbacks(hideFullOverlay);
      if (playback != null && playback.wantsPlayback() && !playback.isTerminal())
        clockHandler.postDelayed(hideFullOverlay, 5000);
    }
  }

  private void exitFullScreen() {
    if (!fullScreen) return;
    closeDrawer();
    fullScreen = false;
    // Restore the preview Surface before detaching the full-screen target.
    content.setVisibility(View.VISIBLE);
    clockHandler.removeCallbacks(hideFullOverlay);
    settlePlayerTarget();
    homePreview.requestFocus();
  }

  private void setPlaybackVisibility(boolean visible) {
    int visibility = visible ? View.VISIBLE : View.INVISIBLE;
    if (playerView != null) playerView.setVisibility(visibility);
    if (fullPlayerView != null) fullPlayerView.setVisibility(visibility);
  }

  private void updatePlayerKeepScreenOn() {
    if (playerView != null)
      playerView.setKeepScreenOn(playbackKeepScreenOn && attachedPlayerView == playerView);
    if (fullPlayerView != null)
      fullPlayerView.setKeepScreenOn(playbackKeepScreenOn && attachedPlayerView == fullPlayerView);
  }

  private void cancelPendingSurfaceSwitch() {
    if (pendingSurfaceHolder != null && pendingSurfaceCallback != null)
      pendingSurfaceHolder.removeCallback(pendingSurfaceCallback);
    pendingSurfaceHolder = null;
    pendingSurfaceCallback = null;
  }

  private void settlePlayerTarget() {
    cancelPendingSurfaceSwitch();
    PlayerView target = fullScreen ? fullPlayerView : playerView;
    if (target == null) return;
    setPlaybackVisibility(playingId != null);
    if (!fullScreen) content.setVisibility(View.VISIBLE);
    Runnable handoff =
        () -> {
          if (target != (fullScreen ? fullPlayerView : playerView)) return;
          if (playback != null) {
            // Media3 attaches the new target before detaching the old target.
            PlayerView.switchTargetView(playback.player, attachedPlayerView, target);
            attachedPlayerView = target;
          }
          updatePlayerKeepScreenOn();
          if (fullScreen) {
            content.setVisibility(View.GONE);
          } else if (fullHost != null) {
            if (fullPlayerView != null) fullPlayerView.setPlayer(null);
            root.removeView(fullHost);
            fullPlayerView = null;
            fullHost = null;
            fullTitle = null;
            fullOverlay = null;
          }
        };
    View surface = target.getVideoSurfaceView();
    if (playback != null && playingId != null && surface instanceof SurfaceView) {
      SurfaceHolder holder = ((SurfaceView) surface).getHolder();
      if (holder.getSurface()==null || !holder.getSurface().isValid()) {
        pendingSurfaceHolder = holder;
        pendingSurfaceCallback =
            new SurfaceHolder.Callback() {
              @Override
              public void surfaceCreated(SurfaceHolder readyHolder) {
                if (pendingSurfaceCallback != this) return;
                cancelPendingSurfaceSwitch();
                handoff.run();
              }

              @Override
              public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}

              @Override
              public void surfaceDestroyed(SurfaceHolder holder) {}
            };
        holder.addCallback(pendingSurfaceCallback);
        return;
      }
    }
    handoff.run();
  }

  private void switchChannel(int direction) {
    List<Catalog.Channel> playable = new ArrayList<>();
    for (Catalog.Channel c : visibleChannels)
      if (!c.eligible(territory, Instant.now()).isEmpty()) playable.add(c);
    if (playable.isEmpty()) return;
    int current = 0;
    for (int i = 0; i < playable.size(); i++) if (playable.get(i).id.equals(playingId)) current = i;
    selectedId = playable.get(Math.floorMod(current + direction, playable.size())).id;
    playSelected(false);
    updateFullTitle();
  }

  @Override
  public boolean dispatchKeyEvent(KeyEvent event) {
    int remoteKey = event.getKeyCode();
    if (repo == null && remoteKey != KeyEvent.KEYCODE_BACK)
      return super.dispatchKeyEvent(event);
    if (remoteKey == KeyEvent.KEYCODE_MENU
        || remoteKey == KeyEvent.KEYCODE_SETTINGS
        || remoteKey == KeyEvent.KEYCODE_TV_CONTENTS_MENU) {
      if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) showSettings();
      return true;
    }
    if (event.getAction() == KeyEvent.ACTION_DOWN
        && event.getRepeatCount() == 0
        && playback != null
        && playingId != null) {
      int key = event.getKeyCode();
      if (key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
        if (!playback.isTerminal()) playback.setPaused(playback.wantsPlayback());
        return true;
      }
      if (key == KeyEvent.KEYCODE_MEDIA_PAUSE) {
        playback.setPaused(true);
        return true;
      }
      if (key == KeyEvent.KEYCODE_MEDIA_PLAY) {
        playback.setPaused(false);
        return true;
      }
    }
    if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
      if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) navigateBack();
      return true;
    }
    if (fullScreen && drawerOpen) return super.dispatchKeyEvent(event);
    if (fullScreen
        && event.getAction() == KeyEvent.ACTION_UP
        && (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_CENTER
            || event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) return true;
    if (fullScreen && event.getAction() == KeyEvent.ACTION_DOWN) {
      int code = event.getKeyCode();
      if (event.getRepeatCount() > 0) return true;
      if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_CHANNEL_UP) {
        switchChannel(-1);
        return true;
      }
      if (code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_CHANNEL_DOWN) {
        switchChannel(1);
        return true;
      }
      if (code == KeyEvent.KEYCODE_DPAD_CENTER
          || code == KeyEvent.KEYCODE_ENTER
          || code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
        showDrawer();
        return true;
      }
    }
    return super.dispatchKeyEvent(event);
  }

  @Override
  public void onBackPressed() {
    navigateBack();
  }

  private void navigateBack() {
    selectionGeneration++;
    if (repo == null) { finish(); return; }
    if (drawerOpen) {
      if (menuLevel == 2) showCategoryDrawer(drawerCategory);
      else closeDrawer();
    } else if (fullScreen) goHome();
    else confirmExit();
  }

  private void goHome() {
    if (fullScreen) exitFullScreen();
    else closeDrawer();
    if (homePreview != null) homePreview.requestFocus();
  }

  private void confirmExit() {
    if (exitDialog != null && exitDialog.isShowing()) return;
    exitDialog = new AlertDialog.Builder(this)
        .setTitle("離開瞬頻 TV？")
        .setMessage("選擇取消可留在首頁。")
        .setNegativeButton("取消", (d, which) -> homePreview.requestFocus())
        .setPositiveButton("離開", (d, which) -> finish())
        .create();
    exitDialog.setOnCancelListener(d -> homePreview.requestFocus());
    exitDialog.show();
    exitDialog.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus();
  }

  private void addHomeMenuButton(LinearLayout parent) {
    Button home = button("⌂ 返回首頁", this::goHome);
    home.setContentDescription("返回首頁");
    home.setTextColor(MINT);
    addDrawerButton(parent, home);
  }

  private void openChannelLibrary(String channelId) {
    android.content.Intent intent = new android.content.Intent(this, ChannelLibraryActivity.class);
    if (channelId != null) intent.putExtra("channel_id", channelId);
    startActivityForResult(intent, 2402);
  }

  private void refreshCatalog(boolean allowRequestedPlayback) {
    refreshCatalog(allowRequestedPlayback, null);
  }

  private void refreshCatalog(boolean allowRequestedPlayback, Runnable afterRefresh) {
    if (activityDestroyed || isFinishing()) return;
    int generation = ++catalogGeneration;
    deferredCatalogResult = null;
    int selectedGeneration = selectionGeneration;
    catalogRefreshPending = true;
    String requestedAtStart = prefs.getString("requested_play", null);
    if (catalogNotice == null) {
      catalogNotice = text("正在更新頻道目錄… 可繼續操作或按返回", 14, TEXT);
      catalogNotice.setPadding(dp(16), dp(10), dp(16), dp(10));
      catalogNotice.setBackgroundColor(SURFACE);
      root.addView(catalogNotice, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
    }
    catalogExecutor.execute(() -> {
      try {
        SupplementalSources.seed(getApplicationContext());
        CatalogRepository loaded = new CatalogRepository(getApplicationContext());
        deliverCatalogResult(generation, () -> {
          catalogRefreshPending = false;
          clearCatalogNotice();
          Catalog.Channel previouslyPlaying = repo.catalog.find(playingId);
          repo = loaded;
          Catalog.Channel refreshedPlaying = repo.catalog.find(playingId);
          boolean playingSourceChanged = previouslyPlaying != null
              && !samePlaybackSources(previouslyPlaying, refreshedPlaying);
          favorites.removeIf(id -> repo.catalog.find(id) == null);
          if (repo.catalog.find(selectedId) == null)
            selectedId = repo.catalog.channels.isEmpty() ? null : repo.catalog.channels.get(0).id;
          prefs.edit().putStringSet("favorites", new HashSet<>(favorites)).apply();
          if (playingId != null && (refreshedPlaying == null || playingSourceChanged)) {
            if (playback != null) playback.stop();
            playingId = null;
            setPlaybackVisibility(false);
            prefs.edit().remove("lastPlayed").apply();
            goHome();
            lastStatus = "播放來源已移除或變更，請確認後重新播放";
            status.setText(lastStatus);
          }
          renderChannels();
          // Only consume the request belonging to this load; newer user choices win.
          if (allowRequestedPlayback && selectedGeneration == selectionGeneration
              && Objects.equals(requestedAtStart, prefs.getString("requested_play", null))) {
            Catalog.Channel c = repo.catalog.find(requestedAtStart);
            prefs.edit().remove("requested_play").apply();
            if (c != null) {
              selectedId = c.id;
              filter = "all";
              query = "";
              renderChannels();
              startPlaybackIfReady();
              playSelected(false);
            }
          }
          updateDetail();
          startPlaybackIfReady();
          if(playingId==null && playback!=null && (!BuildConfig.PERSONAL_BUILD || new HttpPlaybackConsent(this).globalEnabled())) {
            Catalog.Channel first=repo.catalog.channels.stream().filter(c -> !c.isDemo()&&!c.eligible(territory,Instant.now()).isEmpty())
              .sorted(java.util.Comparator.comparingInt(c -> c.sources.stream().anyMatch(x -> SourceManager.hasDevelopmentPlaybackObservation(x.uri))?0:1)).findFirst().orElse(null);
            if(first!=null){selectedId=first.id;filter="all";query="";renderChannels();playSelected(false);}
          }
          if (afterRefresh != null && selectedGeneration == selectionGeneration) afterRefresh.run();
          if (drawerOpen && menuLevel == 1) showCategoryDrawer(drawerCategory);
        });
      } catch (Exception error) {
        deliverCatalogResult(generation, () -> {
          catalogRefreshPending = false;
          clearCatalogNotice();
          startPlaybackIfReady();
          new AlertDialog.Builder(this).setTitle("目錄更新失敗")
              .setMessage("保留原有目錄。可重試，或按返回繼續使用。")
              .setPositiveButton("重試", (d, which) -> refreshCatalog(allowRequestedPlayback, afterRefresh))
              .setNegativeButton("返回", null).show();
        });
      }
    });
  }

  private static boolean samePlaybackSources(Catalog.Channel before, Catalog.Channel after) {
    if (after == null || before.sources.size() != after.sources.size()) return false;
    for (int i = 0; i < before.sources.size(); i++) {
      Catalog.Source a = before.sources.get(i), b = after.sources.get(i);
      if (!a.id.equals(b.id) || !a.uri.equals(b.uri) || a.rights != b.rights
          || !a.territories.equals(b.territories) || !a.validFrom.equals(b.validFrom)
          || !a.validUntil.equals(b.validUntil)) return false;
    }
    return true;
  }

  private void openSourceBinding(String presetId) {
    startActivityForResult(new android.content.Intent(this, SourceBindingActivity.class)
        .putExtra("preset_id", presetId), 2403);
  }

  private void clearCatalogNotice() {
    if (catalogNotice != null) root.removeView(catalogNotice);
    catalogNotice = null;
  }

  private void handleNavigationIntent(android.content.Intent intent) {
    if (repo == null || sourceManager == null) return;
    if (intent.getBooleanExtra("open_home", false)) { intent.removeExtra("open_home"); goHome(); return; }
    String category = intent.getStringExtra("open_category");
    boolean sources = intent.getBooleanExtra("open_sources", false);
    String action = intent.getStringExtra("command_action");
    String commandQuery = intent.getStringExtra("command_query");
    String channelId = intent.getStringExtra("command_channel_id");
    for (String key : new String[] {"open_category", "open_sources", "command_action",
        "command_query", "command_channel_id"}) intent.removeExtra(key);
    if (sources) { goHome(); sourceManager.show(); return; }
    if (category != null && Arrays.asList("全部", "CCTV", "卡通少兒", "衛視", "地方", "香港", "其他", "自備", "收藏").contains(category)) {
      showCategoryDrawer(category);
      return;
    }
    if ("search".equals(action)) {
      query = commandQuery == null ? "" : commandQuery.trim();
      if (query.length() > 120) query = query.substring(0, 120);
      filter = "search";
      renderChannels();
      showSearchResults();
    } else if ("play".equals(action) || "favorite".equals(action)) {
      Catalog.Channel c = repo.catalog.find(channelId);
      if (c == null) {
        showService("頻道已變更", "此頻道已不在本地目錄，請重新搜尋。");
        return;
      }
      selectedId = c.id;
      filter = "all";
      query = "";
      renderChannels();
      if ("favorite".equals(action)) {
        favorites.add(c.id);
        prefs.edit().putStringSet("favorites", new HashSet<>(favorites)).apply();
        showCategoryDrawer("收藏");
      } else if (!c.eligible(territory, Instant.now()).isEmpty()) playSelected(true);
      else showProgramPanel(c.id);
    }
  }

  private void openOfficialPage() {
    prefs.edit().putString("lastMode", "official").apply();
    startActivity(OfficialWebActivity.launchIntent(this));
  }

  private void openOfficialPage(String url) {
    if (!OfficialWebActivity.isSupportedOfficialPage(url)) return;
    prefs.edit().putString("lastMode", "official").apply();
    startActivity(OfficialWebActivity.launchIntent(this, url));
  }

  private String sourceSummary(Catalog.Channel c) {
    if (c.sources.isEmpty()) return repo != null && OfficialWebActivity.isSupportedOfficialPage(repo.officialPage(c.id))
        ? "官方網頁入口 · 音畫與地區可播性未驗證" : "尚未接入來源";
    Catalog.Source s = c.sources.get(0);
    String blocked = s.blockedReason(territory, Instant.now());
    String connectionStatus =
        c.id.equals(playingId) && lastStatus != null && !lastStatus.trim().isEmpty()
            ? lastStatus
            : "待連線驗證";
    return "共 "+repo.playbackChannel(c).sources.size()+" 條不同線路 · 地區 "
        + String.join(" / ", s.territories)
        + " · "
        + (blocked == null ? connectionStatus : blocked);
  }

  @Override
  protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    if(requestCode==2404){refreshCatalog(false);return;}
    if (requestCode == 2403) {
      String preset = data == null ? drawerSelectionId : data.getStringExtra("preset_id");
      refreshCatalog(false, () -> {
        if (repo.isPreset(preset)) showProgramPanel(preset);
      });
    } else if (requestCode == 2402) {
      refreshCatalog(false, () -> {
      if (data != null && data.getBooleanExtra("return_home", false)) goHome();
      else if (data != null && data.getStringExtra("play_channel_id") != null) {
        String id = data.getStringExtra("play_channel_id");
        root.post(() -> {
          Catalog.Channel c = repo.catalog.find(id);
          if (c == null || c.eligible(territory, Instant.now()).isEmpty()) {
            showService("無法播放", "此頻道已移除或目前沒有符合地區與使用條件的來源。");
            return;
          }
          selectedId = id;
          filter = "all";
          query = "";
          renderChannels();
          playSelected(true);
        });
      } else if (drawerOpen) {
        if (menuLevel == 2 && repo.catalog.find(drawerSelectionId) != null)
          showProgramPanel(drawerSelectionId);
        else if (menuLevel == 0) showAppMenu();
        else showCategoryDrawer(drawerCategory);
      }
      });
    } else if (sourceManager != null) sourceManager.onActivityResult(requestCode, resultCode, data);
  }

  private void showDrawer() {
    showChannelDrawer(false);
  }

  private void showChannelDrawer(boolean onlyFavorites) {
    showCategoryDrawer(onlyFavorites ? "收藏" : "全部");
  }

  private void showCategoryDrawer(String category) {
    selectionGeneration++;
    if (!fullScreen) enterFullScreen();
    closeDrawer();
    drawerOpen = true;
    if (fullOverlay != null) fullOverlay.setVisibility(View.GONE);
    menuLevel = 1;
    drawerCategory = category;
    drawer = column();
    drawer.setPadding(dp(16), dp(18), dp(16), dp(16));
    drawer.setBackgroundColor(0xDB0B1320);
    TextView title = text("瞬頻 TV   /   頻道", 20, TEXT);
    title.setPadding(dp(6), 0, 0, dp(14));
    LinearLayout drawerHeader = row();
    drawerHeader.setGravity(Gravity.CENTER_VERTICAL);
    drawerHeader.addView(title, new LinearLayout.LayoutParams(0,-2,1));
    drawerHeader.addView(roundSearchButton(), new LinearLayout.LayoutParams(dp(40),dp(40)));
    drawer.addView(drawerHeader);
    addHomeMenuButton(drawer);
    LinearLayout columns = row();
    drawer.addView(columns, new LinearLayout.LayoutParams(-1, 0, 1));
    LinearLayout cats = column();
    ScrollView catScroll = new ScrollView(this);
    catScroll.addView(cats);
    catScroll.setVerticalScrollBarEnabled(false);
    columns.addView(catScroll, new LinearLayout.LayoutParams(dp(96), -1));
    ListView list = new ListView(this);
    list.setId(View.generateViewId());
    list.setItemsCanFocus(false);
    list.setDivider(null);
    list.setSelector(shape(0xFF214F53, MINT, 2));
    list.setFocusable(true);
    columns.addView(list, new LinearLayout.LayoutParams(0, -1, 1));
    java.util.LinkedHashSet<String> categories =
        new java.util.LinkedHashSet<>(Arrays.asList("全部", "CCTV", "衛視", "香港", "收藏", "自備"));
    categories.addAll(repo.groups.values());
    categories.add("應用");
    Button selectedCategoryButton = null;
    for (String cat : categories) {
      Button tab =
          button(
              cat,
              () -> {
                if (cat.equals("應用")) showAppMenu();
                else {
                  drawerCategory = cat;
                  fillDrawerChannels(list);
                }
              });
      tab.setSelected(cat.equals(category));
      if (cat.equals(category)) selectedCategoryButton = tab;
      tab.setTextSize(15);
      addDrawerButton(cats, tab);
      tab.setOnFocusChangeListener(
          (v, focus) -> {
            if (focus && !cat.equals("應用")) {
              drawerCategory = cat;
              for (int i = 0; i < cats.getChildCount(); i++)
                cats.getChildAt(i).setSelected(cats.getChildAt(i) == v);
              fillDrawerChannels(list);
              list.setNextFocusLeftId(v.getId());
            }
          });
    }
    fillDrawerChannels(list);
    TextView hint = text("← → 分類與頻道   OK 播放   長按 詳情   返回 收起", 11, MUTED);
    hint.setPadding(0, dp(10), 0, 0);
    drawer.addView(hint);
    fullHost.addView(drawer, new FrameLayout.LayoutParams(dp(470), -1, Gravity.START));
    animateDrawer();
    if (Boolean.TRUE.equals(list.getTag())) list.requestFocus();
    else if (selectedCategoryButton != null) selectedCategoryButton.requestFocus();
  }

  private void fillDrawerChannels(ListView list) {
    list.setContentDescription("頻道列表：" + drawerCategory);
    List<Catalog.Channel> channels = new ArrayList<>();
    for (Catalog.Channel c : repo.catalog.channels) {
      if (c.sources.isEmpty() || c.isDemo()) continue;
      if (drawerCategory.equals("收藏") && !favorites.contains(c.id)) continue;
      if (!drawerCategory.equals("收藏") && !repo.matchesCategory(c.id, drawerCategory)) continue;
      channels.add(c);
    }
    channels.sort(java.util.Comparator.comparingInt(repo::channelRank));
    java.util.Set<String> stationKeys=new java.util.HashSet<>();
    channels.removeIf(c -> !stationKeys.add(CatalogRepository.stationKey(c)));
    list.setTag(!channels.isEmpty());
    list.setAdapter(
        new BaseAdapter() {
          public int getCount() {
            return Math.max(1, channels.size());
          }

          public Object getItem(int position) {
            return channels.isEmpty() ? null : channels.get(position);
          }

          public long getItemId(int position) {
            return position;
          }

          public boolean areAllItemsEnabled() {
            return !channels.isEmpty();
          }

          public boolean isEnabled(int position) {
            return !channels.isEmpty();
          }

          public View getView(int position, View recycled, ViewGroup parent) {
            TextView row = recycled instanceof TextView ? (TextView) recycled : text("", 14, TEXT);
            row.setPadding(dp(15), dp(10), dp(12), dp(10));
            row.setMinHeight(dp(65));
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMaxLines(3);
            if (channels.isEmpty()) {
              row.setText("此分類暫無頻道\n可在應用選單匯入來源");
              return row;
            }
            Catalog.Channel c = channels.get(position);
            Guide.Program now = repo.guide.now(c.id, Instant.now());
            row.setText(
                c.number
                    + "   "
                    + c.name
                    + "\n"
                    + (c.sources.isEmpty()
                        ? "沒有播放來源"
                        : now == null ? (c.sources.stream().anyMatch(x -> x.uri.startsWith("http://")) && !new HttpPlaybackConsent(MainActivity.this).globalEnabled() ? "HTTP 尚未開啟 · OK 設定並播放" : "共 "+repo.playbackChannel(c).sources.size()+" 條線路 · OK 播放／失敗自動換線") : now.title));
            row.setContentDescription(c.name + "，OK 立即播放，長按節目與來源");
            return row;
          }
        });
    list.setOnItemClickListener(
        (parent, view, position, id) -> {
          if (!channels.isEmpty()) {
            drawerSelectionId = channels.get(position).id;
            selectedId = drawerSelectionId; filter = "all"; query = "";
            renderChannels(); playSelected(true);
          }
        });
    list.setOnItemLongClickListener((parent,view,position,id) -> {
      if(channels.isEmpty())return false;
      showProgramPanel(channels.get(position).id); return true;
    });
    int current = 0;
    for (int i = 0; i < channels.size(); i++)
      if (channels.get(i).id.equals(drawerSelectionId == null ? playingId : drawerSelectionId)) current = i;
    list.setSelection(current);
  }

  private void showAppMenu() {
    closeDrawer();
    drawerOpen = true;
    if (fullOverlay != null) fullOverlay.setVisibility(View.GONE);
    menuLevel = 0;
    drawer = column();
    drawer.setPadding(dp(22), dp(22), dp(22), dp(22));
    drawer.setBackgroundColor(0xED152237);
    TextView title = text("瞬頻 TV  /  應用", 23, TEXT);
    title.setPadding(0, 0, 0, dp(12));
    LinearLayout drawerHeader = row();
    drawerHeader.setGravity(Gravity.CENTER_VERTICAL);
    drawerHeader.addView(title, new LinearLayout.LayoutParams(0,-2,1));
    drawerHeader.addView(roundSearchButton(), new LinearLayout.LayoutParams(dp(40),dp(40)));
    drawer.addView(drawerHeader);
    addHomeMenuButton(drawer);
    ScrollView scroll = new ScrollView(this);
    LinearLayout list = column();
    scroll.addView(list);
    drawer.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    addDrawerButton(list, button("頻道與節目", this::showDrawer));
    addDrawerButton(list, button("來源搜尋與組合", () -> sourceManager.show()));
    addDrawerButton(
        list,
        button(
            "我的空間",
            () -> startActivity(new android.content.Intent(this, LocalUserCenterActivity.class))));
    addDrawerButton(list, button("官方網頁", this::openOfficialPage));
    addDrawerButton(list, button("設定", this::showSettings));
    addDrawerButton(list, button("離開瞬頻 TV", this::confirmExit));
    fullHost.addView(drawer, new FrameLayout.LayoutParams(dp(300), -1, Gravity.START));
    list.getChildAt(0).requestFocus();
  }

  private void openRequestedCctv() {
    java.util.List<String> ids=new java.util.ArrayList<>(), labels=new java.util.ArrayList<>();
    org.json.JSONArray rows=new PlaylistSubscriptions(this).entries();
    java.util.Set<String> seenUrls=new java.util.HashSet<>();
    for(int i=0;i<rows.length();i++) {
      org.json.JSONObject row=rows.optJSONObject(i);
      if(row==null||!row.optString("origin").contains("best-fan/iptv-sources/"))continue;
      String id=row.optString("id");Catalog.Channel channel=repo.catalog.find(id);if(channel==null)continue;
      String uri=PlaylistSubscriptions.uriOf(row);if(!seenUrls.add(uri))continue;
      boolean observed=SourceManager.hasContinuousPlaybackObservation(this,uri);
      boolean developmentObserved=SourceManager.hasDevelopmentPlaybackObservation(uri);
      int insertion=(observed||developmentObserved)?0:ids.size();ids.add(insertion,id);
      labels.add(insertion,(observed?"本機曾連播60秒 · ":developmentObserved?"開發機曾連播60秒 · ":"")+"["+row.optString("group","未分組")+"] "+row.optString("name")+" · "+java.net.URI.create(uri).getHost()
        +(channel.eligible(territory,Instant.now()).isEmpty()?" · 需設定":""));
    }
    if(ids.isEmpty()){requestedCctvUpdateStarted=false;updateRequestedCctvOnLaunch(true);return;}
    AlertDialog playlistDialog=new AlertDialog.Builder(this).setTitle("直播片單 · 選台（來源可能失效）")
      .setItems(labels.toArray(new String[0]),(dialog,index)->{
        Catalog.Channel channel=repo.catalog.find(ids.get(index));
        if(channel.eligible(territory,Instant.now()).isEmpty())showProgramPanel(channel.id);
        else onNewIntent(new android.content.Intent(this,MainActivity.class).putExtra("command_action","play").putExtra("command_channel_id",channel.id));
      }).setNeutralButton("更新八份片單",(dialog,which)->{requestedCctvUpdateStarted=false;updateRequestedCctvOnLaunch(true);})
      .setPositiveButton(prefs.getBoolean("cctv_auto_update",true)?"停用自動更新":"啟用自動更新",(dialog,which)->prefs.edit().putBoolean("cctv_auto_update",!prefs.getBoolean("cctv_auto_update",true)).apply())
      .setNegativeButton("整理／刪改",(dialog,which)->openChannelLibrary(null)).create();
    playlistDialog.show();playlistDialog.getListView().requestFocus();playlistDialog.getListView().setSelection(0);
  }

  private void showProgramPanel(String id) {
    selectionGeneration++;
    Catalog.Channel c = repo.playbackChannel(repo.catalog.find(id));
    if (c == null) {
      showService("節目與回看", "目前沒有選中頻道。請先選擇可用來源。");
      return;
    }
    if (!fullScreen) enterFullScreen();
    closeDrawer();
    drawerOpen = true;
    if (fullOverlay != null) fullOverlay.setVisibility(View.GONE);
    menuLevel = 2;
    drawerSelectionId = id;
    drawer = column();
    drawer.setContentDescription("頻道詳情");
    drawer.setPadding(dp(24), dp(22), dp(24), dp(22));
    drawer.setBackgroundColor(0xEE101D30);
    TextView title = text(c.name, 24, TEXT);
    title.setTypeface(null, Typeface.BOLD);
    title.setMaxLines(2);
    title.setEllipsize(android.text.TextUtils.TruncateAt.END);
    LinearLayout drawerHeader = row();
    drawerHeader.setGravity(Gravity.CENTER_VERTICAL);
    drawerHeader.addView(title, new LinearLayout.LayoutParams(0,-2,1));
    drawerHeader.addView(roundSearchButton(), new LinearLayout.LayoutParams(dp(40),dp(40)));
    drawer.addView(drawerHeader);
    addHomeMenuButton(drawer);
    String officialUrl = repo.officialPage(c.id);
    boolean officialSupported = officialUrl != null && OfficialWebActivity.isSupportedOfficialPage(officialUrl);
    Guide.Program now = repo.guide.now(id, Instant.now());
    TextView info =
        text(
            (c.sources.isEmpty() ? (officialSupported ? "可開啟官方直播網頁 · 非內建串流" : "尚無內建播放來源") : now == null ? "未提供節目單" : now.title)
                + "\n"
                + (c.isDemo() ? "自有測試內容，不是直播" : sourceSummary(c))
                + (repo.linkedUserChannel(id) == null ? "" : "\n" + repo.bindingLabel(id) + "\n節目身份未核實"),
            14,
            MUTED);
    info.setPadding(0, dp(13), 0, dp(18));
    drawer.addView(info);
    ScrollView scroll = new ScrollView(this);
    LinearLayout list = column();
    scroll.addView(list);
    drawer.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    if (c.sources.isEmpty() && officialSupported)
      addDrawerButton(list, button("觀看官方直播", () -> openOfficialPage(officialUrl)));
    String guideKey = com.harbour.core.OfficialPageAdapter.cctvKey(officialUrl);
    if (guideKey != null)
      addDrawerButton(list, button("官方節目單／回看", () -> startActivity(
          new android.content.Intent(this, CctvGuideActivity.class).putExtra("channel_key", guideKey))));
    if (repo.isPreset(id))
      addDrawerButton(list, button(repo.linkedUserChannel(id) == null ? "連結已匯入來源" : "更換／解除來源連結",
          () -> openSourceBinding(id)));
    String httpChannelId = repo.isPreset(id) ? repo.linkedUserChannel(id) : id;
    Catalog.Channel httpChannel = httpChannelId == null ? null : repo.catalog.find(httpChannelId);
    if (httpChannel != null && httpChannel.sources.stream().anyMatch(source -> source.uri.startsWith("http://")))
      addDrawerButton(list, button("HTTP 來源設定（啟用／停用）", () -> sourceManager.configureHttp(httpChannelId)));
    if (BuildConfig.PERSONAL_BUILD && !c.eligible(territory, Instant.now()).isEmpty())
      addDrawerButton(list, button("使用 VLC 播放（測試）", () -> {
        String problem = VlcCompanion.open(this, c, territory);
        if (problem != null) new AlertDialog.Builder(this).setTitle("VLC 播放").setMessage(problem).setPositiveButton("知道了", null).show();
      }));
    if (!c.eligible(territory, Instant.now()).isEmpty()) {
      addDrawerButton(
          list,
          button(
              "播放此頻道",
              () -> {
                selectedId = c.id;
                filter = "all";
                query = "";
                renderChannels();
                playSelected(true);
              }));
    } else {
      TextView unavailable =
          text(
              c.sources.isEmpty() ? (officialSupported ? "官方網頁保留原站播放、地區與裝置條件；尚未驗證此裝置播放成功。亦可手動連結自備來源。" : "沒有播放來源。可手動連結已匯入來源；連結不代表官方授權或頻道身份核實。") : "目前來源不可用 · 請檢查地區、期限與 HTTP 同意條件",
              14,
              MUTED);
      unavailable.setPadding(0, dp(10), 0, dp(10));
      list.addView(unavailable);
      if (officialUrl != null && !officialSupported)
        addDrawerButton(
            list,
            button(
                "官方服務與支援",
                () -> {
                  String url = officialUrl;
                  try {
                    startActivity(
                        new android.content.Intent(
                            android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)));
                  } catch (android.content.ActivityNotFoundException e) {
                    new AlertDialog.Builder(this)
                        .setTitle("官方服務入口")
                        .setMessage("此電視沒有可用瀏覽器。請使用官方智能電視 App，或在其他裝置開啟：\n" + url)
                        .setPositiveButton("知道了", null)
                        .show();
                  }
                }));
    }
    addDrawerButton(
        list,
        button(
            favorites.contains(id) ? "★ 移除收藏" : "☆ 加入收藏",
            () -> {
              selectedId = id;
              toggleFavorite();
              showProgramPanel(id);
            }));
    final String replayId=id.startsWith("user-")?id:repo.linkedUserChannel(id);
    if(replayId!=null) addDrawerButton(list,button("節目回放",()->startActivity(ReplayActivity.launchIntent(this,replayId))));
    TextView replayState = text(SourceManager.replayDisclosure(this,
        id.startsWith("user-") ? id : repo.linkedUserChannel(id)), 13, MUTED);
    replayState.setPadding(0, dp(12), 0, dp(12));
    list.addView(replayState);
    addDrawerButton(
        list,
        button(
            "來源詳情",
            () ->
                new AlertDialog.Builder(this)
                    .setTitle("來源詳情")
                    .setMessage(c.description + "\n\n" + sourceSummary(c))
                    .setPositiveButton("知道了", null)
                    .show()));
    if (playingId != null && repo.catalog.find(playingId) != null
        && !repo.catalog.find(playingId).sources.isEmpty() && playback != null && !playback.isTerminal())
      addDrawerButton(
        list,
        button(
            "暫停／繼續目前播放",
            () -> {
              if (playback != null) playback.setPaused(playback.wantsPlayback());
              closeDrawer();
            }));
    String importedId = repo.isPreset(id) ? repo.linkedUserChannel(id) : id;
    if (importedId != null && repo.catalog.find(importedId) != null)
      addDrawerButton(list, button("整理原匯入來源", () -> openChannelLibrary(importedId)));
    addDrawerButton(list, button("返回頻道列表", () -> showCategoryDrawer(drawerCategory)));
    fullHost.addView(drawer, new FrameLayout.LayoutParams(dp(420), -1, Gravity.START));
    for (int i = 0; i < list.getChildCount(); i++)
      if (list.getChildAt(i).isFocusable()) {
        list.getChildAt(i).requestFocus();
        break;
      }
  }

  private void showSearchResults() {
    List<Catalog.Channel> found = new ArrayList<>(visibleChannels);
    filter = "all";
    query = "";
    renderChannels();
    String[] names = found.stream().map(c -> c.name).toArray(String[]::new);
    if (names.length == 0) {
      new AlertDialog.Builder(this)
          .setTitle("沒有符合的頻道")
          .setMessage("搜尋只查現有目錄，不會編造節目。")
          .setPositiveButton("知道了", null)
          .show();
      return;
    }
    new AlertDialog.Builder(this)
        .setTitle("搜尋結果 · " + names.length)
        .setItems(names, (d, i) -> {selectedId=found.get(i).id;filter="all";query="";renderChannels();playSelected(true);})
        .setNegativeButton("返回", null)
        .show();
  }

  private void addDrawerButton(LinearLayout parent, Button b) {
    b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(48));
    lp.bottomMargin = dp(7);
    parent.addView(b, lp);
  }

  private void animateDrawer() {
    if (android.animation.ValueAnimator.areAnimatorsEnabled()) {
      drawer.setAlpha(0f);
      drawer.setTranslationX(-dp(16));
      drawer.animate().alpha(1f).translationX(0f).setDuration(150).start();
    }
  }

  private void closeDrawer() {
    if (drawer != null && fullHost != null) fullHost.removeView(drawer);
    drawer = null;
    drawerOpen = false;
    updateFullTitle();
    if (fullHost != null) fullHost.requestFocus();
  }

  private void showFatal() {
    TextView error = text("頻道目錄無法載入。\n請重新安裝經核實的原型版本。\n\n不會使用未核實的外部來源作替代。", 22, TEXT);
    error.setPadding(dp(40), dp(40), dp(40), dp(40));
    error.setBackgroundColor(BG);
    setContentView(error);
  }

  private LinearLayout column() {
    LinearLayout v = new LinearLayout(this);
    v.setOrientation(LinearLayout.VERTICAL);
    return v;
  }

  private LinearLayout row() {
    LinearLayout v = new LinearLayout(this);
    v.setOrientation(LinearLayout.HORIZONTAL);
    return v;
  }

  private TextView text(String s, float size, int color) {
    TextView v = new TextView(this);
    v.setText(s);
    v.setTextSize(size);
    v.setTextColor(color);
    v.setIncludeFontPadding(false);
    return v;
  }

  private Button button(String s, Runnable action) {
    Button b =
        new Button(this) {
          @Override
          protected void onFocusChanged(boolean focused, int direction, Rect oldRect) {
            super.onFocusChanged(focused, direction, oldRect);
            // Keep the focus outline inside its allocated card/preview bounds.
            setElevation(focused ? dp(6) : 0);
          }
        };
    b.setId(View.generateViewId());
    b.setText(s);
    b.setAllCaps(false);
    b.setTextSize(14);
    b.setTextColor(TEXT);
    b.setPadding(dp(15), dp(7), dp(15), dp(7));
    b.setMinWidth(0);
    b.setMinimumWidth(0);
    b.setStateListAnimator(null);
    b.setBackground(focusBackground());
    b.setFocusable(true);
    b.setOnClickListener(v -> {
      selectionGeneration++;
      action.run();
    });
    return b;
  }

  private StateListDrawable focusBackground() {
    StateListDrawable d = new StateListDrawable();
    d.addState(new int[] {android.R.attr.state_focused}, shape(0xFF214F53, MINT, 3));
    d.addState(new int[] {android.R.attr.state_pressed}, shape(0xFF28666A, MINT, 3));
    d.addState(new int[] {android.R.attr.state_selected}, shape(0xFF24404E, MINT, 1));
    d.addState(new int[] {}, shape(0xFF182F43, 0xFF284156, 1));
    return d;
  }

  private GradientDrawable shape(int color, int stroke, int width) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(dp(11));
    if (width > 0) d.setStroke(dp(width), stroke);
    return d;
  }

  private FrameLayout.LayoutParams match() {
    return new FrameLayout.LayoutParams(-1, -1);
  }

  private int dp(float v) {
    return Math.round(v * getResources().getDisplayMetrics().density);
  }

  private static final class HarbourBackdrop extends View {
    final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Path wave = new Path();

    HarbourBackdrop(android.content.Context c) {
      super(c);
    }

    @Override
    protected void onDraw(Canvas canvas) {
      super.onDraw(canvas);
      int w = getWidth(), h = getHeight();
      canvas.drawColor(0xFF071725);
      p.setColor(0xFF184452);
      p.setStrokeWidth(2);
      p.setStyle(Paint.Style.STROKE);
      for (int n = 0; n < 5; n++) {
        wave.reset();
        wave.moveTo(0, h * .45f + n * 18);
        wave.cubicTo(w * .3f, h * .1f + n * 18, w * .55f, h * .9f + n * 18, w, h * .35f + n * 18);
        canvas.drawPath(wave, p);
      }
      p.setStyle(Paint.Style.FILL);
      p.setColor(0xFF61E6CC);
      p.setTextSize(h * .12f);
      p.setTextAlign(Paint.Align.CENTER);
      canvas.drawText("SHUNPIN TV", w * .5f, h * .43f, p);
    }
  }
}
