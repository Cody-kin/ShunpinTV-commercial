package com.harbour.core;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import static com.harbour.core.LocalCommandParser.Action.*;

/** Run with plain javac/java; verifies command semantics and the external URL boundary. */
public final class LocalCommandTests {
  private static int checks;
  public static void main(String[] args) {
    LocalCommandParser parser = new LocalCommandParser();
    command(parser, "請幫我播放 CCTV1", PLAY, "cctv1");
    command(parser, "请帮我播放 CCTV1", PLAY, "cctv1");
    command(parser, "收藏 CCTV1", FAVORITE, "cctv1");
    command(parser, "加入收藏 CCTV1", FAVORITE, "cctv1");
    command(parser, "查看收藏", CATEGORY, "收藏");
    command(parser, "看衛視", CATEGORY, "衛視");
    command(parser, "看卫视", CATEGORY, "衛視");
    command(parser, "打開香港", CATEGORY, "香港");
    command(parser, "找央視", CATEGORY, "CCTV");
    command(parser, "找香港 M3U", SOURCE_SEARCH, "香港");
    command(parser, "搜尋來源 香港", SOURCE_SEARCH, "香港");
    command(parser, "搜索来源 香港", SOURCE_SEARCH, "香港");
    command(parser, "匯入來源", IMPORT, "");
    command(parser, "导入 https://provider.example/list.m3u", IMPORT, "");
    command(parser, "https://provider.example/a?private=token", IMPORT, "");
    command(parser, "網上找香港 M3U", WEB_SEARCH, "香港");
    command(parser, "联网找新网址", WEB_SEARCH, "");
    command(parser, "不要播放 CCTV1", HELP, "");
    command(parser, "取消收藏 CCTV1", HELP, "");
    command(parser, "播放 CCTV1 然後收藏 CCTV2", HELP, "");
    command(parser, "intent://evil", HELP, "");
    command(parser, "javascript:alert(1)", HELP, "");
    command(parser, "file:///sdcard/list", HELP, "");
    command(parser, "播放", HELP, "");
    command(parser, "", HELP, "");
    command(parser, "a".repeat(161), HELP, "");
    Catalog catalog = new Catalog("fixture", "local", Instant.EPOCH, List.of(
        channel("built-cctv1", "1", "CCTV-1"), channel("user-cctv13", "13", "CCTV13 新聞"),
        channel("user-hk", "88", "香港測試"), channel("user-extra", "99", "綜藝")));
    Map<String, String> groups = Map.of("user-extra", "香港娛樂");
    List<Catalog.Channel> found = parser.match(catalog, parser.parse("播放 cctv1"), groups);
    check(found.size() == 2 && found.get(0).id.equals("built-cctv1"), "exact match precedes partial; ambiguous results still selectable");
    check(parser.match(catalog, parser.parse("找香港 M3U"), groups).size() == 2, "imported sources by name or group");
    check(parser.match(catalog, parser.parse("找 M3U"), groups).size() == 3, "source search excludes bundled catalog");
    check(parser.match(catalog, parser.parse("播放 新聞"), groups).size() == 1, "traditional channel search");
    check(parser.match(catalog, parser.parse("播放 新闻"), groups).size() == 1, "simplified channel search");
    check(parser.match(catalog, parser.parse("播放 unknown"), groups).isEmpty(), "no invented channel");
    check(parser.match(catalog, parser.parse("不要播放 cctv1"), groups).isEmpty(), "rejected instruction has no matches");
    check(parser.match(null, parser.parse("播放 cctv1"), groups).isEmpty(), "missing catalog is empty");
    for (OfficialDestinations.Destination destination : OfficialDestinations.Destination.values())
      check(OfficialDestinations.isAllowed(destination.url), "fixed official page allowed");
    for (String url : new String[] {"https://www.douyin.com.evil.test/", "http://www.douyin.com/", "https://www.douyin.com@evil.test/", "https://www.douyin.com/?next=evil", "https://www.douyin.com:443/", "intent://www.douyin.com", "javascript:alert(1)", "https://hongguoduanju.com/player/unknown", "https://search.cctv.com/#evil", "", null})
      check(!OfficialDestinations.isAllowed(url), "reject arbitrary destination: " + url);
    System.out.println("Local commands / official destinations: " + checks + " checks passed");
  }
  private static Catalog.Channel channel(String id, String number, String name) {
    return new Catalog.Channel(id, number, name, Catalog.Category.ALL, "zh", "fixture", "#000000", List.of());
  }
  private static void command(LocalCommandParser parser, String input, LocalCommandParser.Action action, String query) {
    LocalCommandParser.Command command = parser.parse(input);
    check(command.action == action && command.query.equals(query), input + " -> " + command.action + "/" + command.query);
  }
  private static void check(boolean condition, String message) {
    checks++; if (!condition) throw new AssertionError(message);
  }
}
