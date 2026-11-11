package com.harbour.core;

import java.util.*;

public final class M3uPlaylistTests {
  private M3uPlaylistTests() {}

  private static int checks;

  public static void main(String[] args) {
    largeDirectory();
    String fixture =
        "#EXTM3U\n"
            + "#EXTINF:-1 tvg-id=\"alpha\" tvg-logo=\"https://example.org/logo.png\""
            + " group-title=\"News, HK\",Alpha, live\n"
            + "#EXTVLCOPT:http-referrer=https://example.org/\n"
            + "https://example.org/live.m3u8?token=sample\n"
            + "#EXTINF:-1,Unsupported\n"
            + "http://example.org/insecure.ts\n";
    List<M3uPlaylist.Entry> entries = M3uPlaylist.parse(fixture);
    check(entries.size() == 2, "two entries");
    check(entries.get(0).name.equals("Alpha, live"), "commas in quoted attributes and names");
    check(entries.get(0).attributes.get("group-title").equals("News, HK"), "group metadata");
    check(entries.get(0).mimeType().equals("application/x-mpegURL"), "HLS query inference");
    check(entries.get(0).invalidReason() == null, "HTTPS accepted");
    check(entries.get(1).invalidReason() != null, "HTTP marked incompatible");
    check(M3uPlaylist.export(entries).equals(fixture), "metadata survives export");
    List<M3uPlaylist.Entry> selected = Collections.singletonList(entries.get(0));
    check(
        M3uPlaylist.parse(M3uPlaylist.export(selected)).size() == 1,
        "selection exports only selected");
    rejects(() -> M3uPlaylist.parse("#EXTM3U\n"), "empty rejected");
    rejects(() -> M3uPlaylist.parse("x".repeat(M3uPlaylist.MAX_CHARS + 1)), "oversize rejected");
    rejects(
        () -> M3uPlaylist.parse(" <!doctype html>\n<html>error</html>"),
        "HTML error response rejected");
    rejects(
        () -> M3uPlaylist.parseFromUrl("{\"error\":\"denied\"}", "https://example.org/list"),
        "JSON object response rejected");
    rejects(() -> M3uPlaylist.parse("[\"https://example.org/live.m3u8\"]"), "JSON array rejected");
    check(
        M3uPlaylist.parse("https://example.org/live.m3u8\nhttps://example.org/two.m3u8").size()
            == 2,
        "plain URL text remains supported");
    ChannelClassificationTests.run();
    hlsManifests();
    relativeDirectory();
    System.out.println("M3U parser: " + checks + " checks passed");
  }

  private static void hlsManifests() {
    String master =
        "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000\nlow/index.m3u8\n"
            + "#EXT-X-STREAM-INF:BANDWIDTH=1800000\nhigh/index.m3u8\n";
    String media =
        "\uFEFF#EXTM3U\r\n#EXT-X-TARGETDURATION:2\r\n#EXT-X-MEDIA-SEQUENCE:10\r\n"
            + "#EXT-X-MAP:URI=\"init.mp4\"\r\n#EXTINF:2.0,\r\nsegment10.m4s\r\n"
            + "#EXTINF:2.0,\r\nsegment11.m4s\r\n#EXT-X-ENDLIST\r\n";
    for (String manifest : new String[] {master, media}) {
      check(M3uPlaylist.isHlsManifest(manifest), "HLS protocol recognized");
      rejects(() -> M3uPlaylist.parse(manifest), "HLS text requires original URL");
      String url = "https://example.org/watch/manifest?token=example";
      List<M3uPlaylist.Entry> streams = M3uPlaylist.parseFromUrl(manifest, url);
      check(
          streams.size() == 1 && streams.get(0).uri.equals(url),
          "one original manifest, not variants/segments");
      check(
          streams.get(0).mimeType().equals("application/x-mpegURL"),
          "HLS MIME survives extensionless URL");
      check(!streams.get(0).name.contains("token"), "default display name excludes query");
      List<M3uPlaylist.Entry> roundTrip = M3uPlaylist.parse(M3uPlaylist.export(streams));
      check(
          roundTrip.size() == 1 && roundTrip.get(0).mimeType().equals("application/x-mpegURL"),
          "HLS entry exports as channel directory");
    }
    check(
        M3uPlaylist.isHlsManifest("#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4\"\n"),
        "MAP-only fragment protected");
    check(
        M3uPlaylist.isHlsManifest("#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:1\n"),
        "sequence-only fragment protected");
    check(
        M3uPlaylist.parseFromUrl(media, "http://example.org/live").get(0).invalidReason() != null,
        "HTTP HLS marked incompatible");
    M3uPlaylist.Entry custom =
        M3uPlaylist.fromStreamUrl("My stream\n#EXTINF:-1,Injected", "https://example.org/live");
    check(
        !custom.name.contains("\n")
            && M3uPlaylist.parse(M3uPlaylist.export(List.of(custom))).size() == 1,
        "custom name cannot inject playlist lines");
    rejects(
        () -> M3uPlaylist.parseFromUrl(master, "relative/manifest"),
        "manifest base must be absolute");
  }

