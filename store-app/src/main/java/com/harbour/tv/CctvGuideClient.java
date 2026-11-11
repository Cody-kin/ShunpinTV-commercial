package com.harbour.tv;

import com.harbour.core.CctvGuide;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Reads only official EPG metadata. Does not fetch or derive media streams. */
public final class CctvGuideClient {
  private static final int MAX_BYTES=512*1024;
  private CctvGuideClient() {}
  public static List<CctvGuide.Programme> fetch(String channel,LocalDate date,Instant now)throws Exception {
    return new Request().fetch(channel,date,now);
  }
  /** A single-use request whose cancellation disconnects a current blocking network operation. */
  public static final class Request {
    private volatile boolean cancelled;
    private HttpURLConnection active;
    private synchronized void attach(HttpURLConnection connection)throws InterruptedIOException {
      if(cancelled){connection.disconnect();throw new InterruptedIOException("讀取已取消");}
      active=connection;
    }
    public synchronized void cancel(){cancelled=true;if(active!=null)active.disconnect();}
    private void check(long deadline)throws InterruptedIOException {
      if(cancelled||Thread.currentThread().isInterrupted()||System.nanoTime()>=deadline)
        throw new InterruptedIOException("節目單讀取已取消或超過30秒");
    }
    public List<CctvGuide.Programme> fetch(String channel,LocalDate date,Instant now)throws Exception {
      validateRequest(channel,date,now);
      final long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
      check(deadline);
      URL url=new URL("https://api.cntv.cn/epg/getEpgInfoByChannelNew?c="+channel+"&serviceId=tvcctv&d="+date.format(DateTimeFormatter.BASIC_ISO_DATE)+"&t=jsonp&cb=setItem1");
      HttpURLConnection connection=(HttpURLConnection)url.openConnection();
      attach(connection);
      ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"cctv-guide-deadline");t.setDaemon(true);return t;});
      ScheduledFuture<?> expiry=timer.schedule(this::cancel,30,TimeUnit.SECONDS);
      connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(10000);connection.setReadTimeout(10000);
      try {
        int status=connection.getResponseCode();check(deadline);
        if(status!=200)throw new IOException("官方節目單回應 HTTP "+status+"；未取得資料");
        if(connection.getContentLengthLong()>MAX_BYTES)throw new IOException("節目單超過512 KiB限制");
        try(InputStream in=connection.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
          byte[] buffer=new byte[8192];int n;
          while(true){
            check(deadline);n=in.read(buffer);check(deadline);if(n==-1)break;
            if(out.size()+n>MAX_BYTES)throw new IOException("節目單超過512 KiB限制");out.write(buffer,0,n);
          }
          List<CctvGuide.Programme> parsed=parse(new String(out.toByteArray(),StandardCharsets.UTF_8),channel,date,now);
          check(deadline);return parsed;
        }
      } finally {
        expiry.cancel(false);timer.shutdownNow();connection.disconnect();
        synchronized(this){if(active==connection)active=null;}
      }
    }
  }
  private static void validateRequest(String channel,LocalDate date,Instant now) {
    if(!CctvGuide.CHANNELS.contains(channel))throw new IllegalArgumentException("未知官方頻道");
    if(!CctvGuide.allowedDate(date,now))throw new IllegalArgumentException("只讀取今天及之前六天的官方節目單");
  }
  public static List<CctvGuide.Programme> parse(String text,String channel,LocalDate date,Instant now)throws JSONException {
    validateRequest(channel,date,now);
    if(text==null||text.length()>MAX_BYTES)throw new IllegalArgumentException("節目單內容過大或空白");
    String body=text.trim();
    if(body.endsWith(";"))body=body.substring(0,body.length()-1).trim();
    if(!body.startsWith("setItem1(")||!body.endsWith(")"))throw new IllegalArgumentException("官方節目單格式不符；未執行回傳腳本");
    JSONTokener tokens = new JSONTokener(body.substring(9,body.length()-1));
    Object parsedRoot = tokens.nextValue();
    if (!(parsedRoot instanceof JSONObject) || tokens.nextClean() != 0)
      throw new IllegalArgumentException("節目單包含非 JSON 的尾隨內容");
    JSONObject root=(JSONObject)parsedRoot;
    JSONObject data=root.getJSONObject("data");JSONObject station=data.optJSONObject(channel);
    if(station==null)throw new IllegalArgumentException("節目單未包含所選頻道");
    JSONArray rows=station.optJSONArray("list");
    if(rows==null)return Collections.emptyList();
    if(rows.length()>2000)throw new IllegalArgumentException("單日節目項目超過限制");
    List<CctvGuide.Programme> result=new ArrayList<>();Set<Long> seen=new HashSet<>();
    long dayStart=date.atStartOfDay(CctvGuide.ZONE).toEpochSecond();long nextDay=date.plusDays(1).atStartOfDay(CctvGuide.ZONE).toEpochSecond();
    for(int i=0;i<rows.length();i++) {
      JSONObject row=rows.optJSONObject(i);if(row==null)continue;
      try {
        long start=row.getLong("startTime"),end=row.getLong("endTime");
        String title=row.getString("title");if(title.length()>500)title=title.substring(0,500);
        CctvGuide.Programme programme=new CctvGuide.Programme(title,start,end);
        if(start<dayStart||start>=nextDay||!seen.add(start))continue;
        result.add(programme);
      }catch(JSONException|IllegalArgumentException invalid){/* Individual malformed programme is not a playable entry. */}
    }
    result.sort(Comparator.comparingLong(p->p.start));return Collections.unmodifiableList(result);
  }
}
