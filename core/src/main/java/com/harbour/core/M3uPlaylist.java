package com.harbour.core;

import java.net.URI;
import java.util.*;
import java.util.regex.*;

/** Bounded extended-M3U reader; preserves imported metadata verbatim for export. */
public final class M3uPlaylist {
  private M3uPlaylist() {}

  public static final int MAX_CHARS = 8 * 1024 * 1024;
  public static final int MAX_ENTRIES = 30000;
  private static final Pattern ATTRIBUTE = Pattern.compile("([\\w-]+)=\"([^\"]*)\"");

  public static final class Entry {
    public final String name, uri, metadata;
    public final Map<String, String> attributes;

    Entry(String name, String uri, String metadata, Map<String, String> attributes) {
      this.name = name;
      this.uri = uri;
      this.metadata = metadata;
      this.attributes = Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }

    public String invalidReason() {
      try {
        URI parsed = URI.create(uri);
        if (parsed.getHost() == null) return "網址格式錯誤：缺少主機";
        if (parsed.getUserInfo() != null) return "網址含帳密：請勿將帳密嵌入來源網址";
        if ("http".equalsIgnoreCase(parsed.getScheme()))
          return "HTTP 播放未啟用：可整理此條目，不代表來源已失效";
        if (!"https".equalsIgnoreCase(parsed.getScheme()))
          return "此串流協定未支援：可整理，不能播放";
        return null;
      } catch (RuntimeException e) {
        return "失效：網址格式錯誤";
      }
    }

    /** Capability disclosure only. Metadata is preserved, not proof of a working archive. */
    public String replayDisclosure() {
      String mode = attributes.getOrDefault("catchup", "").trim().toLowerCase(Locale.ROOT);
      boolean declared = !attributes.getOrDefault("catchup-source", "").trim().isEmpty()
          || (!mode.isEmpty() && !Arrays.asList("none", "off", "0").contains(mode));
      boolean epg = !attributes.getOrDefault("url-tvg", "").trim().isEmpty()
          || !attributes.getOrDefault("x-tvg-url", "").trim().isEmpty()
          || Pattern.compile("(?:url-tvg|x-tvg-url)=\"[^\"]+\"").matcher(metadata).find();
      return (declared
          ? "片單含 catch-up 聲明 · 可開啟節目回放檢查支援方式，尚未實測此來源。"
          : "回放未啟用 · 此片單未提供 catch-up 回放資訊。")
          + (epg ? "\n片單含 EPG 地址，可在節目回放中載入。"
              : "\n此片單未提供 EPG 節目表地址；頻道 ID 不能代替節目表。")
          + "\n直播暫停或時移不等於已播節目回放。";
    }

    public String mimeType() {
      String declared = attributes.getOrDefault("type", "");
      if (declared.equals("application/x-mpegURL")
          || declared.equals("application/vnd.apple.mpegurl")
          || declared.equals("application/dash+xml")
          || declared.equals("video/mp4")
          || declared.equals("video/mp2t")) return declared;
      String path;
      try {
        path = URI.create(uri).getPath().toLowerCase(Locale.ROOT);
      } catch (RuntimeException e) {
        return "application/octet-stream";
      }
      if (path.endsWith(".m3u8")) return "application/x-mpegURL";
      if (path.endsWith(".mpd")) return "application/dash+xml";
      if (path.endsWith(".mp4")) return "video/mp4";
      if (path.endsWith(".ts")) return "video/mp2t";
      return "application/octet-stream";
    }
  }

  private static void checkInput(String input) {
    if (input == null) throw new IllegalArgumentException("請提供 M3U 或 HLS 清單內容");
    if (input.length() > MAX_CHARS) throw new IllegalArgumentException("M3U 超過 8 MiB 上限");
  }

  /** HLS protocol tags distinguish a stream manifest from an IPTV channel directory. */
  public static boolean isHlsManifest(String input) {
    checkInput(input);
    for (String raw : input.replace("\uFEFF", "").split("\\r?\\n")) {
      // All EXT-X tags are reserved to HLS; do not interpret segment URIs as channels.
      if (raw.trim().toUpperCase(Locale.ROOT).startsWith("#EXT-X-")) return true;
    }
    return false;
  }

  /** Parse fetched text without performing any network access. Base URL is the final fetch URL. */
  public static List<Entry> parseFromUrl(String input, String url) {
    checkInput(input);
    rejectDocumentResponse(input);
    URI base = baseUrl(url);
    if (isHlsManifest(input))
      return Collections.singletonList(fromStreamUrl(null, base.toString()));
    List<Entry> resolved = new ArrayList<>();
    for (Entry entry : parse(input)) {
      String absolute;
      try {
        absolute = base.resolve(entry.uri).toString();
      } catch (IllegalArgumentException malformed) {
        // Keep bad entries reviewable; invalidReason() explains malformed/non-HTTPS URLs.
        absolute = entry.uri;
      }
      resolved.add(new Entry(entry.name, absolute, entry.metadata, entry.attributes));
    }
    return resolved;
  }

