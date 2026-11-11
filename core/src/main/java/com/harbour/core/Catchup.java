package com.harbour.core;

import java.net.URI;
import java.time.Instant;
import java.util.*;

/** Provider-declared archive templates. Never guesses an archive from a live URL. */
public final class Catchup {
  private Catchup() {}
  public static String unavailable(M3uPlaylist.Entry entry) {
    Map<String,String> a=entry.attributes;
    String mode=a.getOrDefault("catchup", "").toLowerCase(Locale.ROOT);
    if(!mode.equals("default")&&!mode.equals("append"))return "此來源未提供支援的回放方式（default／append）";
    if(a.getOrDefault("catchup-source", "").trim().isEmpty())return "此來源未提供回放地址";
    try {if(days(entry)<=0)return "此來源未提供有效回放天數";}catch(RuntimeException e){return "回放天數格式不正確";}
    if(!a.getOrDefault("catchup-correction","0").matches("[+-]?0(?:\\.0+)?"))return "此來源的回放時差格式尚未支援";
    return null;
  }
  public static int days(M3uPlaylist.Entry e){int d=Integer.parseInt(e.attributes.getOrDefault("catchup-days","0"));if(d<1||d>365)throw new IllegalArgumentException("回放天數須為 1–365");return d;}
  public static String url(M3uPlaylist.Entry e,long start,long end,Instant now){
    String reason=unavailable(e);if(reason!=null)throw new IllegalArgumentException(reason);
    long n=now.getEpochSecond();
    if(start<=0||end<=start||end>n||start<n-days(e)*86400L)throw new IllegalArgumentException("節目尚未播完或已超過回放期限");
    String t=e.attributes.get("catchup-source");
    // Replace dollar forms first so ${duration} never leaves a stray dollar.
    String[][] values={{"${start}",""+start},{"${end}",""+end},{"${duration}",""+(end-start)},{"${now}",""+n},{"${timestamp}",""+n},{"{utc}",""+start},{"{utcend}",""+end},{"{duration}",""+(end-start)},{"{lutc}",""+n}};
    boolean timed=t.contains("{utc}")||t.contains("${start}");
    for(String[] v:values)t=t.replace(v[0],v[1]);
    if(!timed||t.contains("{")||t.contains("}"))throw new IllegalArgumentException("回放地址時間模板尚未支援");
    if(e.attributes.get("catchup").equalsIgnoreCase("append"))t=e.uri+t;
    URI u=URI.create(t);
    if(u.getHost()==null||u.getUserInfo()!=null||u.getFragment()!=null||!("http".equalsIgnoreCase(u.getScheme())||"https".equalsIgnoreCase(u.getScheme())))throw new IllegalArgumentException("回放地址格式不正確");
    return u.toString();
  }
}
