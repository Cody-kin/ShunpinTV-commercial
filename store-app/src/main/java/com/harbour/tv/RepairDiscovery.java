package com.harbour.tv;
import android.content.Context;
import com.harbour.core.*;
import java.util.*;
import org.json.*;
/** Public catalogues are candidate discovery only; local decoder checks determine usability. */
final class RepairDiscovery {
 static final String FEED="https://raw.githubusercontent.com/DataShare-duo/MovieLiveUrl/main/movie_live.txt";
 static final String[] FEEDS={FEED,
  "https://raw.githubusercontent.com/CCSH/IPTV/main/live_lite.txt",
  "https://raw.githubusercontent.com/zilong7728/Collect-IPTV/main/best_sorted.m3u8",
  "https://iptv-org.github.io/iptv/countries/cn.m3u",
  "https://iptv-org.github.io/iptv/countries/hk.m3u"};
 interface Fetcher { String fetch(String url) throws Exception; }
 static int refresh(Context context,Set<String> requestedKeys) throws Exception {
  return refresh(context,requestedKeys,url->HttpsPlaylistFetcher.fetch(url).text);
 }
 static int refresh(Context context,Set<String> requestedKeys,Fetcher fetcher) throws Exception {
  if(!BuildConfig.PERSONAL_BUILD||requestedKeys.isEmpty())return 0;
  PlaylistSubscriptions store=new PlaylistSubscriptions(context);int total=0, downloaded=0;
  for(String feed:FEEDS){
   if(Thread.currentThread().isInterrupted())throw new InterruptedException();
   try{
    String text=fetcher.fetch(feed);
    if(text.trim().startsWith("<")||text.trim().isEmpty())throw new java.io.IOException("Invalid catalogue response");
    List<M3uPlaylist.Entry> incoming=select(text,feed,requestedKeys);downloaded++;
    if(incoming.isEmpty())continue;
    String origin="修復搜尋候選 · "+feed,batch=null;JSONArray bs=store.batches();
    for(int i=0;i<bs.length();i++)if(origin.equals(bs.getJSONObject(i).optString("origin")))batch=bs.getJSONObject(i).getString("id");
    // Each catalogue owns its batch; one failed download never erases another catalogue.
    // Keep stations outside this repair request when refreshing a shared discovery batch.
    if(batch!=null){
     JSONArray saved=store.entries();
     for(int i=0;i<saved.length();i++){JSONObject row=saved.getJSONObject(i);if(batch.equals(row.optString("batchId")))for(M3uPlaylist.Entry old:M3uPlaylist.parse(row.getString("m3u")))if(!requestedKeys.contains(key(old.name)))incoming.add(old);}
    }
    store.replace(batch,origin,context.getSharedPreferences("harbour",0).getString("territory","HK"),"2099-01-01T00:00:00Z",incoming);
    total+=incoming.size();
   }catch(InterruptedException e){Thread.currentThread().interrupt();throw e;}
   catch(Exception e){/* Keep saved candidates and continue independent catalogues. */}
  }
  context.getSharedPreferences("repair_discovery",0).edit().putInt("downloaded",downloaded).putInt("attempted",FEEDS.length).putLong("checked_at",System.currentTimeMillis()).commit();
  return total;
 }
 static String key(String name){return CatalogRepository.stationKey(new Catalog.Channel("user-discovery","0",name,Catalog.Category.ALL,"zh","candidate","#000000",Collections.emptyList()));}
 static List<M3uPlaylist.Entry> select(String text,String feed,Set<String> requestedKeys){
  List<M3uPlaylist.Entry> parsed=new ArrayList<>();
  if(text.replace("\uFEFF","").trim().startsWith("#EXTM3U")){
   if(M3uPlaylist.isHlsManifest(text))return parsed;
   parsed.addAll(M3uPlaylist.parseFromUrl(text,feed));
  }else{
   if(text.trim().startsWith("<")||text.length()>M3uPlaylist.MAX_CHARS)return parsed;
   for(String line:text.split("\\r?\\n")){int comma=line.indexOf(',');if(comma<1)continue;
    String name=line.substring(0,comma).trim(),url=line.substring(comma+1).trim();
    try{if(url.startsWith("https://")||url.startsWith("http://"))parsed.add(M3uPlaylist.fromStreamUrl(name,url));}catch(IllegalArgumentException ignored){}
   }
  }
  List<M3uPlaylist.Entry> selected=new ArrayList<>();Set<String> seen=new HashSet<>();Map<String,Integer> counts=new HashMap<>();
  for(M3uPlaylist.Entry e:parsed){String key=key(e.name);if(e.uri.equals("https://vdm6-hw.nbgdsygs.com/live/h6-2.flv")||key.equals("station-name-翡翠台")||!requestedKeys.contains(key)||counts.getOrDefault(key,0)>=8)continue;
   try{java.net.URI uri=java.net.URI.create(e.uri);if(uri.getHost()==null||uri.getUserInfo()!=null||uri.getFragment()!=null||!("http".equalsIgnoreCase(uri.getScheme())||"https".equalsIgnoreCase(uri.getScheme()))||!seen.add(e.uri))continue;
    selected.add(e);counts.put(key,counts.getOrDefault(key,0)+1);
   }catch(IllegalArgumentException ignored){}
  }
  return selected;
 }
}
