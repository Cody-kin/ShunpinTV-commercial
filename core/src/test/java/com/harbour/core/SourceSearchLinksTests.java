package com.harbour.core;

import java.net.URI;
import java.net.URLDecoder;

/** Pure Java boundary tests; this class never opens a connection or browser. */
public final class SourceSearchLinksTests {
  private static int checks;
  public static void main(String[] args) throws Exception {
    String words = "香港 新聞 & 體育 + #?測試";
    String url = SourceSearchLinks.build(words);
    URI uri = URI.create(url);
    check("https".equals(uri.getScheme()), "HTTPS");
    check("www.bing.com".equals(uri.getHost()), "fixed Bing host");
    check("/search".equals(uri.getPath()), "fixed search path");
    check(uri.getUserInfo() == null && uri.getPort() == -1 && uri.getFragment() == null, "no injected URL fields");
    check(uri.getRawQuery().startsWith("q="), "single query parameter");
    check(!uri.getRawQuery().contains("&"), "user ampersand cannot inject parameters");
    check(URLDecoder.decode(uri.getRawQuery().substring(2), "UTF-8")
        .equals(words + " M3U 播放清單 官方"), "UTF-8 and reserved characters round trip");
    check(url.contains("%26") && url.contains("%2B") && url.contains("%23"), "reserved characters escaped");
    check(!url.contains("香港"), "Unicode keywords URL-encoded");
    check(decoded("").equals("IPTV M3U 官方"), "empty query uses fixed public keywords");
    check(decoded(null).equals("IPTV M3U 官方"), "null uses fixed public keywords");
    check(decoded("   ").equals("IPTV M3U 官方"), "blank uses fixed public keywords");
    check(decoded("台".repeat(80)).equals("台".repeat(80) + " M3U 播放清單 官方"), "80-character boundary");
    rejects("台".repeat(81));
    rejects("https://private.example/list.m3u?token=never-send-this");
    rejects("找 https://private.example/list.m3u?token=never-send-this");
    rejects("HTTPS://private.example/list.m3u?token=never-send-this");
    rejects("ｈｔｔｐｓ：／／private.example/list?token=never-send-this");
    rejects("custom://private.example/never-send-this");
    rejects("香港\n新聞");
    rejects("香港\r新聞");
    rejects("香港\t新聞");
    rejects("香港\u0000新聞");
    rejects("香港\u007f新聞");
    rejects("香港\u202e新聞");
    // URL rejection also survives the actual online-command parsing path.
    LocalCommandParser parser = new LocalCommandParser();
    LocalCommandParser.Command online = parser.parse("網上找香港 M3U");
    check(online.action == LocalCommandParser.Action.WEB_SEARCH && online.query.equals("香港"), "online command retains useful keywords");
    rejects(parser.parse("網上找 https://private.example/a?token=never-send-this").query);
    System.out.println("Source web-search links: " + checks + " checks passed (no network)");
  }
  private static String decoded(String query) throws Exception {
    return URLDecoder.decode(URI.create(SourceSearchLinks.build(query)).getRawQuery().substring(2), "UTF-8");
  }
  private static void rejects(String query) {
    try { SourceSearchLinks.build(query); throw new AssertionError("Unsafe query unexpectedly accepted"); }
    catch (IllegalArgumentException expected) {
      check(!expected.getMessage().contains("never-send-this"), "rejection does not echo private URL/token");
    }
  }
  private static void check(boolean condition, String message) {
    checks++;
    if (!condition) throw new AssertionError(message);
  }
}
