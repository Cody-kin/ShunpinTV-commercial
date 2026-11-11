package com.harbour.tv;

import com.harbour.core.PublicDrama;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Independently written parser. Never evaluates scripts or calls private App endpoints. */
public final class PublicDramaParser {
    private PublicDramaParser() {}
    public static final int MAX_HTML_BYTES = 2 * 1024 * 1024;
    private static final Pattern ROUTER = Pattern.compile("(?:window\\.)?_ROUTER_DATA\\s*=\\s*");
    private static final Pattern LD = Pattern.compile("<script\\b[^>]*type\\s*=\\s*[\"']application/ld\\+json[\"'][^>]*>(.*?)</script\\s*>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    static boolean id(String value) { return value != null && value.matches("[0-9]{6,30}"); }

    public static String checkedPage(String value) {
        try {
            URI u = new URI(value.trim());
            if (!"https".equalsIgnoreCase(u.getScheme()) || !"hongguoduanju.com".equalsIgnoreCase(u.getHost())
                    || u.getRawUserInfo() != null || (u.getPort() != -1 && u.getPort() != 443) || u.getRawFragment() != null)
                throw new IllegalArgumentException();
            String p = u.getRawPath();
            if ("/detail".equals(p) && u.getRawQuery() != null && u.getRawQuery().matches("series_id=[0-9]{6,30}"))
                return "https://hongguoduanju.com" + p + "?" + u.getRawQuery();
            if (p != null && p.matches("/player/[0-9]{6,30}(?:/[0-9]{6,30})?") && u.getRawQuery() == null)
                return "https://hongguoduanju.com" + p;
        } catch (Exception ignored) { }
        throw new IllegalArgumentException("請貼上紅果官方 HTTPS 詳情或播放頁網址（不接受短網址或其他網站）");
    }
    public static String seriesId(String page) {
        URI u = URI.create(checkedPage(page));
        return "/detail".equals(u.getPath()) ? u.getRawQuery().substring(10) : u.getPath().split("/")[2];
    }
    public static String episodePage(String series, String episode) {
        if (!id(series) || !id(episode)) throw new IllegalArgumentException("劇集編號無效");
        return checkedPage("https://hongguoduanju.com/player/" + series + "/" + episode);
    }
    private static JSONObject route(String html) throws Exception {
        if (html == null || html.length() > MAX_HTML_BYTES) throw new IllegalArgumentException("頁面過大或為空");
        Matcher m = ROUTER.matcher(html);
        if (!m.find() || m.end() >= html.length() || html.charAt(m.end()) != '{')
            throw new IllegalArgumentException("官網未提供可辨識的公開劇集資料");
        int start = m.end(), depth = 0; boolean quoted = false, escaped = false;
        for (int i = start; i < html.length(); i++) {
            char c = html.charAt(i);
            if (quoted) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') quoted = false;
            } else if (c == '"') quoted = true;
            else if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return new JSONObject(html.substring(start, i + 1)).getJSONObject("loaderData");
        }
        throw new IllegalArgumentException("官網資料不完整");
    }
    private static JSONObject pageData(String html) throws Exception {
        JSONObject loader = route(html);
        for (String key : new String[]{"detail_page", "player_(series_id)/page", "player_(series_id)/(vid)/page"}) {
            JSONObject data = loader.optJSONObject(key);
            if (data != null && Boolean.TRUE.equals(data.opt("isSuccess"))) return data;
        }
        throw new IllegalArgumentException("官網未返回成功的詳情資料");
    }
    public static PublicDrama parse(String html, String page) throws Exception {
        String expected = seriesId(page);
        JSONObject data = pageData(html), detail = data.getJSONObject("seriesDetail");
        if (!expected.equals(detail.optString("series_id"))) throw new IllegalArgumentException("官網回傳其他劇集，已停止");
        String title = detail.optString("series_name").trim();
        if (title.isEmpty() || title.length() > 300) throw new IllegalArgumentException("劇名資料無效");
        JSONArray rows = detail.getJSONArray("vid_list");
        if (rows.length() == 0 || rows.length() > 3000) throw new IllegalArgumentException("公開分集數量無效");
        List<String> episodes = new ArrayList<>(); HashSet<String> seen = new HashSet<>();
        for (int i = 0; i < rows.length(); i++) {
            Object raw = rows.get(i);
            if (!(raw instanceof String) || !id((String) raw) || !seen.add((String) raw))
                throw new IllegalArgumentException("公開分集編號無效或重複");
            episodes.add((String) raw);
        }
        return new PublicDrama(expected, title, reportedCount(detail, "episode_cnt"),
                reportedCount(detail, "accessible_episode_cnt"), episodes);
    }
    // Missing, string-coerced, fractional, negative or overflowing counts are unknown.
    private static int reportedCount(JSONObject data, String key) {
        Object value = data.opt(key);
        if (!(value instanceof Integer) && !(value instanceof Long)) return -1;
        long count = ((Number) value).longValue();
        return count >= 0 && count <= Integer.MAX_VALUE ? (int) count : -1;
    }
    public static PublicDrama.Assessment assess(String html, String series, String episode) {
        try {
            if (!id(series) || !id(episode)) return unknown();
            JSONObject loader = route(html);
            JSONObject data = loader.optJSONObject("player_(series_id)/(vid)/page");
            if (data == null) data = loader.optJSONObject("player_(series_id)/page");
            if (data == null || !Boolean.TRUE.equals(data.opt("isSuccess"))
                    || !series.equals(data.optString("series_id")) || !episode.equals(data.optString("vid"))
                    || data.optJSONObject("seriesDetail") == null
                    || !series.equals(data.getJSONObject("seriesDetail").optString("series_id"))) return unknown();
            if (Boolean.TRUE.equals(data.opt("protectedPlayback")))
                return new PublicDrama.Assessment(PublicDrama.State.PROTECTED, "此集為受保護播放，不能在此直接播放");
            if (!Boolean.FALSE.equals(data.opt("protectedPlayback"))) return unknown();
            JSONObject seo = data.optJSONObject("publicVideoSeo");
            if (seo == null || !Boolean.TRUE.equals(seo.opt("available")) || !series.equals(seo.optString("seriesId"))
                    || !episode.equals(seo.optString("itemId"))) return unknown();
            Matcher m = LD.matcher(html);
            while (m.find()) {
                JSONObject video;
                try { video = new JSONObject(m.group(1)); } catch (Exception ignored) { continue; }
                if (!"VideoObject".equals(video.optString("@type"))) continue;
                String canonical = checkedPage(video.optString("url"));
                if (!canonical.equals(episodePage(series, episode))
                        && !canonical.equals("https://hongguoduanju.com/player/" + series)) continue;
                URI media = new URI(video.optString("contentUrl"));
                // Deliberately narrower than upstream: same official origin, same episode, no token/query.
                if ("https".equals(media.getScheme()) && "hongguoduanju.com".equals(media.getHost())
                        && media.getRawUserInfo() == null && media.getPort() == -1 && media.getRawQuery() == null
                        && media.getRawFragment() == null && media.getRawPath().matches("/video/public/" + episode + "/[A-Za-z0-9_-]+\\.mp4"))
                    return new PublicDrama.Assessment(PublicDrama.State.PUBLIC_CANDIDATE,
                            "有同集公開媒體描述；完整片長及播放尚未驗證，本版不啟用播放");
            }
        } catch (Exception ignored) { }
        return unknown();
    }
    private static PublicDrama.Assessment unknown() {
        return new PublicDrama.Assessment(PublicDrama.State.UNKNOWN, "公開完整片源狀態未知，不能在此直接播放");
    }
}
