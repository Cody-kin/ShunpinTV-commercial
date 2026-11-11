package com.harbour.tv;

import android.content.Context;
import android.content.SharedPreferences;
import com.harbour.core.M3uPlaylist;
import java.time.Instant;
import java.util.*;
import org.json.*;

/** One preference transaction owns the channel rows and subscription metadata. */
public final class PlaylistSubscriptions {
  private final SharedPreferences prefs;
  public PlaylistSubscriptions(Context context) { prefs = context.getSharedPreferences("user_sources", 0); }
  private JSONArray read(String key) {
    try { return new JSONArray(prefs.getString(key, "[]")); }
    catch (JSONException e) { throw new IllegalStateException("本機片單資料損壞；未改動原資料", e); }
  }
  public JSONArray entries() { synchronized (PlaylistSubscriptions.class) { return read("entries"); } }
  public JSONArray batches() { synchronized (PlaylistSubscriptions.class) { return read("batches"); } }
  public JSONObject batch(String id) {
    synchronized (PlaylistSubscriptions.class) {
      JSONArray all = read("batches");
      for (int i=0;i<all.length();i++) { JSONObject b=all.optJSONObject(i); if(b!=null && id.equals(b.optString("id"))) return b; }
      return null;
    }
  }
  public static String uriOf(JSONObject row) {
    try { return M3uPlaylist.parse(row.getString("m3u")).get(0).uri; }
    catch(Exception e) { return ""; }
  }
  public static String groupOf(JSONObject row) {
    String stored = row.optString("group", "").trim();
    if (!stored.isEmpty()) return stored;
    try {
      String group=M3uPlaylist.parse(row.getString("m3u")).get(0).attributes.getOrDefault("group-title", "").trim();
      return group.isEmpty()?"未分組":group;
    } catch(Exception e) { return "未分組"; }
  }
  private void commit(JSONArray entries, JSONArray batches) {
    if(entries.length()>M3uPlaylist.MAX_ENTRIES) throw new IllegalArgumentException("本機來源上限為 30,000 個");
    if(entries.toString().length()+batches.toString().length()>8*M3uPlaylist.MAX_CHARS)
      throw new IllegalArgumentException("本機來源資料超過容量上限");
    if(!prefs.edit().putString("entries", entries.toString()).putString("batches", batches.toString()).commit())
      throw new IllegalStateException("無法保存片單，請檢查儲存空間");
  }
  /** Add an explicitly requested local sample through the same transaction lock as imports. */
  public void addStandaloneIfAbsent(JSONObject supplied) throws JSONException {
    synchronized (PlaylistSubscriptions.class) {
      JSONArray entries = read("entries"), batches = read("batches");
      String id = supplied.getString("id");
      for (int i = 0; i < entries.length(); i++)
        if (id.equals(entries.getJSONObject(i).optString("id"))) return;
      JSONObject row = new JSONObject(supplied.toString());
      String batchId = "batch-" + UUID.randomUUID();
      row.put("batchId", batchId);
      row.put("group", groupOf(row));
      JSONObject batch = new JSONObject();
      batch.put("id", batchId); batch.put("name", row.getString("name"));
      batch.put("origin", row.optString("origin")); batch.put("url", "");
      batch.put("region", row.optString("region")); batch.put("until", row.optString("until"));
      batch.put("updatedAt", Instant.now().toString()); batch.put("excluded", new JSONArray());
      entries.put(row); batches.put(batch);
      commit(entries, batches);
    }
  }

