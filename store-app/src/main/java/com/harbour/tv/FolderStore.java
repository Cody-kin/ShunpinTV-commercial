package com.harbour.tv;

import android.content.Context;
import com.harbour.core.ChannelFolders;
import java.util.Collection;

final class FolderStore {
  private final android.content.SharedPreferences prefs;
  FolderStore(Context context) { prefs = context.getSharedPreferences("user_sources", 0); }
  ChannelFolders load() { return ChannelFolders.decode(prefs.getString("folders_v1", "")); }
  void save(ChannelFolders folders) {
    String encoded = folders.encode();
    // Match ChannelFolders.decode's bound before replacing the last readable snapshot.
    if (encoded.length() > 1024 * 1024)
      throw new IllegalArgumentException("文件夾資料超過 1 MiB 上限，請減少文件夾內頻道數；原資料未改動");
    ChannelFolders.decode(encoded);
    if (!prefs.edit().putString("folders_v1", encoded).commit())
      throw new IllegalStateException("無法儲存文件夾，請檢查裝置儲存空間");
  }
  void removeDeleted(Collection<String> ids) {
    ChannelFolders folders = load(); folders.removeFromAll(ids); save(folders);
  }
}
