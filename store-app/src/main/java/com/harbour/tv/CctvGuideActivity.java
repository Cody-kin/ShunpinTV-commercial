package com.harbour.tv;

import android.app.Activity;
import android.content.*;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import com.harbour.core.CctvGuide;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

/** Native schedule navigation into the official website's own replay player. */
public final class CctvGuideActivity extends Activity {
  public static final String EXTRA_CHANNEL_KEY="channel_key";
  private final ExecutorService executor=Executors.newSingleThreadExecutor();
  private Future<?> pending;
  private CctvGuideClient.Request activeRequest;
  private String channel;
  private LocalDate date;
  private int generation;
  private TextView heading,status;
  private ListView list;
  private Button previous,next;
  private List<CctvGuide.Programme> programmes=Collections.emptyList();
  public static Intent launchIntent(Context context,String channelKey){return new Intent(context,CctvGuideActivity.class).putExtra(EXTRA_CHANNEL_KEY,channelKey);}
  private int dp(int x){return Math.round(x*getResources().getDisplayMetrics().density);}
  @Override public void onCreate(Bundle state){
    super.onCreate(state);
    if(!BuildConfig.PERSONAL_BUILD){finish();return;}
    channel=getIntent().getStringExtra(EXTRA_CHANNEL_KEY);
    if(!CctvGuide.CHANNELS.contains(channel)){Toast.makeText(this,"未知官方頻道",Toast.LENGTH_LONG).show();finish();return;}
    date=Instant.now().atZone(CctvGuide.ZONE).toLocalDate();
    if(state!=null)try{LocalDate restored=LocalDate.parse(state.getString("date",""));if(CctvGuide.allowedDate(restored,Instant.now()))date=restored;}catch(Exception ignored){}
    LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(28),dp(20),dp(28),dp(20));root.setBackgroundColor(0xFF102033);setContentView(root);
    heading=label("",22);root.addView(heading);
    root.addView(label("官方頻道回看 · 選取已播完節目後交由央視原頁播放\n節目單不是播放驗證；廣告、登入、地區與節目權利限制由原站處理。",15));
    LinearLayout actions=new LinearLayout(this);root.addView(actions);
    previous=button("前一天",()->{date=date.minusDays(1);load();});actions.addView(previous);
    next=button("後一天",()->{date=date.plusDays(1);load();});actions.addView(next);
    actions.addView(button("重新讀取",this::load));actions.addView(button("返回直播",()->open(CctvGuide.liveUrl(channel))));actions.addView(button("返回",this::finish));
    status=label("",15);root.addView(status);
    list=new ListView(this);list.setDividerHeight(dp(3));list.setBackgroundColor(0xFF14283B);root.addView(list,new LinearLayout.LayoutParams(-1,0,1));
    list.setOnItemClickListener((p,v,position,id)->{
      CctvGuide.Programme programme=programmes.get(position);Instant now=Instant.now();
      if(programme.canReplay(now))open(programme.replayUrl(channel,now));
      else if(programme.isLive(now))open(CctvGuide.liveUrl(channel));
      else Toast.makeText(this,"節目尚未開始或已超過回看窗口",Toast.LENGTH_SHORT).show();
    });
    load();
  }
  private TextView label(String text,int size){TextView view=new TextView(this);view.setText(text);view.setTextSize(size);view.setTextColor(0xFFE7F6F4);view.setPadding(dp(8),dp(8),dp(8),dp(8));return view;}
  private Button button(String text,Runnable click){
    Button button=new Button(this);button.setText(text);button.setAllCaps(false);button.setTextColor(0xFFF2FFFC);button.setMinHeight(dp(48));
    android.graphics.drawable.StateListDrawable states=new android.graphics.drawable.StateListDrawable();
    for(boolean focus:new boolean[]{true,false}){android.graphics.drawable.GradientDrawable bg=new android.graphics.drawable.GradientDrawable();bg.setColor(focus?0xFF285F59:0xFF20394B);bg.setCornerRadius(dp(6));bg.setStroke(dp(2),focus?0xFF6CE8CD:0xFF38566B);states.addState(focus?new int[]{android.R.attr.state_focused}:new int[]{},bg);}button.setBackground(states);button.setOnClickListener(v->click.run());return button;
  }
  private String name(){
    if(channel.equals("cctvjilu"))return "CCTV-9 紀錄";if(channel.equals("cctvchild"))return "CCTV-14 少兒";
    if(channel.equals("cctveurope"))return "CCTV-4 歐洲";if(channel.equals("cctvamerica"))return "CCTV-4 美洲";
    if(channel.equals("cctv5plus"))return "CCTV-5+";return channel.toUpperCase(Locale.ROOT).replace("CCTV","CCTV-");
  }
  private void load(){
    Instant now=Instant.now();if(!CctvGuide.allowedDate(date,now))date=now.atZone(CctvGuide.ZONE).toLocalDate();
    final int request=++generation;final LocalDate requestedDate=date;
    if(activeRequest!=null)activeRequest.cancel();
    if(pending!=null)pending.cancel(true);
    final CctvGuideClient.Request requestHandle=new CctvGuideClient.Request();activeRequest=requestHandle;
    heading.setText(name()+" · "+date+" · 北京時間");
    previous.setEnabled(CctvGuide.allowedDate(date.minusDays(1),now));next.setEnabled(CctvGuide.allowedDate(date.plusDays(1),now));
    status.setText("正在讀取官方單日節目單…");programmes=Collections.emptyList();list.setAdapter(null);
    pending=executor.submit(()->{
      try{
        List<CctvGuide.Programme> found=requestHandle.fetch(channel,requestedDate,Instant.now());
        runOnUiThread(()->{if(isFinishing()||isDestroyed()||request!=generation)return;programmes=found;render();});
      }catch(Exception error){runOnUiThread(()->{if(isFinishing()||isDestroyed()||request!=generation)return;status.setText("未取得官方節目單，請稍後重新讀取。沒有以固定節目或欄目影片代替回看。");});}
    });
  }
  private void render(){
    status.setText(programmes.isEmpty()?"官方單日節目單暫無資料。":"共 "+programmes.size()+" 項 · 回看開啟官方播放器，尚未驗證實際回看播放。");
    DateTimeFormatter format=DateTimeFormatter.ofPattern("HH:mm").withZone(CctvGuide.ZONE);
    List<String> lines=new ArrayList<>();Instant now=Instant.now();
    for(CctvGuide.Programme p:programmes)lines.add(format.format(Instant.ofEpochSecond(p.start))+"–"+format.format(Instant.ofEpochSecond(p.end))+"  "+p.title+"\n"+(p.canReplay(now)?"官方回看 · 未驗證播放":p.isLive(now)?"直播中 · 開啟官方直播":"未開始／已過期"));
    list.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_list_item_1,lines){
      @Override public boolean areAllItemsEnabled(){return false;}
      @Override public boolean isEnabled(int position){CctvGuide.Programme p=programmes.get(position);Instant now=Instant.now();return p.canReplay(now)||p.isLive(now);}
      @Override public View getView(int position,View recycled,ViewGroup parent){TextView v=(TextView)super.getView(position,recycled,parent);v.setTextColor(isEnabled(position)?0xFFF0F8F8:0xFF83909D);v.setTextSize(17);v.setMinHeight(dp(68));v.setPadding(dp(16),dp(10),dp(16),dp(10));return v;}
    });
  }
  private void open(String url){startActivity(OfficialWebActivity.launchIntent(this,url));}
  @Override protected void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);if(date!=null)state.putString("date",date.toString());}
  @Override protected void onDestroy(){generation++;if(activeRequest!=null)activeRequest.cancel();if(pending!=null)pending.cancel(true);executor.shutdownNow();super.onDestroy();}
}
