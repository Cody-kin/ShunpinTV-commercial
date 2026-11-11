package com.harbour.core;

import java.time.*;
import java.util.*;

/** Official channel schedules and replay eligibility; never a media stream source. */
public final class CctvGuide {
  public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
  public static final Set<String> CHANNELS = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
      "cctv1","cctv2","cctv3","cctv4","cctv5","cctv5plus","cctv6","cctv7","cctv8","cctvjilu",
      "cctv10","cctv11","cctv12","cctv13","cctvchild","cctv15","cctv16","cctv17","cctveurope","cctvamerica")));
  private CctvGuide() {}
  public static boolean allowedDate(LocalDate date, Instant now) {
    LocalDate today=now.atZone(ZONE).toLocalDate();
    return date != null && !date.isAfter(today) && !date.isBefore(today.minusDays(6));
  }
  public static String liveUrl(String channel) {
    if(!CHANNELS.contains(channel))throw new IllegalArgumentException("未知官方頻道");
    return OfficialPageAdapter.liveUrl(channel);
  }
  public static final class Programme {
    public final String title;
    public final long start, end;
    public Programme(String title,long start,long end) {
      if(title==null||title.trim().isEmpty()||start<=0||end<=start||end-start>86400)
        throw new IllegalArgumentException("節目資料格式錯誤");
      this.title=title.trim();this.start=start;this.end=end;
    }
    public boolean isLive(Instant now){return start<=now.getEpochSecond()&&end>now.getEpochSecond();}
    public boolean canReplay(Instant now){
      long earliest=now.atZone(ZONE).toLocalDate().minusDays(6).atStartOfDay(ZONE).toEpochSecond();
      return start>=earliest&&end<=now.getEpochSecond();
    }
    public String replayUrl(String channel,Instant now){
      if(!canReplay(now))throw new IllegalArgumentException("節目尚未播完或已超過官方回看日期窗口");
      String page=liveUrl(channel);
      // Official EPG anchors use index.shtml even where the directory's live link ends in '/'.
      if(page.endsWith("/"))page+="index.shtml";
      return page+"?stime="+start+"&etime="+end+"&type=lbacks";
    }
  }
}