  private static void relativeDirectory() {
    String input =
        "#EXTM3U x-tvg-url=\"https://example.org/guide.xml\"\n"
            + "#EXTINF:-1 tvg-id=\"a\" group-title=\"News\",Alpha\n"
            + "#EXTVLCOPT:http-referrer=https://example.org/\n../stream/live.m3u8\n"
            + "#EXTINF:-1,Root\n/root/live.m3u8\n"
            + "#EXTINF:-1,Other host\n//cdn.example.org/live.m3u8\n"
            + "#EXTINF:-1,HTTP\nhttp://example.org/live.m3u8\n";
    check(!M3uPlaylist.isHlsManifest(input), "extended channel directory is not HLS");
    List<M3uPlaylist.Entry> original = M3uPlaylist.parse(input);
    List<M3uPlaylist.Entry> resolved =
        M3uPlaylist.parseFromUrl(input, "https://example.org/lists/channels.m3u?key=example");
    check(
        resolved.get(0).uri.equals("https://example.org/stream/live.m3u8"),
        "relative parent resolved");
    check(
        resolved.get(1).uri.equals("https://example.org/root/live.m3u8"), "root-relative resolved");
    check(
        resolved.get(2).uri.equals("https://cdn.example.org/live.m3u8"),
        "protocol-relative resolved");
    check(resolved.get(3).invalidReason() != null, "absolute HTTP stays marked incompatible");
    check(
        resolved.get(0).metadata.equals(original.get(0).metadata)
            && resolved.get(0).attributes.equals(original.get(0).attributes),
        "relative resolution preserves all metadata");
    List<M3uPlaylist.Entry> exported = M3uPlaylist.parse(M3uPlaylist.export(resolved));
    check(
        exported.size() == 4 && exported.get(0).uri.equals(resolved.get(0).uri),
        "resolved directory round-trip");
    check(
        M3uPlaylist.parseFromUrl(input, "http://example.org/list.m3u").get(0).invalidReason()
            != null,
        "relative HTTP base stays incompatible");
  }

  private static void check(boolean ok, String message) {
    if (!ok) throw new AssertionError(message);
    checks++;
  }

  private static void largeDirectory() {
    StringBuilder large = new StringBuilder("#EXTM3U\n");
    for (int i=0;i<12000;i++) large.append("#EXTINF:-1 group-title=\"Group ").append(i%184)
        .append("\",Channel ").append(i).append("\nhttps://example.org/").append(i).append(".m3u8\n");
    List<M3uPlaylist.Entry> entries = M3uPlaylist.parseFromUrl(large.toString(), "https://example.org/index.m3u");
    check(entries.size()==12000, "large real-world-sized directory accepted");
    check(entries.get(11999).attributes.get("group-title").equals("Group " + (11999 % 184)), "large directory final group retained");
    StringBuilder tooMany = new StringBuilder("#EXTM3U\n");
    for (int i=0;i<=M3uPlaylist.MAX_ENTRIES;i++) tooMany.append("https://example.org/").append(i).append('\n');
    rejects(() -> M3uPlaylist.parse(tooMany.toString()), "bounded large directory count");
  }

  private static void rejects(Runnable action, String message) {
    try {
      action.run();
    } catch (IllegalArgumentException expected) {
      checks++;
      return;
    }
    throw new AssertionError(message);
  }
}
