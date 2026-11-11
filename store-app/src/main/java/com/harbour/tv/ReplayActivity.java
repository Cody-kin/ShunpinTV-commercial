package com.harbour.tv;

import android.app.Activity;
import android.content.*;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.media3.common.*;
import androidx.media3.datasource.*;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;
import com.harbour.core.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Separate archive session; never rewrites the live channel or its last-played source. */
@androidx.media3.common.util.UnstableApi
public final class ReplayActivity extends Activity {
  private final ExecutorService worker=Executors.newSingleThreadExecutor();
  private M3uPlaylist.Entry entry; private Catalog.Source source; private String territory;
  private TextView heading,status; private ListView list; private PlayerView video; private ExoPlayer player;
  private LocalDate date=LocalDate.now(ZoneId.of("Asia/Hong_Kong"));
  private final ZoneId zone=ZoneId.of("Asia/Hong_Kong");
  private List<XmltvGuide.Programme> programmes=Collections.emptyList(),visible=Collections.emptyList();
  private int generation; private boolean stopped; private Future<?> task;
  public static Intent launchIntent(Context c,String id){return new Intent(c,ReplayActivity.class).putExtra("channel_id",id);}
  @Override public void onCreate(Bundle saved){
    super.onCreate(saved);
    LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(32,20,32,20);root.setBackgroundColor(0xFF102033);setContentView(root);
    heading=label("節目回放",22);root.addView(heading);
    LinearLayout actions=new LinearLayout(this);root.addView(actions);
    button(actions,"前一天",()->{if(date.isAfter(LocalDate.now(zone).minusDays(365))){date=date.minusDays(1);render();}});
    button(actions,"後一天",()->{if(date.isBefore(LocalDate.now(zone))){date=date.plusDays(1);render();}});
    button(actions,"更新節目表",this::load);button(actions,"返回節目表",this::stopReplay);button(actions,"返回直播",this::finish);
    status=label("",16);root.addView(status);
    list=new ListView(this);root.addView(list,new LinearLayout.LayoutParams(-1,0,1));
    video=new PlayerView(this);video.setUseController(true);video.setControllerShowTimeoutMs(0);video.setVisibility(View.GONE);root.addView(video,new LinearLayout.LayoutParams(-1,0,1));
    list.setOnItemClickListener((parent,v,pos,id)->play(visible.get(pos)));
    try{
      String id=getIntent().getStringExtra("channel_id");JSONArray rows=new PlaylistSubscriptions(this).entries();
      for(int i=0;i<rows.length();i++){JSONObject r=rows.getJSONObject(i);if(r.optString("id").equals(id)){entry=M3uPlaylist.parse(r.getString("m3u")).get(0);break;}}
      Catalog.Channel c=new CatalogRepository(this).catalog.find(id);
      if(entry==null||c==null||c.sources.isEmpty())throw new IOException("找不到此頻道來源");source=c.sources.get(0);
      territory=getSharedPreferences("harbour",0).getString("territory","HK");
      if(saved!=null)try{date=LocalDate.parse(saved.getString("date",""));}catch(Exception ignored){}
      load();
    }catch(Exception e){status.setText("無法開啟回放："+e.getMessage());}
  }
  private TextView label(String text,int size){TextView v=new TextView(this);v.setText(text);v.setTextColor(0xFFF0F8F8);v.setTextSize(size);v.setPadding(12,8,12,8);return v;}
  private void button(LinearLayout row,String text,Runnable action){Button b=new Button(this);b.setText(text);b.setAllCaps(false);b.setOnClickListener(v->action.run());row.addView(b);}
  private Catalog.Source at(String url,String mime){return new Catalog.Source(source.id,url,mime,source.rights,source.territories,source.validFrom,source.validUntil,source.licenseReference,new HttpPlaybackConsent(this));}
  private String epgUrl(){return entry.attributes.getOrDefault("url-tvg",entry.attributes.getOrDefault("x-tvg-url",""));}
  private String fetch(String url) throws Exception {
    Catalog.Source target=at(url,"application/xml");String blocked=source.blockedReason(territory,Instant.now());if(blocked!=null)throw new IOException(blocked);
    RestrictedPlaybackDataSource data=new RestrictedPlaybackDataSource(this,target,territory);
    try{data.open(new DataSpec(android.net.Uri.parse(url)));ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buf=new byte[8192];int n;
      while((n=data.read(buf,0,buf.length))!=-1){if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();bytes.write(buf,0,n);if(bytes.size()>8*1024*1024)throw new IOException("節目表超過 8 MiB");}
      return bytes.toString(StandardCharsets.UTF_8.name());
    }finally{data.close();}
  }
  private void load(){
    if(entry==null)return;stopReplay();final int request=++generation;if(task!=null)task.cancel(true);
    String epg=epgUrl();if(epg.isEmpty()){status.setText("此來源未提供 XMLTV 節目表地址，暫不能列出回放節目。");heading.setText(entry.name+" · 節目回放");return;}
    status.setText("正在讀取節目表…");
    task=worker.submit(()->{try{List<XmltvGuide.Programme> found=XmltvGuide.parse(fetch(epg),entry.attributes.get("tvg-id"));runOnUiThread(()->{if(isDestroyed()||isFinishing()||request!=generation)return;programmes=found;render();});}
      catch(Exception e){runOnUiThread(()->{if(isDestroyed()||isFinishing()||request!=generation)return;status.setText("節目表更新失敗，保留已載入資料。"+(e instanceof IllegalArgumentException?e.getMessage():"請檢查來源及網絡。"));});}});
  }
  private String reason(XmltvGuide.Programme p){try{Catchup.url(entry,p.start,p.end,Instant.now());return null;}catch(RuntimeException e){return e.getMessage();}}
  private void render(){
    if(entry==null)return;heading.setText(entry.name+" · "+date+" · 香港時間");List<XmltvGuide.Programme> found=new ArrayList<>();List<String> rows=new ArrayList<>();
    DateTimeFormatter f=DateTimeFormatter.ofPattern("HH:mm").withZone(zone);
    for(XmltvGuide.Programme p:programmes)if(Instant.ofEpochSecond(p.start).atZone(zone).toLocalDate().equals(date)){
      found.add(p);String why=reason(p);rows.add(f.format(Instant.ofEpochSecond(p.start))+"–"+f.format(Instant.ofEpochSecond(p.end))+"  "+p.title+"\n"+(why==null?"播放回放 · 來源聲明，未驗證內容":why));}
    visible=found;status.setText(found.isEmpty()?"此日期沒有對應節目，試試前一天。":"共 "+found.size()+" 項 · 回放取決於來源保存的節目");
    list.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_list_item_1,rows){
      @Override public boolean areAllItemsEnabled(){return false;}
      @Override public boolean isEnabled(int pos){return reason(visible.get(pos))==null;}
      @Override public View getView(int pos,View recycled,ViewGroup parent){TextView v=(TextView)super.getView(pos,recycled,parent);v.setTextColor(isEnabled(pos)?0xFFF0F8F8:0xFF9DA6B2);v.setTextSize(16);v.setMinHeight(95);return v;}
    });
  }
  private void play(XmltvGuide.Programme p){
    try{
      String blocked=source.blockedReason(territory,Instant.now());if(blocked!=null)throw new IllegalArgumentException(blocked);
      String url=Catchup.url(entry,p.start,p.end,Instant.now());stopReplay();Catalog.Source archive=at(url,entry.mimeType());
      player=new ExoPlayer.Builder(this).setMediaSourceFactory(new DefaultMediaSourceFactory(()->new RestrictedPlaybackDataSource(this,archive,territory))).build();
      video.setPlayer(player);list.setVisibility(View.GONE);video.setVisibility(View.VISIBLE);video.requestFocus();
      status.setText("正在載入回放 · "+p.title);
      player.addListener(new Player.Listener(){@Override public void onRenderedFirstFrame(){status.setText("回放 · "+p.title);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);}
        @Override public void onPlayerError(PlaybackException e){status.setText("回放失敗："+e.getErrorCodeName()+" · 可返回節目表重試");getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);}
        @Override public void onPlaybackStateChanged(int state){if(state==Player.STATE_ENDED){status.setText("回放結束 · "+p.title);getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);}}
      });
      MediaItem item=new MediaItem.Builder().setUri(url).setMediaId("replay:"+p.start).setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder().setEndPositionMs((p.end-p.start)*1000L).build()).build();
      player.setMediaItem(item);player.prepare();player.play();
    }catch(Exception e){status.setText("無法播放回放："+e.getMessage());}
  }
  private void stopReplay(){if(player!=null){video.setPlayer(null);player.release();player=null;}if(video!=null)video.setVisibility(View.GONE);if(list!=null)list.setVisibility(View.VISIBLE);getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);}
  @Override public void onBackPressed(){if(player!=null){stopReplay();render();}else super.onBackPressed();}
  @Override protected void onStop(){stopReplay();stopped=true;super.onStop();}
  @Override protected void onStart(){super.onStart();if(stopped){stopped=false;render();}}
  @Override protected void onSaveInstanceState(Bundle out){out.putString("date",date.toString());super.onSaveInstanceState(out);}
  @Override protected void onDestroy(){generation++;if(task!=null)task.cancel(true);worker.shutdownNow();stopReplay();super.onDestroy();}
}
