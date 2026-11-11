package com.harbour.tv;
import android.content.Context;
import com.harbour.core.*;
import java.time.Instant;
import java.util.List;
import org.json.*;
/** User-requested personal development preset; playlist metadata is not a content licence. */
final class RequestedCctvSubscription {
 static final String URL="https://github.com/best-fan/iptv-sources/blob/main/cn_cctv.m3u8";
 interface Fetch {String read() throws Exception;}
 static void refresh(Context context) throws Exception {
  refresh(context,()->HttpsPlaylistFetcher.fetch(SourceManager.normalizePlaylistUrl(URL)).text);
 }
 static void refresh(Context context,Fetch fetch) throws Exception {
  String text=fetch.read();
  if(!text.contains("#EXTM3U")||M3uPlaylist.isHlsManifest(text))throw new IllegalArgumentException("回應不是頻道清單");
  List<M3uPlaylist.Entry> entries=M3uPlaylist.parseFromUrl(text,SourceManager.normalizePlaylistUrl(URL));
  if(entries.isEmpty())throw new IllegalArgumentException("清單為空");
  save(context,entries,URL);
 }
 interface FeedFetch {String read(String url) throws Exception;}
 static final String ROOT="https://raw.githubusercontent.com/best-fan/iptv-sources/master/";
 static final String[] FILES={"cn_cctv.m3u8","cn_province.m3u8","cn_pay.m3u8","cn_all.m3u8","cn_cctv_status.m3u8","cn_province_status.m3u8","cn_pay_status.m3u8","cn_all_status.m3u8"};
 static void refreshAll(Context context) throws Exception {refreshAll(context,url->HttpsPlaylistFetcher.fetch(url).text);}
 static void refreshAll(Context context,FeedFetch fetch) throws Exception {
  java.util.LinkedHashMap<String,M3uPlaylist.Entry> unique=new java.util.LinkedHashMap<>();
  for(String file:FILES){String text=fetch.read(ROOT+file);
   if(!text.contains("#EXTM3U")||M3uPlaylist.isHlsManifest(text))throw new IllegalArgumentException("回應不是頻道清單");
   List<M3uPlaylist.Entry> entries=M3uPlaylist.parseFromUrl(text,ROOT+file);
   if(entries.isEmpty())throw new IllegalArgumentException("清單為空");
   for(M3uPlaylist.Entry entry:entries)unique.putIfAbsent(entry.uri,entry);
  }
  // Commit only after all eight downloads and parses succeed; failures preserve the full prior batch.
  save(context,new java.util.ArrayList<>(unique.values()),ROOT+"cn_all.m3u8");
 }
 private static void save(Context context,List<M3uPlaylist.Entry> entries,String origin) throws Exception {
  PlaylistSubscriptions store=new PlaylistSubscriptions(context);JSONArray batches=store.batches();JSONObject prior=null;
  for(int i=0;i<batches.length();i++){JSONObject b=batches.getJSONObject(i);String url=b.optString("url");if(URL.equals(url)||SourceManager.normalizePlaylistUrl(URL).equals(url)||(ROOT+"cn_all.m3u8").equals(url)){prior=b;break;}}
  store.replace(prior==null?null:prior.getString("id"),origin,
    prior==null?context.getSharedPreferences("harbour",0).getString("territory","HK"):prior.optString("region","HK"),
    prior==null?Instant.now().plusSeconds(30L*86400).toString():prior.getString("until"),entries);
 }
}
