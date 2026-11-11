package com.harbour.tv;

import android.content.Context;
import com.harbour.core.Catalog;
import com.harbour.core.ChannelClassification;
import com.harbour.core.Guide;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.json.*;

/**
 * Replace this adapter with an authenticated, authorized provider. No internet playlists are
 * scraped.
 */
final class CatalogRepository {
  private final Context context;
  private final Map<String,List<Catalog.Source>> stationSources=new LinkedHashMap<>();
  private final Map<String,Catalog.Channel> mergedChannels=new HashMap<>();
  final Catalog catalog;
  final Guide guide;
  final Map<String, String> groups;
  final Map<String, String> originalGroups;
  private final Map<String, String> officialPages = new LinkedHashMap<>();
  private final SourceBindingStore bindings;
  private final Set<String> presetIds = new LinkedHashSet<>();

  CatalogRepository(Context context) throws Exception {
    this.context=context.getApplicationContext();
    bindings = new SourceBindingStore(context);
    byte[] bytes;
    try (InputStream in = context.getAssets().open("catalog.json");
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[8192];
      int n;
      while ((n = in.read(buffer)) != -1) {
        out.write(buffer, 0, n);
        if (out.size() > 1024 * 1024) throw new IOException("Catalog exceeds size limit");
      }
      bytes = out.toByteArray();
    }
    JSONObject data = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
    if (data.getInt("schemaVersion") != 1)
      throw new IllegalArgumentException("Unsupported catalog schema");
    List<Catalog.Channel> channels = new ArrayList<>();
    JSONArray list = data.getJSONArray("channels");
    if (list.length() == 0) throw new IllegalArgumentException("Empty catalog");
    if (list.length() > 500) throw new IllegalArgumentException("Channel limit exceeded");
    Map<String, String> presetGroups = new LinkedHashMap<>();
    for (int i = 0; i < list.length(); i++) {
      JSONObject c = list.getJSONObject(i);
      String channelId = c.getString("id");
      if (!presetIds.add(channelId)) throw new IllegalArgumentException("Duplicate preset id");
      presetGroups.put(channelId, c.optString("group", "其他"));
      String official = c.optString("officialPage", "");
      if (!official.isEmpty()) {
        java.net.URI link = java.net.URI.create(official);
        if (!"https".equalsIgnoreCase(link.getScheme()) || link.getHost() == null
            || link.getUserInfo() != null) throw new IllegalArgumentException("Invalid official page");
        officialPages.put(channelId, official);
      }
      List<Catalog.Source> sources = new ArrayList<>();
      JSONArray sourceList = c.getJSONArray("sources");
      if (sourceList.length() > 3) throw new IllegalArgumentException("Source limit exceeded");
      for (int j = 0; j < sourceList.length(); j++) {
        JSONObject s = sourceList.getJSONObject(j);
        Set<String> regions = new HashSet<>();
        JSONArray ts = s.getJSONArray("territories");
        for (int k = 0; k < ts.length(); k++) regions.add(ts.getString(k));
        sources.add(
            new Catalog.Source(
                s.getString("id"),
                s.getString("uri"),
                s.getString("mimeType"),
                Catalog.Rights.valueOf(s.getString("rights")),
                regions,
                Instant.parse(s.getString("validFrom")),
                Instant.parse(s.getString("validUntil")),
                s.getString("licenseReference")));
      }
      channels.add(
          new Catalog.Channel(
              c.getString("id"),
              c.getString("number"),
              c.getString("name"),
              Catalog.Category.valueOf(c.getString("category")),
              c.getString("language"),
              c.getString("description"),
              c.getString("accent"),
              sources));
    }
    originalGroups = Collections.unmodifiableMap(new LinkedHashMap<>(SourceManager.groupByChannel(context)));
    Catalog imported =
        SourceManager.merge(
            context,
            new Catalog(
                data.getString("provider"),
                data.getString("sourceReference"),
                Instant.parse(data.getString("updatedAt")),
                channels));
    catalog = bindings.apply(imported, presetIds);
    Map<String, String> classified = new LinkedHashMap<>(presetGroups);
    for (Catalog.Channel channel : catalog.channels) {
      if (!presetIds.contains(channel.id)) classified.put(channel.id,
          ChannelClassification.classify(channel.name, originalGroups.get(channel.id)));
    }
    groups = Collections.unmodifiableMap(classified);
    List<Guide.Program> programs = new ArrayList<>();
    JSONArray ps = data.getJSONArray("programs");
    if (ps.length() > 20000) throw new IllegalArgumentException("EPG limit exceeded");
    for (int i = 0; i < ps.length(); i++) {
      JSONObject p = ps.getJSONObject(i);
      programs.add(
          new Guide.Program(
              p.getString("id"),
              p.getString("channelId"),
              p.getString("title"),
              p.optString("description", ""),
              p.getString("sourceId"),
              Instant.parse(p.getString("start")),
              Instant.parse(p.getString("end")),
              Instant.parse(p.getString("fetchedAt"))));
    }
    guide = new Guide(programs, catalog);
    for(Catalog.Channel channel:catalog.channels)if(channel.id.startsWith("user-"))
      stationSources.computeIfAbsent(stationKey(channel),key->new ArrayList<>()).addAll(channel.sources);
  }
  // Conservative station identity: do not combine regional CCTV-4, UHD or language editions.
  static String stationKey(Catalog.Channel c) {
    String name=c.name.toUpperCase(java.util.Locale.ROOT);
    java.util.regex.Matcher m=java.util.regex.Pattern.compile("^CCTV[\\s-]*([0-9]{1,2})(?![0-9])([+]?)").matcher(name);
    boolean cctv=m.find();
    if(!cctv) {
      // Exact names plus a small set of known traditional/simplified aliases.
      String normalized=name.replace("衛視","卫视").replace("東","东").replace("廣","广").replace("蘇","苏").replace("慶","庆").replace("優漫","优漫").replace("金鷹","金鹰").replace("少兒","少儿");
      normalized=normalized.replaceAll("[\\s　]+","").replaceAll("(?:高清|标清|HD|SD)$","");
      if(normalized.equals("北京卡酷少儿")||normalized.equals("BRTVKAKUCHILDRENSCHANNEL")||normalized.equals("卡酷动画"))normalized="卡酷少儿";
      if(normalized.equals("湖南金鹰卡通")||normalized.equals("GOLDENEAGLECARTOON"))normalized="金鹰卡通";
      if(normalized.equals("YOUMANCARTOON"))normalized="优漫卡通";
      return normalized.isEmpty()?c.id:"station-name-"+normalized;
    }
    if(name.contains("4K") || name.contains("8K") || name.contains("EUROPE") || name.contains("AMERICA"))return c.id;
    int n=Integer.parseInt(m.group(1));
    if(n==4||n<1||n>17)return c.id;
    return "station-cctv-"+n+m.group(2);
  }

