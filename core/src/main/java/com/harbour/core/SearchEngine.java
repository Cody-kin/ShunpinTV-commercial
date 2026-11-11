package com.harbour.core;

import java.time.Instant;
import java.util.*;

/** On-device intent matching, NOT a connected LLM. All results are grounded catalog IDs. */
public final class SearchEngine {
  public enum TimeWindow {
    ANY,
    NOW,
    NEXT
  }

  public static final class Intent {
    public final String query, language;
    public final Catalog.Category category;
    public final TimeWindow time;
    public final boolean favorites;

    public Intent(
        String query,
        Catalog.Category category,
        TimeWindow time,
        String language,
        boolean favorites) {
      this.query = query == null ? "" : query.trim();
      this.category = Objects.requireNonNull(category);
      this.time = Objects.requireNonNull(time);
      this.language = language == null ? "" : language;
      this.favorites = favorites;
    }
  }

  public static final class Result {
    public final Catalog.Channel channel;
    public final Guide.Program program;
    public final String reason;

    Result(Catalog.Channel channel, Guide.Program program, String reason) {
      this.channel = channel;
      this.program = program;
      this.reason = reason;
    }
  }

  public Intent parse(String query) {
    String q = normalize(query);
    Catalog.Category category = Catalog.Category.ALL;
    if (contains(q, "新聞", "新闻", "news")) category = Catalog.Category.NEWS;
    else if (contains(q, "文化", "紀錄", "纪录", "documentary", "culture"))
      category = Catalog.Category.CULTURE;
    else if (contains(q, "親子", "亲子", "兒童", "儿童", "卡通", "動畫", "动画", "kids", "family"))
      category = Catalog.Category.FAMILY;
    else if (contains(q, "測試", "测试", "test")) category = Catalog.Category.TEST;
    TimeWindow time =
        contains(q, "接下來", "接下来", "稍後", "稍后", "next")
            ? TimeWindow.NEXT
            : contains(q, "正在播", "現在", "现在", "now") ? TimeWindow.NOW : TimeWindow.ANY;
    String language =
        contains(q, "粵語", "粤语", "廣東話", "广东话", "cantonese")
            ? "yue"
            : contains(q, "國語", "国语", "普通話", "普通话", "mandarin")
                ? "zh"
                : contains(q, "英語", "英语", "英文", "english") ? "en" : "";
    boolean favorites = contains(q, "收藏", "favorite", "favourite");
    String residual = q;
    for (String token :
        new String[] {
          "接下來",
          "接下来",
          "正在播",
          "普通話",
          "普通话",
          "廣東話",
          "广东话",
          "documentary",
          "cantonese",
          "mandarin",
          "favourite",
          "favorite",
          "新聞",
          "新闻",
          "news",
          "文化",
          "紀錄",
          "纪录",
          "culture",
          "親子",
          "亲子",
          "兒童",
          "儿童",
          "卡通",
          "動畫",
          "动画",
          "kids",
          "family",
          "測試",
          "测试",
          "test",
          "現在",
          "现在",
          "稍後",
          "稍后",
          "next",
          "now",
          "粵語",
          "粤语",
          "國語",
          "国语",
          "英語",
          "英语",
          "英文",
          "english",
          "收藏",
          "我想看",
          "想看",
          "幫我找",
          "帮我找",
          "給我看",
          "给我看",
          "的",
          "頻道",
          "频道",
          "節目",
          "节目"
        }) residual = residual.replace(token, " ");
    return new Intent(residual.replaceAll("\\s+", " ").trim(), category, time, language, favorites);
  }

  public List<Result> search(
      Catalog catalog, Guide guide, Intent intent, Set<String> favorites, Instant now) {
    List<Result> output = new ArrayList<>();
    for (Catalog.Channel c : catalog.channels) {
      if (intent.category != Catalog.Category.ALL && intent.category != c.category) continue;
      if (!intent.language.isEmpty() && !c.language.equals(intent.language)) continue;
      if (intent.favorites && !favorites.contains(c.id)) continue;
      Guide.Program program =
          intent.time == TimeWindow.NEXT ? guide.next(c.id, now) : guide.now(c.id, now);
      if (intent.time != TimeWindow.ANY && program == null) continue;
      String facts =
          normalize(
              c.name
                  + " "
                  + c.number
                  + " "
                  + c.description
                  + (program == null ? "" : " " + program.title + " " + program.description));
      boolean match = true;
      for (String word : normalize(intent.query).split("\\s+"))
        if (!word.isEmpty() && !facts.contains(word)) match = false;
      if (!match) continue;
      String reason =
          intent.favorites
              ? "你收藏的頻道"
              : intent.category != Catalog.Category.ALL
                  ? "符合所選分類"
                  : !intent.query.isEmpty() ? "符合名稱或節目資料" : "來自目前頻道目錄";
      output.add(new Result(c, program, reason));
    }
    return Collections.unmodifiableList(new ArrayList<>(output));
  }

  private static String normalize(String v) {
    return v == null ? "" : v.toLowerCase(Locale.ROOT).trim();
  }

  private static boolean contains(String v, String... terms) {
    for (String s : terms) if (v.contains(s)) return true;
    return false;
  }
}
