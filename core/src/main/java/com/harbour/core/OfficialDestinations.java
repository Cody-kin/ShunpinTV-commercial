package com.harbour.core;

import java.net.URI;

/** Fixed public landing pages, never constructed from a playlist, model result, or user input. */
public final class OfficialDestinations {
  private OfficialDestinations() {}
  public enum Destination {
    DOUYIN("抖音", "https://www.douyin.com/"),
    HONGGUO("紅果短劇", "https://hongguoduanju.com/"),
    CCTV_SEARCH("央視官方搜尋", "https://search.cctv.com/");
    public final String title, url;
    Destination(String title, String url) { this.title = title; this.url = url; }
  }
  public static boolean isAllowed(String url) {
    if (url == null) return false;
    try {
      URI uri = URI.create(url);
      if (!"https".equals(uri.getScheme()) || uri.getRawUserInfo() != null
          || uri.getPort() != -1 || uri.getRawQuery() != null || uri.getRawFragment() != null) return false;
      for (Destination destination : Destination.values()) if (destination.url.equals(url)) return true;
    } catch (IllegalArgumentException ignored) { }
    return false;
  }
}