  Catalog.Channel playbackChannel(Catalog.Channel selected) {
    if(selected==null||!selected.id.startsWith("user-"))return selected;
    Catalog.Channel cached=mergedChannels.get(selected.id);if(cached!=null)return cached;
    String key=stationKey(selected);
    if(key.equals(selected.id))return selected;
    LinkedHashMap<String,Catalog.Source> sources=new LinkedHashMap<>();
    for(Catalog.Source source:selected.sources)sources.putIfAbsent(source.uri,source);
    for(Catalog.Source source:stationSources.getOrDefault(key,Collections.emptyList()))sources.putIfAbsent(source.uri,source);
    List<Catalog.Source> ordered=new ArrayList<>(sources.values());
    ordered.sort(Comparator.comparingInt(source->sourceRank(source)));
    Catalog.Channel combined=new Catalog.Channel(selected.id,selected.number,selected.name,selected.category,selected.language,selected.description,selected.accent,ordered);
    mergedChannels.put(selected.id,combined);return combined;
  }

  private int sourceRank(Catalog.Source source) {
    int recent=new LineHealthStore(context).recent(source.uri);if(recent<0)return 9;if(recent>0)return 0;
    if(SourceManager.hasContinuousPlaybackObservation(context,source.uri))return 0;
    if(SourceManager.hasDevelopmentPlaybackObservation(source.uri))return 1;
    return 2;
  }
  int channelRank(Catalog.Channel channel){return playbackChannel(channel).sources.stream().mapToInt(this::sourceRank).min().orElse(3);}

  boolean matchesCategory(String channelId, String category) {
    if ("卡通少兒".equals(category)) {
      Catalog.Channel c=catalog.find(channelId);
      if(c!=null && ("preset-cctv-14".equals(channelId)||"station-cctv-14".equals(stationKey(c))))return true;
    }
    return groups.containsKey(channelId)
        && ChannelClassification.matches(channelId, groups.get(channelId), category);
  }

  boolean isPreset(String id) { return presetIds.contains(id); }
  String linkedUserChannel(String presetId) { return bindings.linkedChannel(presetId); }
  String bindingLabel(String presetId) {
    String id = linkedUserChannel(presetId);
    if (id == null) return "";
    Catalog.Channel user = catalog.find(id);
    return user == null ? "連結來源已移除" : "使用者連結：" + user.name + " · " + SourceBindingStore.hosts(user);
  }

  String officialPage(String channelId) { return officialPages.get(channelId); }

  int presetCount() { return presetIds.size(); }

  /** Eligible source candidates, not proof of successful playback or verified rights. */
  int playableCount(String territory) {
    int count = 0;
    Instant now = Instant.now();
    for (Catalog.Channel channel : catalog.channels)
      if (!channel.eligible(territory, now).isEmpty()) count++;
    return count;
  }

}
