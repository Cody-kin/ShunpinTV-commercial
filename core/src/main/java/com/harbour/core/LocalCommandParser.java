package com.harbour.core;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Small deterministic Chinese command grammar. No LLM, network, intents, or side effects. */
public final class LocalCommandParser {
  public static final int MAX_INPUT = 160;
  public enum Action { HELP, SEARCH, SOURCE_SEARCH, PLAY, FAVORITE, CATEGORY, IMPORT, WEB_SEARCH }
  public static final class Command {
    public final Action action;
    public final String query;
    public final String explanation;
    private Command(Action action, String query, String explanation) {
      this.action = action;
      this.query = query;
      this.explanation = explanation;
    }
  }
  private static Command result(Action action, String query, String explanation) {
    return new Command(action, query, explanation);
  }
  public Command parse(String input) {
    if (input == null || input.trim().isEmpty()) return help("請輸入頻道名稱或一個指令。");
    if (input.length() > MAX_INPUT) return help("指令過長，請縮短為 160 字元內。");
    String raw = Normalizer.normalize(input, Normalizer.Form.NFKC).trim();
    String q = normalize(raw).replaceAll("^[，。!！?？\\s]+|[，。!！?？\\s]+$", "");
    // Never reinterpret a negative/destructive request as an affirmative action.
    if (contains(q, "不要", "别", "取消", "删除", "清除", "移除", "卸载", "执行", "运行", "intent:", "javascript:", "file:", "content:"))
      return help("這個指令不會執行；請使用單一步驟的查找、播放、收藏或匯入。");
    if (contains(q, ";", "；", "然后", "并且", "同时", "再播放", "再收藏", "再导入"))
      return help("一次請輸入一個操作，先預覽匹配結果再確認。");
    q = q.replaceFirst("^(请帮我|麻烦帮我|帮我|请|麻烦)", "").trim();
    if (q.startsWith("导入") || q.startsWith("汇入") || q.startsWith("添加来源")
        || q.startsWith("新增来源") || q.startsWith("https://") || q.startsWith("http://"))
      return result(Action.IMPORT, "", "開啟來源管理後，選擇檔案或輸入已知來源網址；這裡不下載或匯入任何網址。");
    if (contains(q, "网上", "联网", "网络", "全网", "新网址", "新链接", "官方搜索", "官方搜寻")) {
      String keywords = q.contains("://") ? q : q
          .replaceAll("网上|联网|网络|全网|新网址|新链接|官方搜索|官方搜寻|m3u8?|来源|片单", "")
          .trim().replaceFirst("^(查找|寻找|搜寻|搜索|查|找|搜)", "").trim();
      return result(Action.WEB_SEARCH, keywords, "先預覽本機來源；按網頁搜尋才會把顯示的關鍵字送往 Bing。這是一般搜尋，不是雲端 AI。");
    }
    if (q.equals("收藏") || q.equals("我的收藏") || q.equals("打开收藏") || q.equals("查看收藏") || q.equals("看收藏"))
      return result(Action.CATEGORY, "收藏", "切換到本機收藏分類。");
    if (q.equals("来源管理") || q.equals("管理来源") || q.equals("打开来源管理"))
      return result(Action.IMPORT, "", "開啟本機來源管理。");
    Action action = Action.SEARCH;
    if (q.startsWith("收藏") || q.startsWith("加入收藏")) {
      action = Action.FAVORITE;
      q = q.replaceFirst("^(加入收藏|收藏)", "").trim();
    } else if (q.startsWith("播放") || q.startsWith("收看") || q.startsWith("我想看") || q.startsWith("想看") || q.startsWith("看")) {
      action = Action.PLAY;
      q = q.replaceFirst("^(播放|收看|我想看|想看|看)", "").trim();
    } else {
      q = q.replaceFirst("^(查找|寻找|搜寻|搜索|查|找|搜|打开|查看)", "").trim();
    }
    if (contains(q, "m3u", "来源", "片单")) {
      if (action != Action.SEARCH) return help("來源請先搜尋或匯入，再從本機匹配頻道選擇播放。");
      action = Action.SOURCE_SEARCH;
      q = q.replaceAll("m3u8?|来源|片单", "").trim();
    }
    q = q.replaceFirst("^(频道|电视)", "").replaceFirst("(频道|电视)$", "").trim();
    String category = category(q);
    if (category != null && action != Action.FAVORITE && action != Action.SOURCE_SEARCH)
      return result(Action.CATEGORY, category, "預覽分類後切換；不會自動選台播放。");
    if (q.isEmpty() && action != Action.SOURCE_SEARCH) return help("請加上頻道名稱，例如「播放 CCTV1」。");
    return result(action, q, action == Action.SOURCE_SEARCH
        ? "只搜尋已匯入本機的來源名稱與分組，不會搜尋網際網路。"
        : "只比對本機目錄中的頻道名稱、編號與分組；請選擇匹配結果確認。");
  }
  private static Command help(String message) { return result(Action.HELP, "", message); }
  private static boolean contains(String text, String... parts) {
    for (String part : parts) if (text.contains(part)) return true;
    return false;
  }
  private static String category(String q) {
    switch (q) {
      case "全部": case "所有": return "全部";
      case "cctv": case "央视": case "中央台": return "CCTV";
      case "卫视": return "衛視";
      case "香港": case "港台": return "香港";
      case "地方": return "地方";
      case "其他": return "其他";
      case "自备": case "自订": return "自備";
      default: return null;
    }
  }
  /** This is a limited, explicit character mapping, not a general Chinese converter. */
  public static String normalize(String text) {
    String q = Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    String from = "請幫尋搜尋頻視衛臺灣聞體綜藝電網絡匯導開來單備訂為這個刪執運別並時後連結輸";
    String to =   "请帮寻搜寻频视卫台湾闻体综艺电网络汇导开来单备订为这个删执运别并时后连结输";
    for (int i = 0; i < from.length(); i++) q = q.replace(from.charAt(i), to.charAt(i));
    return q.replace("央視", "央视").replaceAll("\\s+", " ").trim();
  }
  public List<Catalog.Channel> match(Catalog catalog, Command command, Map<String, String> groups) {
    if (catalog == null) return Collections.emptyList();
    if (!(command.action == Action.SEARCH || command.action == Action.SOURCE_SEARCH || command.action == Action.WEB_SEARCH
        || command.action == Action.PLAY || command.action == Action.FAVORITE)) return Collections.emptyList();
    String needle = compact(command.query);
    List<Catalog.Channel> exact = new ArrayList<>(), partial = new ArrayList<>();
    for (Catalog.Channel channel : catalog.channels) {
      if ((command.action == Action.SOURCE_SEARCH || command.action == Action.WEB_SEARCH) && !channel.id.startsWith("user-")) continue;
      String name = compact(channel.name), number = compact(channel.number);
      String group = compact(groups == null ? "" : groups.getOrDefault(channel.id, ""));
      if (!needle.isEmpty() && (name.equals(needle) || number.equals(needle))) exact.add(channel);
      else if (needle.isEmpty() || name.contains(needle) || number.contains(needle) || group.contains(needle)) partial.add(channel);
    }
    exact.addAll(partial);
    return Collections.unmodifiableList(exact);
  }
  private static String compact(String text) {
    return normalize(text).replace("央视", "cctv").replace("中央台", "cctv").replaceAll("[\\s_－-]", "");
  }
}
