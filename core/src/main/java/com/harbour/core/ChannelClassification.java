package com.harbour.core;

import java.text.Normalizer;
import java.util.Locale;

/** Navigation classification only: never rewrites names, stream URIs or source metadata. */
public final class ChannelClassification {
  private ChannelClassification() {}

  public static String normalizedName(String value) {
    String text = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
        .toUpperCase(Locale.ROOT);
    String from = "视卫台湾东广内龙辽宁贵陕门云苏兰凤闻综财际农业乡";
    String to =   "視衛臺灣東廣內龍遼寧貴陝門雲蘇蘭鳳聞綜財際農業鄉";
    for (int i = 0; i < from.length(); i++) text = text.replace(from.charAt(i), to.charAt(i));
    return text.replace("臺", "台").replaceAll("[\\s\\p{Punct}·（）【】]", "")
        .replaceAll("(超高清|高清|標清|标清|UHD|FHD|HD|SD|4K|8K)$", "");
  }

  public static String classify(String name, String originalGroup) {
    String n = normalizedName(name);
    String g = normalizedName(originalGroup);
    // Specific station identities win over broad/mislabeled playlist groups.
    if (n.startsWith("CCTV") || n.startsWith("央視") || n.startsWith("央视")
        || n.startsWith("中央電視") || n.startsWith("中央电视")) return "CCTV";
    if (n.startsWith("TVB") || n.startsWith("VIUTV") || n.startsWith("RTHK")
        || n.startsWith("HOY") || n.startsWith("港台電視") || n.startsWith("港台电视")
        || n.startsWith("翡翠台") || n.startsWith("明珠台") || n.startsWith("鳳凰")
        || n.startsWith("無綫新聞") || n.startsWith("无线新闻")) return "香港";
    if (n.contains("卡通") || n.contains("少兒") || n.contains("少儿") || n.contains("優漫") || n.contains("优漫") || n.contains("嘉佳")) return "卡通少兒";
    if (n.contains("衛視")) return "衛視";
    if (g.contains("CCTV") || g.contains("央視") || g.contains("央视")) return "CCTV";
    if (g.contains("香港") || g.equals("HK") || g.contains("HONGKONG")
        || (originalGroup != null && originalGroup.toUpperCase(Locale.ROOT)
            .matches(".*(?:^|[ ,/_-])HK(?:$|[ ,/_-]).*"))) return "香港";
    if (g.contains("衛視") || g.contains("SATELLITE")) return "衛視";
    if (g.contains("地方") || g.contains("本地") || g.contains("省內")
        || g.equals("LOCAL") || n.matches("^(北京|上海|廣東|廣州|深圳|天津|重慶).*(新聞|新闻|綜合|综合|都市|公共|少兒|少儿).*"))
      return "地方";
    return "其他";
  }

  public static boolean matches(String id, String canonicalGroup, String category) {
    if ("全部".equals(category)) return true;
    if ("自備".equals(category)) return id != null && id.startsWith("user-");
    if ("央視".equals(category) || "央视".equals(category)) category = "CCTV";
    return category != null && category.equals(canonicalGroup);
  }
}
