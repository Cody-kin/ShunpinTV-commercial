package com.harbour.tv;
import android.content.Context;
import android.content.SharedPreferences;
import com.harbour.core.Catalog;
import java.net.URI;
/** Explicit source consent covers its normal HTTP manifests, redirects, segments and keys. */
final class HttpPlaybackConsent implements Catalog.HttpPlaybackPolicy {
 private final SharedPreferences prefs;
 private static volatile Boolean sessionChoice;
 boolean hasGlobalChoice(){return sessionChoice!=null||prefs.contains("global_enabled");}
 boolean globalEnabled(){return BuildConfig.PERSONAL_BUILD&&(sessionChoice!=null?sessionChoice:prefs.getBoolean("global_enabled",true));}
 void setGlobalAllowed(boolean enabled,boolean remember){
  if(!BuildConfig.PERSONAL_BUILD)throw new IllegalStateException("此版本不支援全域 HTTP 播放");
  SharedPreferences.Editor edit=prefs.edit();
  if(remember)edit.putBoolean("global_enabled",enabled);else edit.remove("global_enabled");
  if(!edit.commit())throw new IllegalStateException("無法保存 HTTP 選擇");
  sessionChoice=remember?null:enabled;
 }

 HttpPlaybackConsent(Context context){prefs=context.getSharedPreferences("http_playback_consent",Context.MODE_PRIVATE);}
 static boolean isBuildAllowed(URI uri){
  if(uri==null||!"http".equalsIgnoreCase(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null
      ||uri.getFragment()!=null||uri.getPort()==0||uri.getPort()>65535)return false;
  if(BuildConfig.PERSONAL_BUILD)return true;
  if(!"test-streams.mux.dev".equalsIgnoreCase(uri.getHost())||(uri.getPort()!=-1&&uri.getPort()!=80))return false;
  for(String host:BuildConfig.HTTP_PLAYBACK_HOSTS.split(","))if("test-streams.mux.dev".equals(host.trim()))return true;
  return false;
 }
 @Override public boolean permits(String sourceId,URI root){return isBuildAllowed(root)&&(BuildConfig.PERSONAL_BUILD ? globalEnabled()&&prefs.getBoolean(key(sourceId,root),true) : prefs.getBoolean(key(sourceId,root),false));}
 boolean permitsRequest(String sourceId,URI root,URI request){
  return permits(sourceId,root)&&isBuildAllowed(request)
    &&(BuildConfig.PERSONAL_BUILD||(root.getHost().equalsIgnoreCase(request.getHost())&&effectivePort(root)==effectivePort(request)));
 }
 void setAllowed(String sourceId,URI root,boolean allowed){
  if(!isBuildAllowed(root))throw new IllegalArgumentException("此版本不支援這個 HTTP 來源");
  if(!prefs.edit().putBoolean(key(sourceId,root),allowed).commit())throw new IllegalStateException("無法保存 HTTP 選擇");
 }
 private static int effectivePort(URI uri){return uri.getPort()==-1?80:uri.getPort();}
 private static String key(String sourceId,URI root){return "dependencies-v2:"+sourceId+"@"+root.toString();}
}