  /** Make one HLS stream entry, including manifest endpoints with no .m3u8 extension. */
  public static Entry fromStreamUrl(String name, String url) {
    URI stream = baseUrl(url);
    String safeName = name == null ? "" : name.replaceAll("[\\p{Cntrl}\\u2028\\u2029]", " ").trim();
    if (safeName.isEmpty()) {
      safeName = "自備 HLS 串流";
      if (stream.getHost() != null && stream.getUserInfo() == null)
        safeName += " · " + stream.getHost();
    }
    if (safeName.length() > 200) safeName = safeName.substring(0, 200);
    Map<String, String> attributes = new LinkedHashMap<>();
    attributes.put("type", "application/x-mpegURL");
    return new Entry(
        safeName,
        stream.toString(),
        "#EXTINF:-1 type=\"application/x-mpegURL\"," + safeName + "\n",
        attributes);
  }

  private static URI baseUrl(String url) {
    if (url == null || url.length() > MAX_CHARS || url.trim().isEmpty())
      throw new IllegalArgumentException("請提供原始 HLS／目錄的完整網址");
    try {
      URI parsed = URI.create(url.trim());
      if (!parsed.isAbsolute() || parsed.isOpaque() || parsed.getHost() == null)
        throw new IllegalArgumentException();
      return parsed;
    } catch (IllegalArgumentException malformed) {
      throw new IllegalArgumentException("原始 HLS／目錄網址格式錯誤", malformed);
    }
  }

  private static void rejectDocumentResponse(String input) {
    String first = input.replace("\uFEFF", "").trim();
    if (first.startsWith("<") || first.startsWith("{") || first.startsWith("["))
      throw new IllegalArgumentException("收到 HTML／XML 或 JSON 頁面，不是 M3U／HLS；請提供原始清單或串流網址");
  }

  public static List<Entry> parse(String input) {
    checkInput(input);
    rejectDocumentResponse(input);
    if (isHlsManifest(input))
      throw new IllegalArgumentException("這是 HLS 串流清單，不是頻道目錄；請提供原始 manifest 網址");
    List<Entry> entries = new ArrayList<>();
    StringBuilder metadata = new StringBuilder();
    String name = "";
    Map<String, String> defaults = new LinkedHashMap<>();
    String header = "";
    Map<String, String> attrs = new LinkedHashMap<>();
    for (String raw : input.replace("\uFEFF", "").split("\\r?\\n")) {
      String line = raw.trim();
      if (line.isEmpty()) continue;
      if (line.startsWith("#EXTM3U") || line.startsWith("#SHUNPIN-ORIGINAL-M3U-HEADER:")) {
        if (!line.equals("#EXTM3U")) {
          String original=line.startsWith("#SHUNPIN-ORIGINAL-M3U-HEADER:") ? line.substring("#SHUNPIN-ORIGINAL-M3U-HEADER:".length()) : raw;
          header="#SHUNPIN-ORIGINAL-M3U-HEADER:"+original+"\n";
          defaults.clear(); Matcher global=ATTRIBUTE.matcher(original);
          while(global.find()) defaults.put(global.group(1).toLowerCase(Locale.ROOT),global.group(2));
          attrs.clear(); attrs.putAll(defaults);
        }
        continue;
      }
      if (line.startsWith("#")) {
        metadata.append(raw).append('\n');
        if (line.startsWith("#EXTINF:")) {
          int comma = -1;
          boolean quoted = false;
          for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') quoted = !quoted;
            if (ch == ',' && !quoted) {
              comma = i;
              break;
            }
          }
          name = comma >= 0 ? line.substring(comma + 1).trim() : "";
          Matcher m = ATTRIBUTE.matcher(comma >= 0 ? line.substring(0, comma) : line);
          while (m.find()) attrs.put(m.group(1).toLowerCase(Locale.ROOT), m.group(2));
        }
        continue;
      }
      if (entries.size() >= MAX_ENTRIES) throw new IllegalArgumentException("每次最多匯入 30,000 個來源");
      if (name.isEmpty()) name = attrs.getOrDefault("tvg-name", "自訂頻道 " + (entries.size() + 1));
      if (metadata.length() == 0) metadata.append("#EXTINF:-1,").append(name).append('\n');
      entries.add(new Entry(name, line, header + metadata.toString(), attrs));
      metadata.setLength(0);
      name = "";
      attrs = new LinkedHashMap<>(defaults);
    }
    if (entries.isEmpty()) throw new IllegalArgumentException("找不到 M3U 頻道；請提供 EXTINF 及串流網址");
    return entries;
  }

  public static String export(List<Entry> entries) {
    StringBuilder out = new StringBuilder("#EXTM3U\n");
    for (Entry entry : entries) out.append(entry.metadata).append(entry.uri).append('\n');
    return out.toString();
  }
}
