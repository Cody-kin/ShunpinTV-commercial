package com.harbour.tv;
import android.app.Activity;
import android.content.*;
import android.os.*;
import android.widget.*;
import android.view.*;
import androidx.media3.common.*;
import androidx.media3.datasource.*;
import androidx.media3.exoplayer.*;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;
import com.harbour.core.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.*;
/** User-started bounded, visible decoder checks; searches configured subscriptions, never invented URLs. */
@androidx.media3.common.util.UnstableApi
public final class LineRepairActivity extends Activity {
 private final ExecutorService worker=Executors.newSingleThreadExecutor();
 private final Handler ui=new Handler(Looper.getMainLooper());
 private Future<?> task;private volatile boolean cancelled;private TextView status,results;private PlayerView preview;private ExoPlayer player;private Button check,repair;private LineHealthStore health;
 @Override public void onCreate(Bundle state){super.onCreate(state);health=new LineHealthStore(this);
  LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(30,18,30,18);root.setBackgroundColor(0xFF102033);setContentView(root);
  root.addView(label("線路自檢／修復",23));root.addView(label("每台測試備選畫面連播 3 秒（不測聲音）。修復會更新訂閱、尋找同名線路再實播；失效地址保留供整理。",15));
  LinearLayout actions=new LinearLayout(this);root.addView(actions);check=button(actions,"檢查全部頻道",()->start(false));repair=button(actions,"修復故障頻道",()->start(true));button(actions,"停止",this::cancel);button(actions,"返回",this::finish);
  status=label("尚未開始。上次結果如下：",16);root.addView(status);preview=new PlayerView(this);preview.setUseController(false);root.addView(preview,new LinearLayout.LayoutParams(-1,220));
  ScrollView scroll=new ScrollView(this);results=label("",15);scroll.addView(results);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));try{JSONArray prior=new JSONArray(health.report());StringBuilder text=new StringBuilder();for(int i=0;i<prior.length();i++){JSONObject row=prior.getJSONObject(i);text.append(row.optString("name")).append(" · ").append(row.optString("result")).append('\n');}results.setText(text);}catch(Exception ignored){}
  if(state==null&&getIntent().getBooleanExtra("refresh_now",false))start(true);
 }
 private TextView label(String text,int size){TextView v=new TextView(this);v.setText(text);v.setTextColor(0xFFE8F5F2);v.setTextSize(size);v.setPadding(8,6,8,6);return v;}
 private Button button(LinearLayout row,String text,Runnable r){Button b=new Button(this);b.setText(text);b.setOnClickListener(v->r.run());row.addView(b);return b;}
 private void message(String text){ui.post(()->{if(!isDestroyed())status.setText(text);});}
 private void start(boolean fix){if(task!=null&&!task.isDone())return;cancelled=false;check.setEnabled(false);repair.setEnabled(false);results.setText("");
  task=worker.submit(()->{JSONArray report=new JSONArray();try{
   CatalogRepository before=new CatalogRepository(this);Set<String> failedKeys=new HashSet<>();for(Catalog.Channel c:before.catalog.channels)if(health.failed(c.id))failedKeys.add(CatalogRepository.stationKey(c));
   if(fix){message("正在更新已設定的來源訂閱…");refreshSubscriptions();
    if(!cancelled&&BuildConfig.PERSONAL_BUILD){Set<String> sought=new HashSet<>(failedKeys);if(sought.isEmpty())for(Catalog.Channel c:before.catalog.channels)if(c.id.startsWith("user-"))sought.add(CatalogRepository.stationKey(c));
      message("正在公開目錄尋找同名備選，隨後逐條實播…");try{RepairDiscovery.refresh(this,sought);}catch(Exception ignored){message("搜尋目錄暫不可用，使用已有備選繼續檢查…");}}
   }
   CatalogRepository repo=new CatalogRepository(this);Set<String> seen=new HashSet<>();List<Catalog.Channel> channels=new ArrayList<>();
   for(Catalog.Channel c:repo.catalog.channels)if(c.id.startsWith("user-")&&!c.sources.isEmpty()&&seen.add(CatalogRepository.stationKey(c))&&(getIntent().getStringExtra("channel_id")==null||c.id.equals(getIntent().getStringExtra("channel_id")))&&(!fix||failedKeys.isEmpty()||failedKeys.contains(CatalogRepository.stationKey(c))))channels.add(repo.playbackChannel(c));
   int index=0;for(Catalog.Channel channel:channels){if(cancelled||Thread.currentThread().isInterrupted())break;index++;boolean ok=false;int passedLines=0;int tried=0;String reason="沒有可用線路";
    for(Catalog.Source source:channel.sources){if(cancelled||Thread.currentThread().isInterrupted())break;tried++;message(index+"／"+channels.size()+" · "+channel.name+" · 測試線路 "+tried+"／"+channel.sources.size());
     String blocked=source.blockedReason(getSharedPreferences("harbour",0).getString("territory","HK"),Instant.now());
     if(blocked!=null){reason=blocked;continue;}boolean linePassed=decode(source);if(cancelled)break;health.record(source.uri,linePassed);if(linePassed){ok=true;passedLines++;reason="已有 "+passedLines+" 條畫面連播 3 秒通過 · 聲音未測";if(passedLines>=2)break;}else if(!ok)reason="本次未能連播 3 秒（逾時／來源／格式問題）";
    }
    if(cancelled)break;for(Catalog.Channel peer:repo.catalog.channels)if(CatalogRepository.stationKey(peer).equals(CatalogRepository.stationKey(channel)))health.channelFailed(peer.id,!ok);
    JSONObject row=new JSONObject().put("name",channel.name).put("channelId",channel.id).put("tested",tried).put("lines",channel.sources.size()).put("passed",ok).put("passedLines",passedLines).put("result",reason).put("time",Instant.now().toString());report.put(row);health.report(report);String line=channel.name+" · "+reason+"（已測 "+tried+" 條）\n";ui.post(()->{if(!isDestroyed())results.append(line);});
   }
   message(cancelled?"已停止；保留已完成的檢查結果。":"完成 "+report.length()+" 台。通過是本次短時觀察，不保證長時間穩定。");
  }catch(InterruptedException e){Thread.currentThread().interrupt();message("已停止。");}catch(Exception e){message("檢查未完成；原有清單保留。可重試。");}finally{ui.post(()->{release();if(!isDestroyed()){check.setEnabled(true);repair.setEnabled(true);setResult(RESULT_OK);}});}});
 }
 private void refreshSubscriptions() throws Exception {
  if(BuildConfig.PERSONAL_BUILD&&!cancelled)try{RegionalSubscriptions.refresh(this,true);}catch(Exception ignored){message("香港訂閱更新失敗，保留原有清單…");}
  if(BuildConfig.PERSONAL_BUILD)try{RequestedCctvSubscription.refreshAll(this,url->{if(cancelled||Thread.currentThread().isInterrupted())throw new InterruptedException();return HttpsPlaylistFetcher.fetch(url).text;});}catch(Exception ignored){message("預置訂閱更新失敗，保留舊清單，繼續檢查其他訂閱…");}
  if(cancelled||Thread.currentThread().isInterrupted())throw new InterruptedException();
  PlaylistSubscriptions store=new PlaylistSubscriptions(this);JSONArray batches=store.batches();
  for(int i=0;i<batches.length();i++){if(cancelled||Thread.currentThread().isInterrupted())throw new InterruptedException();JSONObject b=batches.getJSONObject(i);String url=b.optString("url");if(!url.startsWith("https://")||url.contains("best-fan/iptv-sources")||(BuildConfig.PERSONAL_BUILD&&RegionalSubscriptions.HK.equals(url)))continue;
   try{HttpsPlaylistFetcher.Result fetched=HttpsPlaylistFetcher.fetch(SourceManager.normalizePlaylistUrl(url));store.replace(b.getString("id"),url,b.getString("region"),b.getString("until"),M3uPlaylist.parseFromUrl(fetched.text,fetched.finalUrl));}catch(Exception ignored){/* Keep this subscription unchanged. */}
  }
 }
 private boolean decode(Catalog.Source source) throws InterruptedException {
  CountDownLatch done=new CountDownLatch(1);AtomicBoolean passed=new AtomicBoolean();long deadline=SystemClock.elapsedRealtime()+15000;String territory=getSharedPreferences("harbour",0).getString("territory","HK");
  ui.post(()->{if(cancelled||isDestroyed()){done.countDown();return;}release();player=new ExoPlayer.Builder(this).setMediaSourceFactory(new DefaultMediaSourceFactory(()->new RestrictedPlaybackDataSource(this,source,territory)).setLoadErrorHandlingPolicy(new androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy(0))).build();ExoPlayer checking=player;preview.setPlayer(checking);checking.setVolume(0);
   checking.addListener(new Player.Listener(){long first=-1,start;@Override public void onPlayerError(PlaybackException e){done.countDown();}@Override public void onRenderedFirstFrame(){if(first>=0)return;first=SystemClock.elapsedRealtime();start=checking.getCurrentPosition();ui.post(new Runnable(){long stableSince=-1,stablePosition;public void run(){if(player!=checking||cancelled){done.countDown();return;}if(!checking.isPlaying()||checking.getPlaybackState()!=Player.STATE_READY){stableSince=-1;}else if(stableSince<0){stableSince=SystemClock.elapsedRealtime();stablePosition=checking.getCurrentPosition();}if(stableSince>=0&&SystemClock.elapsedRealtime()-stableSince>=3000&&checking.getCurrentPosition()-stablePosition>=2500){passed.set(true);done.countDown();return;}if(SystemClock.elapsedRealtime()<deadline)ui.postDelayed(this,250);else done.countDown();}});}});
   checking.setMediaItem(new MediaItem.Builder().setUri(source.uri).setMimeType(source.mimeType).build());checking.prepare();checking.play();
  });
  try{done.await(16,TimeUnit.SECONDS);return passed.get();}finally{ui.post(this::release);}
 }
 private void release(){if(player!=null){preview.setPlayer(null);player.release();player=null;}}
 private void cancel(){cancelled=true;if(task!=null)task.cancel(true);release();status.setText("正在停止…");}
 @Override protected void onStop(){cancel();super.onStop();}
 @Override protected void onDestroy(){cancelled=true;if(task!=null)task.cancel(true);worker.shutdownNow();release();super.onDestroy();}
}
