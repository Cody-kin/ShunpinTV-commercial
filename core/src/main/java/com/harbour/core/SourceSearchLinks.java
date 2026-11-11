package com.harbour.core;

import java.net.URLEncoder;
import java.io.UnsupportedEncodingException;
import java.text.Normalizer;

/** Builds a general web search URL only; never fetches results or imports a playlist. */
public final class SourceSearchLinks {
  public static final int MAX_QUERY_LENGTH = 80;
  private SourceSearchLinks() {}

  public static String keywords(String query) {
    String raw = query == null ? "" : query;
    if (raw.length() > MAX_QUERY_LENGTH) throw new IllegalArgumentException("搜尋關鍵字最多 80 字元。");
    for (int i = 0; i < raw.length(); i++) {
      char c = raw.charAt(i);
      if (Character.isISOControl(c) || Character.getType(c) == Character.FORMAT)
        throw new IllegalArgumentException("搜尋關鍵字不能含控制字元。");
    }
    String normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC).trim();
    if (normalized.contains("://"))
      throw new IllegalArgumentException("不要把來源網址送往搜尋網站；請在來源管理貼上已知網址。");
    if (normalized.length() > MAX_QUERY_LENGTH)
      throw new IllegalArgumentException("搜尋關鍵字最多 80 字元。");
    return normalized.isEmpty() ? "IPTV M3U 官方" : normalized + " M3U 播放清單 官方";
  }

  public static String build(String query) {
    try { return "https://www.bing.com/search?q=" + URLEncoder.encode(keywords(query), "UTF-8"); }
    catch (UnsupportedEncodingException impossible) { throw new AssertionError(impossible); }
  }
}
