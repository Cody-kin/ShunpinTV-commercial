package com.harbour.tv;
import android.app.Activity;
import android.content.Intent;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import com.harbour.core.Catalog;
import java.time.Instant;
/** Explicit signed companion keeps the two C++ runtimes in separate packages/processes. */
final class VlcCompanion {
 static final String PACKAGE="com.harbour.enginelab";
 static String open(Activity activity, Catalog.Channel channel, String territory) {
  Catalog.Source chosen=null;
  for(Catalog.Source source:channel.eligible(territory,Instant.now()))
   if("https".equalsIgnoreCase(java.net.URI.create(source.uri).getScheme())&&java.net.URI.create(source.uri).getUserInfo()==null){chosen=source;break;}
  if(chosen==null)return "此測試入口只支援目前符合條件的 HTTPS 來源。";
  if(activity.getPackageManager().checkSignatures(activity.getPackageName(),PACKAGE)!=PackageManager.SIGNATURE_MATCH)
   return "尚未安裝同簽章的瞬頻 VLC 測試播放器。請安裝本專案 engine-lab 開發版；原本播放功能仍可使用。";
  Intent intent=new Intent().setComponent(new ComponentName(PACKAGE,"com.harbour.tv.VlcPlaybackActivity"))
   .putExtra("uri",chosen.uri.toString()).putExtra("title",channel.name)
   .putExtra("validUntil",chosen.validUntil.toEpochMilli()).putExtra("issuedAt",System.currentTimeMillis());
  try{activity.startActivity(intent);return null;}
  catch(android.content.ActivityNotFoundException|SecurityException e){return "VLC 測試播放器無法開啟，請檢查安裝版本與簽章。";}
 }
}
