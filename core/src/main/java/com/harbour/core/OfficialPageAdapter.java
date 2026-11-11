package com.harbour.core;

import java.net.URI;

/** Original official-page policy and read-only playback observation; no media URL extraction. */
public final class OfficialPageAdapter {
  public static final String CCTV1 = "https://tv.cctv.com/live/cctv1/index.shtml";
  public static final String YOUMAN = "https://live.jstv.com/";
  public static final String JIAJIA = "https://www.gdtv.cn/tvChannelDetail/66";
  public static final String TVB_NEWS = "https://news.tvb.com/tc/live/C";
  public static final String MANGO_GOLDEN = "https://www.mgtv.com/live-new?_source_=C";
  public static final String HAHA_CARTOON = "https://m.kankanews.com/huikan/9";
  public static final String EUROPE = "https://tv.cctv.com/live/cctveurope/index.shtml";
  private static final java.util.Set<String> CCTV_KEYS = java.util.Collections.unmodifiableSet(new java.util.LinkedHashSet<>(java.util.Arrays.asList(
      "cctv1","cctv2","cctv3","cctv4","cctv5","cctv5plus","cctv6","cctv7","cctv8","cctvjilu","cctv10","cctv11","cctv12","cctv13","cctvchild","cctv15","cctv16","cctv17","cctveurope","cctvamerica")));
  private OfficialPageAdapter() {}
  public static String liveUrl(String key) {
    if (!CCTV_KEYS.contains(key)) throw new IllegalArgumentException("Unknown CCTV channel");
    return "https://tv.cctv.com/live/" + key + (key.equals("cctveurope") ? "/index.shtml" : "/");
  }
  public static String cctvKey(String raw) {
    try {
      URI u = new URI(raw);
      if (!"https".equals(u.getScheme()) || !"tv.cctv.com".equals(u.getHost()) || u.getPort()!=-1
          || u.getRawUserInfo()!=null || u.getRawFragment()!=null) return null;
      String path=u.getRawPath();
      for(String key:CCTV_KEYS) if(path.equals("/live/"+key+"/") || path.equals("/live/"+key+"/index.shtml")) return key;
    } catch(Exception ignored) {}
    return null;
  }
  public static boolean supports(String raw) {
    if (raw == null || raw.length() > 2048) return false;
    try {
      URI u = new URI(raw);
      if (!"https".equals(u.getScheme()) || u.getPort() != -1 || u.getRawUserInfo() != null || u.getRawFragment() != null) return false;
      if (HAHA_CARTOON.equals(raw) || MANGO_GOLDEN.equals(raw) || JIAJIA.equals(raw) || TVB_NEWS.equals(raw) || YOUMAN.equals(raw)) return true;
      if ("tv.cctv.com".equals(u.getHost())) {
        if(cctvKey(raw)==null) return false;
        String q=u.getRawQuery();
        if(q==null) return true;
        java.util.regex.Matcher m=java.util.regex.Pattern.compile("stime=([0-9]{10})&etime=([0-9]{10})&type=lbacks").matcher(q);
        if(!m.matches()) return false;
        long start=Long.parseLong(m.group(1)),end=Long.parseLong(m.group(2)),now=java.time.Instant.now().getEpochSecond();
        return start<end && end-start<=86400 && end<=now+60 && start>=now-7*86400-120;
      }
      return "www.yangshipin.cn".equals(u.getHost()) &&
          ("/tv/home".equals(u.getRawPath()) || "/tv/home/".equals(u.getRawPath())) &&
          (u.getRawQuery() == null || u.getRawQuery().matches("pid=[0-9]{1,20}"));
    } catch (Exception invalid) { return false; }
  }
  /** Restores only a known saved URL; an expired CCTV replay falls back to that channel live page. */
  public static String restoreSavedUrl(String raw) {
    if (supports(raw)) return raw;
    String key=cctvKey(raw);
    if(key==null) return null;
    try {
      String q=new URI(raw).getRawQuery();
      if(q==null) return null;
      java.util.regex.Matcher m=java.util.regex.Pattern.compile("stime=([0-9]{10})&etime=([0-9]{10})&type=lbacks").matcher(q);
      if(!m.matches()) return null;
      long start=Long.parseLong(m.group(1)), end=Long.parseLong(m.group(2)), now=java.time.Instant.now().getEpochSecond();
      if(start>=end || end-start>86400 || end>now || start>=now-7*86400-120) return null;
      return liveUrl(key);
    } catch(Exception invalid) { return null; }
  }

