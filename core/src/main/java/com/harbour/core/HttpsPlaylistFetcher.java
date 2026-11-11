package com.harbour.core;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Bounded playlist-text fetch only; redirects never relax HTTPS or certificate validation. */
public final class HttpsPlaylistFetcher {
  private HttpsPlaylistFetcher() {}
  public interface ConnectionFactory { HttpURLConnection open(URL url) throws IOException; }
  public static final class Result {
    public final String text, finalUrl;
    Result(String text, String finalUrl) { this.text = text; this.finalUrl = finalUrl; }
  }
  /** Messages are fixed text/status codes and never contain URLs or transport exception details. */
  public static final class DownloadException extends IOException {
    DownloadException(String message) { super(message); }
  }
  public static Result fetch(String url) throws IOException {
    return fetch(url, target -> (HttpURLConnection) target.openConnection());
  }
  public static Result fetch(String url, ConnectionFactory factory) throws IOException {
    try {
      URI current = validate(url);
      Set<String> seen = new HashSet<>();
      int redirects = 0;
      while (true) {
        String key = current.getHost().toLowerCase(Locale.ROOT) + ":" + (current.getPort() == -1 ? 443 : current.getPort())
            + (current.getRawPath().isEmpty() ? "/" : current.getRawPath()) + "?" + current.getRawQuery();
        if (!seen.add(key)) throw new DownloadException("HTTPS 重新導向形成迴圈；原片單未改動。");
        HttpURLConnection c = factory.open(current.toURL());
        try {
          c.setInstanceFollowRedirects(false);
          c.setConnectTimeout(10000); c.setReadTimeout(10000);
          c.setRequestMethod("GET");
          int status = c.getResponseCode();
          if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
            if (redirects++ >= 5) throw new DownloadException("HTTPS 重新導向超過 5 次；原片單未改動。");
            String location = c.getHeaderField("Location");
            if (location == null || location.trim().isEmpty())
              throw new DownloadException("重新導向缺少 Location；原片單未改動。");
            current = validate(current.resolve(new URI(location.trim())).toString());
            continue;
          }
          if (status != 200) throw new DownloadException("伺服器回應 HTTP " + status + "，未取得片單；原片單未改動。");
          if (c.getContentLengthLong() > M3uPlaylist.MAX_CHARS)
            throw new DownloadException("清單超過 8 MiB 上限；原片單未改動。");
          try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = in.read(buffer)) != -1) {
              if (out.size() + count > M3uPlaylist.MAX_CHARS)
                throw new DownloadException("清單超過 8 MiB 上限；原片單未改動。");
              out.write(buffer, 0, count);
            }
            return new Result(new String(out.toByteArray(), StandardCharsets.UTF_8), current.toString());
          }
        } finally { c.disconnect(); }
      }
    } catch (DownloadException safe) { throw safe; }
    catch (SocketTimeoutException timeout) { throw new DownloadException("讀取來源逾時；原片單未改動。"); }
    catch (javax.net.ssl.SSLException tls) { throw new DownloadException("TLS 憑證驗證失敗，未降低安全驗證；原片單未改動。"); }
    catch (Exception failure) { throw new DownloadException("HTTPS 清單下載失敗，請核對網址及網路；原片單未改動。"); }
  }
  private static URI validate(String value) throws DownloadException {
    try {
      URI uri = new URI(value).normalize();
      if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
          || uri.getRawUserInfo() != null || uri.getPort() > 65535)
        throw new DownloadException("只允許沒有帳密的 HTTPS 網址及 HTTPS 重新導向；原片單未改動。");
      // A fragment is never sent to the server and must not bypass loop detection.
      String text = uri.toString(); int hash = text.indexOf('#');
      return hash < 0 ? uri : new URI(text.substring(0, hash));
    } catch (DownloadException safe) { throw safe; }
    catch (Exception malformed) { throw new DownloadException("HTTPS 網址或重新導向格式錯誤；原片單未改動。"); }
  }
}
