package com.harbour.tv;
import android.content.Context;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.json.*;
/** Local observations, not permanent claims about provider uptime. */
final class LineHealthStore {
 private final android.content.SharedPreferences prefs;
 LineHealthStore(Context c){prefs=c.getSharedPreferences("line_health",0);}
 private static String key(String uri){try{byte[] d=MessageDigest.getInstance("SHA-256").digest(uri.getBytes(StandardCharsets.UTF_8));StringBuilder s=new StringBuilder();for(byte b:d)s.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return s.toString();}catch(Exception e){throw new IllegalStateException(e);}}
 void record(String uri,boolean pass){prefs.edit().putLong(key(uri),(pass?1:-1)*System.currentTimeMillis()).apply();}
 int recent(String uri){long v=prefs.getLong(key(uri),0);return v==0||System.currentTimeMillis()-Math.abs(v)>30*60*1000?0:v>0?1:-1;}
 void channelFailed(String id,boolean failed){prefs.edit().putBoolean("channel:"+id,failed).apply();}
 boolean failed(String id){return prefs.getBoolean("channel:"+id,false);}
 void report(JSONArray rows){prefs.edit().putString("last_report",rows.toString()).putLong("report_time",System.currentTimeMillis()).apply();}
 String report(){return prefs.getString("last_report","[]");}
}
