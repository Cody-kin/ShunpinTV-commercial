package com.harbour.tv;
import android.content.Context;
import com.harbour.core.M3uPlaylist;
import java.io.*;
import java.nio.charset.StandardCharsets;
/** Personal build seed. User deletions persist; no automatic re-creation after removal. */
final class SupplementalSources {
 static synchronized void seed(Context context) throws Exception {
  if(!BuildConfig.PERSONAL_BUILD||context.getSharedPreferences("harbour",0).getBoolean("supplemental_077_seeded",false))return;
  String origin="補充直播線路 · 2026-11-11";PlaylistSubscriptions store=new PlaylistSubscriptions(context);String prior=null;
  org.json.JSONArray batches=store.batches();for(int i=0;i<batches.length();i++)if(origin.equals(batches.getJSONObject(i).optString("origin")))prior=batches.getJSONObject(i).getString("id");
  try(InputStream in=context.getAssets().open("supplemental-live.m3u")){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1)out.write(buf,0,n);
   store.replace(prior,origin,context.getSharedPreferences("harbour",0).getString("territory","HK"),"2099-01-01T00:00:00Z",M3uPlaylist.parse(out.toString(StandardCharsets.UTF_8.name())));
  }
  if(!context.getSharedPreferences("harbour",0).edit().putBoolean("supplemental_077_seeded",true).commit())throw new IOException("無法保存預置片單狀態");
 }
}
