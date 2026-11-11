package com.harbour.tv;
import android.content.Context;
import com.harbour.core.*;
import java.util.*;
import org.json.*;
/** Searches an explicitly identified public catalogue; candidates must pass the visible decoder check. */
final class RepairDiscovery {
 static final String FEED="https://raw.githubusercontent.com/DataShare-duo/MovieLiveUrl/main/movie_live.txt";
 static int refresh(Context context,Set<String> requestedKeys) throws Exception {
  if(!BuildConfig.PERSONAL_BUILD)return 0;
  String text=HttpsPlaylistFetcher.fetch(FEED).text;List<M3uPlaylist.Entry> incoming=new ArrayList<>();Set<String> seen=new HashSet<>();Map<String,Integer> counts=new HashMap<>();
  for(String line:text.split("\\r?\\n")){int comma=line.indexOf(',');if(comma<1)continue;String name=line.substring(0,comma).trim(),url=line.substring(comma+1).trim();if(!(url.startsWith("https://")||url.startsWith("http://")))continue;
   String key=CatalogRepository.stationKey(new Catalog.Channel("user-discovery","0",name,Catalog.Category.ALL,"zh","candidate","#000000",Collections.emptyList()));
   if(!requestedKeys.contains(key)||counts.getOrDefault(key,0)>=8||!seen.add(url))continue;
   try{java.net.URI uri=java.net.URI.create(url);if(uri.getHost()==null||uri.getUserInfo()!=null||uri.getFragment()!=null)continue;incoming.add(M3uPlaylist.fromStreamUrl(name,url));counts.put(key,counts.getOrDefault(key,0)+1);}catch(IllegalArgumentException ignored){}
  }
  if(incoming.isEmpty())return 0;PlaylistSubscriptions store=new PlaylistSubscriptions(context);String origin="修復搜尋候選 · "+FEED,batch=null;JSONArray bs=store.batches();for(int i=0;i<bs.length();i++)if(origin.equals(bs.getJSONObject(i).optString("origin")))batch=bs.getJSONObject(i).getString("id");
  store.replace(batch,origin,context.getSharedPreferences("harbour",0).getString("territory","HK"),"2099-01-01T00:00:00Z",incoming);return incoming.size();
 }
}