  /** Returns removed IDs; caller can prune local folder references. */
  public Set<String> replace(String batchId, String origin, String region, String until,
      List<M3uPlaylist.Entry> incoming) throws JSONException {
    synchronized (PlaylistSubscriptions.class) {
      if(incoming.isEmpty()) throw new IllegalArgumentException("清單沒有頻道；保留原片單");
      for (M3uPlaylist.Entry entry : incoming) {
        try {
          java.net.URI uri = java.net.URI.create(entry.uri);
          if (!uri.isAbsolute() || uri.isOpaque() || uri.getHost() == null || uri.getUserInfo() != null)
            throw new IllegalArgumentException();
        } catch (RuntimeException invalid) {
          throw new IllegalArgumentException("清單包含無效頻道網址；原片單未改動");
        }
      }
      JSONArray old=read("entries"), batches=read("batches"), next=new JSONArray();
      JSONObject b=null;
      for(int i=0;i<batches.length();i++) if(batchId!=null && batchId.equals(batches.getJSONObject(i).optString("id"))) b=batches.getJSONObject(i);
      if(batchId!=null && b==null) throw new IllegalArgumentException("訂閱已移除；未匯入更新");
      if(b==null) {
        b=new JSONObject(); batchId="batch-"+UUID.randomUUID(); b.put("id",batchId);
        b.put("name",origin.startsWith("https://")?"網址訂閱 · "+java.net.URI.create(origin).getHost():origin);
        b.put("excluded",new JSONArray()); batches.put(b);
      }
      Map<String,JSONObject> previous=new LinkedHashMap<>(); Set<String> removed=new LinkedHashSet<>();
      for(int i=0;i<old.length();i++) {
        JSONObject row=old.getJSONObject(i);
        if(batchId.equals(row.optString("batchId"))) {previous.put(uriOf(row),row);removed.add(row.optString("id"));}
        else next.put(row);
      }
      Set<String> excluded=new HashSet<>(), seen=new HashSet<>(); JSONArray exclusions=b.optJSONArray("excluded");
      if(exclusions!=null) for(int i=0;i<exclusions.length();i++) excluded.add(exclusions.optString(i));
      for(M3uPlaylist.Entry e:incoming) {
        if(!seen.add(e.uri)||excluded.contains(e.uri)) continue;
        JSONObject prior=previous.get(e.uri), row=new JSONObject();
        String id=prior==null?"user-"+UUID.randomUUID():prior.getString("id"); removed.remove(id);
        row.put("id",id); row.put("batchId",batchId); row.put("name",e.name);
        row.put("m3u",M3uPlaylist.export(Collections.singletonList(e)));
        row.put("group",e.attributes.getOrDefault("group-title","").trim().isEmpty()?"未分組":e.attributes.get("group-title").trim());
        row.put("region",region);row.put("from",prior==null?Instant.now().toString():prior.optString("from",Instant.now().toString()));
        row.put("until",until);row.put("declared",true);row.put("origin",origin);next.put(row);
      }
      b.put("origin",origin);b.put("url",origin.startsWith("https://")?origin:"");
      b.put("region",region);b.put("until",until);b.put("updatedAt",Instant.now().toString());
      commit(next,batches);return removed;
    }
  }
  public Set<String> deleteIds(Set<String> ids) throws JSONException {
    synchronized (PlaylistSubscriptions.class) {
      JSONArray old=read("entries"), batches=read("batches"), next=new JSONArray();Set<String> removed=new LinkedHashSet<>();
      for(int i=0;i<old.length();i++) {
        JSONObject row=old.getJSONObject(i);
        if(!ids.contains(row.optString("id"))) {next.put(row);continue;}
        removed.add(row.optString("id"));
        for(int j=0;j<batches.length();j++) {
          JSONObject b=batches.getJSONObject(j);
          if(!b.optString("id").equals(row.optString("batchId"))) continue;
          JSONArray x=b.optJSONArray("excluded"); if(x==null) {x=new JSONArray();b.put("excluded",x);}
          String uri=uriOf(row);boolean exists=false;for(int k=0;k<x.length();k++) if(uri.equals(x.optString(k))) exists=true;
          if(!uri.isEmpty()&&!exists)x.put(uri);
        }
      }
      commit(next,batches);return removed;
    }
  }
  public Set<String> deleteBatch(String batchId) throws JSONException {
    synchronized (PlaylistSubscriptions.class) {
      JSONArray old=read("entries"), batches=read("batches"), next=new JSONArray(), keep=new JSONArray();Set<String> removed=new LinkedHashSet<>();
      for(int i=0;i<old.length();i++){JSONObject r=old.getJSONObject(i);if(batchId.equals(r.optString("batchId")))removed.add(r.optString("id"));else next.put(r);}
      for(int i=0;i<batches.length();i++){JSONObject b=batches.getJSONObject(i);if(!batchId.equals(b.optString("id")))keep.put(b);}
      commit(next,keep);return removed;
    }
  }
  public Set<String> deleteGroup(String batchId,String group) throws JSONException {
    synchronized (PlaylistSubscriptions.class) {
      Set<String> ids=new LinkedHashSet<>();JSONArray all=read("entries");
      for(int i=0;i<all.length();i++){JSONObject r=all.getJSONObject(i);if((batchId==null||batchId.equals(r.optString("batchId")))&&group.equals(groupOf(r)))ids.add(r.optString("id"));}
      return deleteIds(ids);
    }
  }
  public Set<String> restoreExclusions(String batchId) throws JSONException {
    synchronized (PlaylistSubscriptions.class) {
      JSONArray batches=read("batches");
      for(int i=0;i<batches.length();i++){JSONObject b=batches.getJSONObject(i);if(batchId.equals(b.optString("id")))b.put("excluded",new JSONArray());}
      commit(read("entries"),batches);return Collections.emptySet();
    }
  }
}
