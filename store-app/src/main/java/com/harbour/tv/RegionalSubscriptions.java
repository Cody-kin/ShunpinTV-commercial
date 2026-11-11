package com.harbour.tv;
import android.content.Context;
import com.harbour.core.*;
import java.util.*;
import org.json.*;
/** Personal user-requested regional subscription. Empty/failed updates retain the last batch. */
final class RegionalSubscriptions {
 static final String HK="https://iptv-org.github.io/iptv/countries/hk.m3u";
 static void refresh(Context c,boolean manual) throws Exception {
  if(!BuildConfig.PERSONAL_BUILD)return;
  PlaylistSubscriptions store=new PlaylistSubscriptions(c);JSONObject prior=null;JSONArray batches=store.batches();
  for(int i=0;i<batches.length();i++){JSONObject b=batches.getJSONObject(i);if(HK.equals(b.optString("url")))prior=b;}
  android.content.SharedPreferences prefs=c.getSharedPreferences("regional_updates",0);
  if(prior==null&&prefs.getBoolean("hk_added",false)&&!manual)return;
  try{
   HttpsPlaylistFetcher.Result result=HttpsPlaylistFetcher.fetch(HK);
   if(!result.text.contains("#EXTM3U")||M3uPlaylist.isHlsManifest(result.text))throw new IllegalArgumentException("Not a channel list");
   String grouped=result.text.replaceAll("group-title=\"[^\"]*\"","group-title=\"香港\"");
   List<M3uPlaylist.Entry> entries=M3uPlaylist.parseFromUrl(grouped,result.finalUrl);
   if(entries.isEmpty())throw new IllegalArgumentException("Empty list");
   store.replace(prior==null?null:prior.getString("id"),HK,prior==null?c.getSharedPreferences("harbour",0).getString("territory","HK"):prior.getString("region"),prior==null?"2099-01-01T00:00:00Z":prior.getString("until"),entries);
   prefs.edit().putBoolean("hk_added",true).putLong("success_at",System.currentTimeMillis()).putBoolean("last_ok",true).apply();
  }catch(Exception e){prefs.edit().putBoolean("last_ok",false).putLong("attempt_at",System.currentTimeMillis()).apply();throw e;}
 }
}
