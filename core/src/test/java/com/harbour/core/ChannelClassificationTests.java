package com.harbour.core;

import java.util.List;

public final class ChannelClassificationTests {
  private ChannelClassificationTests() {}
  public static void main(String[] args) { run(); }
  static void run() {
    String[][] cases = {
      {"ＣＣＴＶ－１ 高清", "香港", "CCTV"}, {"CCTV5+ HD", "體育", "CCTV"},
      {"央视新闻", "", "CCTV"}, {"湖南卫视高清", "地方台", "衛視"},
      {"浙江衛視 4K", "", "衛視"}, {"翡翠台 HD", "自訂來源", "香港"},
      {"TVB Plus", "綜藝", "香港"}, {"RTHK31", "其他", "香港"},
      {"ViuTVsix", "", "香港"}, {"HOY TV", "", "香港"},
      {"廣東新聞", "", "地方"}, {"測試", "地方台", "地方"},
      {"News", "News, HK", "香港"}, {"News", "香港", "香港"},
      {"未知", "央視", "CCTV"}, {"未知", "卫视", "衛視"},
      {"台視", "台灣", "其他"}, {"未知", "港澳台", "其他"},
      {"金鹰卡通", "其他", "卡通少兒"}, {"优漫卡通", "地方台", "卡通少兒"},
      {"Big Buck Bunny", "測試", "其他"}, {"", "", "其他"}
    };
    for (String[] c : cases) {
      String actual = ChannelClassification.classify(c[0], c[1]);
      if (!c[2].equals(actual)) throw new AssertionError(c[0] + ": " + actual);
    }
    String fixture = "#EXTM3U\n#EXTINF:-1 tvg-id=\"jade\" group-title=\"News, HK\",翡翠台 HD\n"
        + "#EXTVLCOPT:http-referrer=https://example.org/\nhttps://example.org/stream.m3u8?token=sample\n";
    List<M3uPlaylist.Entry> parsed = M3uPlaylist.parse(fixture);
    M3uPlaylist.Entry entry = parsed.get(0);
    String group = ChannelClassification.classify(entry.name, entry.attributes.get("group-title"));
    if (!ChannelClassification.matches("user-123", group, "香港")
        || !ChannelClassification.matches("user-123", group, "自備")
        || ChannelClassification.matches("preset-tvb-jade", group, "自備")
        || ChannelClassification.matches("user-123", group, "CCTV"))
      throw new AssertionError("User provenance and region/category must be independent");
    if (!fixture.equals(M3uPlaylist.export(parsed)) || !entry.name.equals("翡翠台 HD")
        || !entry.attributes.get("group-title").equals("News, HK"))
      throw new AssertionError("Classification must not rewrite imported metadata");
    if (!ChannelClassification.matches("preset-cctv-1", "CCTV", "央視"))
      throw new AssertionError("CCTV alias");
    System.out.println("Channel classification: 20 cases + parser round-trip/provenance passed");
  }
}
