package com.harbour.core;

public final class OfficialPageAdapterTests {
  private static int checks;
  private static void check(boolean result,String name) {checks++;if(!result)throw new AssertionError(name);}
  public static void main(String[] args) {
    check(!OfficialPageAdapter.script(true).contains("v.muted=false"), "normal autoplay preserves mute");
    check(OfficialPageAdapter.script(true, true).contains("v.muted=false"), "explicit sound action unmutes");
    if(args.length>0){System.out.print(OfficialPageAdapter.script(!args[0].equals("no-play")));return;}
    for(String url:new String[]{OfficialPageAdapter.CCTV1,OfficialPageAdapter.EUROPE,"https://www.yangshipin.cn/tv/home?pid=600001814"})check(OfficialPageAdapter.supports(url),"verified page");
    for(String url:new String[]{null,"http://tv.cctv.com/live/cctv1/index.shtml","https://tv.cctv.com.evil.test/live/cctv1/index.shtml","https://evil.test/?https://tv.cctv.com/live/cctv1/index.shtml","https://user:password@tv.cctv.com/live/cctv1/index.shtml","https://tv.cctv.com:443/live/cctv1/index.shtml","https://tv.cctv.com/live/cctv999/index.shtml","https://tv.cctv.com/live/cctv1/index.shtml?next=evil","https://tv.cctv.com/live/cctv1/index.shtml#x","https://tv.cctv.com/live/%63ctv1/index.shtml","https://www.yangshipin.cn/tv/home?pid=1&next=evil"})check(!OfficialPageAdapter.supports(url),"reject unsupported page");
    for(String key:new String[]{"cctv2","cctvjilu","cctvchild","cctvamerica"}) check(OfficialPageAdapter.supports(OfficialPageAdapter.liveUrl(key)),"official channel");
    check(OfficialPageAdapter.supports(OfficialPageAdapter.HAHA_CARTOON), "verified cartoon page");
    check(!OfficialPageAdapter.supports(OfficialPageAdapter.HAHA_CARTOON+"?next=evil"), "cartoon query not widened");
    check(OfficialPageAdapter.supports(OfficialPageAdapter.MANGO_GOLDEN), "verified mango page");
    check(!OfficialPageAdapter.supports(OfficialPageAdapter.MANGO_GOLDEN+"&channelId=287"), "do not guess old channel route");
    for(String page:new String[]{OfficialPageAdapter.YOUMAN,OfficialPageAdapter.JIAJIA,OfficialPageAdapter.TVB_NEWS}) { check(OfficialPageAdapter.supports(page), "verified official page"); check(!OfficialPageAdapter.supports(page+"?url=evil"), "no unverified provider query"); }
    long now=java.time.Instant.now().getEpochSecond();
    String base="https://tv.cctv.com/live/cctvchild/index.shtml?stime=";
    check(OfficialPageAdapter.supports(base+(now-3600)+"&etime="+(now-1800)+"&type=lbacks"),"recent official replay");
    check(!OfficialPageAdapter.supports(base+(now-8*86400)+"&etime="+(now-7*86400)+"&type=lbacks"),"expired replay");
    check(!OfficialPageAdapter.supports(base+(now+100)+"&etime="+(now+200)+"&type=lbacks"),"future replay");
    check(!OfficialPageAdapter.supports(base+(now-3600)+"&etime="+(now-1800)+"&type=lbacks&url=evil"),"no extra replay query");
    check(OfficialPageAdapter.liveUrl("cctvchild").equals(OfficialPageAdapter.restoreSavedUrl(base+(now-8*86400)+"&etime="+(now-8*86400+3600)+"&type=lbacks")),"expired saved replay restores same channel");
    check(OfficialPageAdapter.restoreSavedUrl(base+(now+100)+"&etime="+(now+200)+"&type=lbacks")==null,"future replay not converted");
    check(OfficialPageAdapter.restoreSavedUrl(base+(now-8*86400)+"&etime="+(now-8*86400+3600)+"&type=lbacks&url=evil")==null,"extra query not converted");
    check(OfficialPageAdapter.MANGO_GOLDEN.equals(OfficialPageAdapter.restoreSavedUrl(OfficialPageAdapter.MANGO_GOLDEN)),"valid saved page preserved");
    check(OfficialPageAdapter.restoreSavedUrl("https://evil.test/live/cctv1/")==null,"untrusted saved origin rejected");
    OfficialPageAdapter.Progress p=new OfficialPageAdapter.Progress();
    check(!p.observe("a",1,4,1920,false,false),"one observation insufficient");
    check(!p.observe("a",1,4,1920,false,false),"stalled time not playback");
    check(p.observe("a",2,4,1920,false,false),"same video progressed");
    check(!p.observe("b",10,4,1920,false,false),"new video requires its own progress");
    check(!p.observe("b",11,4,1920,true,false),"paused fails");
    check(!p.observe("b",12,4,1920,false,false),"paused resets previous sample");
    check(p.observe("b",13,4,1920,false,false),"resumed real progress");
    check(!p.observe("b",14,2,1920,false,false),"insufficient readiness");
    check(!p.observe("b",15,4,0,false,false),"zero video dimensions");
    check(!p.observe("b",Double.NaN,4,1920,false,false),"invalid media time");
    p.reset();check(!p.observe("b",16,4,1920,false,false),"navigation reset");
    check(!OfficialPageAdapter.script(false).contains("v.play()"),"no play call without authorization");
    check(OfficialPageAdapter.script(true).contains("v.play()"),"authorized play attempt");
    check(!OfficialPageAdapter.script(true).contains(".src"),"does not extract stream URL");
    check(!OfficialPageAdapter.script(true).contains("display='none'"),"does not hide adverts or restriction elements");
    System.out.println("Official page adapter: "+checks+" checks passed");
  }
}
