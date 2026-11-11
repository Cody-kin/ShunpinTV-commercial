package com.harbour.tv;

import android.content.Context;
import android.content.SharedPreferences;
import com.harbour.core.Catalog;
import java.net.URI;
import java.util.*;

/** Explicit user choices only. Preset names never imply identity or redistribution permission. */
final class SourceBindingStore {
  private final SharedPreferences prefs;
  SourceBindingStore(Context context) { prefs = context.getSharedPreferences("preset_source_bindings", 0); }
  String linkedChannel(String presetId) { return prefs.getString(presetId, null); }
  void link(CatalogRepository repo, String presetId, String userId) {
    Catalog.Channel user = repo.catalog.find(userId);
    if (!repo.isPreset(presetId) || user == null || !user.id.startsWith("user-") || user.sources.isEmpty())
      throw new IllegalArgumentException("頻道或自備來源已變更，請重新選擇");
    if (!prefs.edit().putString(presetId, userId).commit()) throw new IllegalStateException("連結無法保存");
  }
  void unlink(String presetId) {
    if (!prefs.edit().remove(presetId).commit()) throw new IllegalStateException("無法解除連結");
  }
  Catalog apply(Catalog base, Set<String> presets) {
    Map<String, Catalog.Channel> byId = new HashMap<>();
    for (Catalog.Channel c : base.channels) byId.put(c.id, c);
    List<Catalog.Channel> result = new ArrayList<>();
    for (Catalog.Channel c : base.channels) {
      String linked = presets.contains(c.id) ? linkedChannel(c.id) : null;
      if (linked == null) { result.add(c); continue; }
      Catalog.Channel user = byId.get(linked);
      boolean valid = user != null && user.id.startsWith("user-") && !user.sources.isEmpty();
      String note = valid ? "使用者手動連結：" + user.name + "\n主機：" + hosts(user)
          + "\n此連結不代表節目身份或授權已核實。" : "連結的自備來源已移除，請更換或解除連結。";
      // Reuse the original Source objects, including source IDs and injected HTTP consent policy.
      result.add(new Catalog.Channel(c.id, c.number, c.name, c.category, c.language,
          (valid ? "預設頻道的使用者自訂來源連結。" : c.description) + "\n\n" + note, c.accent, valid ? user.sources : Collections.emptyList()));
    }
    return new Catalog(base.provider, base.sourceReference, base.updatedAt, result);
  }
  static String hosts(Catalog.Channel channel) {
    Set<String> hosts = new LinkedHashSet<>();
    for (Catalog.Source s : channel.sources) {
      try { String host = URI.create(s.uri).getHost(); if (host != null) hosts.add(host); }
      catch (IllegalArgumentException ignored) { }
    }
    return hosts.isEmpty() ? "無有效主機" : String.join("、", hosts);
  }
}