  /** The whole official player is resized, including its advertisements, controls and restrictions. */
  public static String script(boolean allowPlay) { return script(allowPlay, false); }
  public static String script(boolean allowPlay, boolean unmute) {
    return "(function(){try{"
      + "if(location.protocol!=='https:'||location.port||location.hash)return JSON.stringify({found:false});"
      + "var keys=['cctv1','cctv2','cctv3','cctv4','cctv5','cctv5plus','cctv6','cctv7','cctv8','cctvjilu','cctv10','cctv11','cctv12','cctv13','cctvchild','cctv15','cctv16','cctv17','cctveurope','cctvamerica'];"
      + "var path=location.pathname.split('/'),cctv=path.length===4&&path[1]==='live'&&keys.indexOf(path[2])>=0&&(path[3]===''||path[3]==='index.shtml');"
      + "var safe=(location.hostname==='tv.cctv.com'&&cctv&&(!location.search||/^\\?stime=[0-9]{10}&etime=[0-9]{10}&type=lbacks$/.test(location.search)))||"
      + "(location.hostname==='www.yangshipin.cn'&&(location.pathname==='/tv/home'||location.pathname==='/tv/home/')&&(!location.search||/^\\?pid=[0-9]{1,20}$/.test(location.search)));"
      + "safe=safe||(location.hostname==='m.kankanews.com'&&location.pathname==='/huikan/9'&&!location.search);"
      + "var mango=location.hostname==='www.mgtv.com'&&location.pathname==='/live-new'&&location.search==='?_source_=C';safe=safe||mango;"
      + "safe=safe||(location.hostname==='www.gdtv.cn'&&location.pathname==='/tvChannelDetail/66'&&!location.search)||(location.hostname==='news.tvb.com'&&location.pathname==='/tc/live/C'&&!location.search);"
      + "var youman=location.hostname==='live.jstv.com'&&location.pathname==='/'&&!location.search;safe=safe||youman;"
      + "if(!safe)return JSON.stringify({found:false});"
      + "if(youman){var ts=document.querySelectorAll('.swiper-slide .title'),slide=null,target=null;"
      + "for(var y=0;y<ts.length;y++){if(ts[y].textContent.trim()==='优漫卡通'){var candidate=ts[y].closest('.swiper-slide');if(candidate){var yr=candidate.getBoundingClientRect();if(yr.width>0&&yr.height>0){slide=candidate;target=ts[y];break;}}}}"
      + "if(!slide)return JSON.stringify({found:false,selection:'missing'});"
      + "if(!window.__harbourYoumanAttempt){window.__harbourYoumanAttempt=Date.now();if(!slide.classList.contains('active'))target.click();return JSON.stringify({found:false,selection:'switching'});}"
      + "if(!slide.classList.contains('active')||Date.now()-window.__harbourYoumanAttempt<4000)return JSON.stringify({found:false,selection:'unconfirmed'});}"

      + "if(mango){var labels=document.querySelectorAll('a.channel .name[title=\"金鹰卡通\"]'),row=null;"
      + "for(var g=0;g<labels.length;g++){var item=labels[g].closest('a.channel');if(labels[g].textContent.trim()==='金鹰卡通'&&item){var rr=item.getBoundingClientRect();if(rr.width>0&&rr.height>0){row=item;break;}}}"
      + "if(!row)return JSON.stringify({found:false,selection:'missing'});"
      + "if(!window.__harbourGoldenAttempt){window.__harbourGoldenAttempt=Date.now();if(!row.classList.contains('selected'))row.click();return JSON.stringify({found:false,selection:'switching'});}"
      + "if(!row.classList.contains('selected')||Date.now()-window.__harbourGoldenAttempt<4000)return JSON.stringify({found:false,selection:'unconfirmed'});}"

      + "var vs=document.querySelectorAll('video'),v=null,area=0;"
      + "for(var i=0;i<vs.length;i++){var r=vs[i].getBoundingClientRect(),a=r.width*r.height;"
      + "if(a>area){v=vs[i];area=a;}}"
      + "if(!v)return JSON.stringify({found:false});"
      + "if(!v.__harbourObservationId)v.__harbourObservationId=String(Date.now())+'-'+Math.random();"
      + "var p=location.hostname==='tv.cctv.com'?document.getElementById('player'):null;"
      + "if(p&&p.contains(v)){p.style.position='fixed';p.style.left='0';p.style.top='0';"
      + "p.style.width='100vw';p.style.height='100vh';v.style.width='100%';v.style.height='100%';v.style.objectFit='contain';}"
      + (unmute ? "v.muted=false;if(v.volume===0)v.volume=1;" : "")
      + (allowPlay ? "if(v.paused&&!v.ended&&!v.__harbourPlayAttempted){v.__harbourPlayAttempted=true;var promise=v.play();if(promise&&promise.catch)promise.catch(function(){});}" : "")
      + "return JSON.stringify({found:true,id:v.__harbourObservationId,time:v.currentTime,ready:v.readyState,width:v.videoWidth,paused:v.paused,ended:v.ended,muted:v.muted,volume:v.volume,height:v.videoHeight,audioBytes:typeof v.webkitAudioDecodedByteCount==='number'?v.webkitAudioDecodedByteCount:null,mediaError:v.error?v.error.code:0});"
      + "}catch(e){return JSON.stringify({found:false});}})()";
  }
  /** Requires progress on the same video over separate samples, never page-finished alone. */
  public static final class Progress {
    private String identity = "";
    private double previous;
    public void reset() { identity = ""; previous = 0; }
    public boolean observe(String id, double time, int ready, int width, boolean paused, boolean ended) {
      if (id == null || id.isEmpty() || !Double.isFinite(time) || time <= 0 || ready < 3 || width <= 0 || paused || ended) {
        reset(); return false;
      }
      boolean playing = id.equals(identity) && time > previous + .01;
      identity = id; previous = time;
      return playing;
    }
  }
}
