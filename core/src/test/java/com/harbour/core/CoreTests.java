package com.harbour.core;

import java.time.*;
import java.util.*;

/** No network, keys, SDK, or external test dependency required. Run scripts/test-core.sh. */
public final class CoreTests {
  private static int checks;
  private static final Instant NOW = Instant.parse("2026-11-07T12:00:00Z");

  public static void main(String[] args) {
    rights();
    httpPolicy();
    Catalog.Source supplied =
        source(
            Catalog.Rights.USER_PROVIDED,
            "https://example.com/stream.m3u8",
            Set.of("HK"),
            NOW.minusSeconds(1),
            NOW.plusSeconds(1),
            "USER DECLARATION");
    check(
        supplied.blockedReason("HK", NOW) == null,
        "user-provided allowed with explicit declaration");
    check(supplied.blockedReason("TW", NOW) != null, "user-provided territory gate");
    check(supplied.blockedReason("HK", NOW.plusSeconds(1)) != null, "user-provided expiry gate");
    check(
        source(
                    Catalog.Rights.USER_PROVIDED,
                    "http://example.com/stream",
                    Set.of("HK"),
                    NOW.minusSeconds(1),
                    NOW.plusSeconds(1),
                    "declared")
                .blockedReason("HK", NOW)
            != null,
        "user-provided HTTPS gate");
    guide();
    search();
    recovery();
    invariants();
    System.out.println("PASS: " + checks + " deterministic core assertions");
  }

  private static void httpPolicy() {
    Catalog.HttpPlaybackPolicy exact = (id, uri) -> id.equals("reviewed")
        && "test-streams.mux.dev".equals(uri.getHost()) && uri.getPort() == -1;
    Catalog.Source allowed = new Catalog.Source("reviewed", "http://test-streams.mux.dev/test.m3u8",
        "application/x-mpegURL", Catalog.Rights.USER_PROVIDED, Set.of("HK"),
        NOW.minusSeconds(1), NOW.plusSeconds(1), "user declaration", exact);
    check(allowed.blockedReason("HK", NOW) == null, "injected HTTP source policy permits reviewed host");
    check(allowed.blockedReason("TW", NOW) != null, "HTTP policy cannot bypass territory");
    check(allowed.blockedReason("HK", NOW.plusSeconds(1)) != null, "HTTP policy cannot bypass expiry");
    check(allowed.blockedReason("HK", NOW.minusSeconds(2)) != null, "HTTP policy cannot bypass validFrom");
    for (String uri : List.of("http://other.example/test", "http://test-streams.mux.dev.evil.test/test",
        "http://test-streams.mux.dev:8080/test", "http://user@test-streams.mux.dev/test")) {
      Catalog.Source rejected = new Catalog.Source("reviewed", uri, "application/x-mpegURL",
          Catalog.Rights.USER_PROVIDED, Set.of("HK"), NOW.minusSeconds(1), NOW.plusSeconds(1), "declared", exact);
      check(rejected.blockedReason("HK", NOW) != null, "HTTP policy/URI rejects unrelated or credentialed target");
    }
    Catalog.Source unverified = new Catalog.Source("reviewed", allowed.uri, allowed.mimeType,
        Catalog.Rights.UNVERIFIED, Set.of("HK"), NOW.minusSeconds(1), NOW.plusSeconds(1), "declared", exact);
    check(unverified.blockedReason("HK", NOW) != null, "HTTP policy cannot bypass unverified rights");
    Catalog.Source noLicense = new Catalog.Source("reviewed", allowed.uri, allowed.mimeType,
        Catalog.Rights.USER_PROVIDED, Set.of("HK"), NOW.minusSeconds(1), NOW.plusSeconds(1), "", exact);
    check(noLicense.blockedReason("HK", NOW) != null, "HTTP policy cannot bypass missing declaration");
    Catalog.Source defaultDeny = new Catalog.Source("reviewed", allowed.uri, allowed.mimeType,
        Catalog.Rights.USER_PROVIDED, Set.of("HK"), NOW.minusSeconds(1), NOW.plusSeconds(1), "declared");
    check(defaultDeny.blockedReason("HK", NOW) != null, "legacy Source constructor remains HTTP deny");
  }

  private static Catalog.Source source(
      Catalog.Rights rights,
      String uri,
      Set<String> regions,
      Instant start,
      Instant end,
      String reference) {
    return new Catalog.Source("source", uri, "video/mp4", rights, regions, start, end, reference);
  }

  private static Catalog.Source demo() {
    return source(
        Catalog.Rights.OWNED_DEMO,
        "asset:///demo_test.mp4",
        Set.of("HK", "TW"),
        NOW.minusSeconds(3600),
        NOW.plusSeconds(3600),
        "SELF");
  }

  private static Catalog.Channel channel(
      String id, String name, Catalog.Category category, String language) {
    return new Catalog.Channel(
        id, id, name, category, language, "原創測試資料", "#61E6CC", List.of(demo()));
  }

  private static Catalog catalog() {
    return new Catalog(
        "Test",
        "fixture",
        NOW,
        List.of(
            channel("01", "港灣新聞", Catalog.Category.NEWS, "yue"),
            channel("02", "親子動畫", Catalog.Category.FAMILY, "zh"),
            channel("03", "文化紀錄", Catalog.Category.CULTURE, "en")));
  }

  private static void rights() {
    check(source(Catalog.Rights.OWNED_DEMO, "asset:///hls-owned/playlist.m3u8", Set.of("HK"), NOW.minusSeconds(1), NOW.plusSeconds(1), "SELF").blockedReason("HK", NOW) == null, "exact owned HLS playlist allowed");
    for (String uri : List.of("asset:///hls-owned/other.m3u8", "asset:///hls-owned/../playlist.m3u8", "asset:///hls-owned/playlist.m3u8?remote=true", "asset:///arbitrary.m3u8", "https://example.org/playlist.m3u8")) {
      check(source(Catalog.Rights.OWNED_DEMO, uri, Set.of("HK"), NOW.minusSeconds(1), NOW.plusSeconds(1), "SELF").blockedReason("HK", NOW) != null, "owned HLS rejects non-exact asset " + uri);
    }
    check(demo().blockedReason("HK", NOW) == null, "HK own content allowed");
    check(demo().blockedReason("TW", NOW) == null, "TW own content allowed");
    check(demo().blockedReason("US", NOW) != null, "territory boundary");
    check(demo().blockedReason("HK", NOW.plusSeconds(3600)) != null, "expiry exclusive");
    check(demo().blockedReason("HK", NOW.minusSeconds(3600)) == null, "start inclusive");
    check(demo().blockedReason("HK", NOW.minusSeconds(3601)) != null, "future rights");
    check(
        source(
                    Catalog.Rights.UNVERIFIED,
                    "https://example.com/v",
                    Set.of("HK"),
                    NOW.minusSeconds(1),
                    NOW.plusSeconds(1),
                    "ref")
                .blockedReason("HK", NOW)
            != null,
        "unverified denied");
    for (String url :
        List.of(
            "http://example.com/v",
            "file:///etc/passwd",
            "javascript:alert(1)",
            "https://user:pass@example.com/v",
            "data:video/mp4,x",
            "https:///video"))
      check(
          source(
                      Catalog.Rights.CONTRACT_REVIEWED,
                      url,
                      Set.of("HK"),
                      NOW.minusSeconds(1),
                      NOW.plusSeconds(1),
                      "ref")
                  .blockedReason("HK", NOW)
              != null,
          "unsafe source denied");
    check(
        source(
                    Catalog.Rights.CONTRACT_REVIEWED,
                    "https://example.com/v",
                    Set.of("HK"),
                    NOW.minusSeconds(1),
                    NOW.plusSeconds(1),
                    "ref")
                .blockedReason("HK", NOW)
            == null,
        "HTTPS provider accepted");
    check(
        source(
                    Catalog.Rights.OWNED_DEMO,
                    "https://example.com/v",
                    Set.of("HK"),
                    NOW.minusSeconds(1),
                    NOW.plusSeconds(1),
                    "ref")
                .blockedReason("HK", NOW)
            != null,
        "demo cannot introduce remote source");
    check(
        source(
                    Catalog.Rights.CONTRACT_REVIEWED,
                    "asset:///demo_test.mp4",
                    Set.of("HK"),
                    NOW.minusSeconds(1),
                    NOW.plusSeconds(1),
                    "ref")
                .blockedReason("HK", NOW)
            != null,
        "contract cannot use asset path");
    check(
        source(
                    Catalog.Rights.OWNED_DEMO,
                    "asset:///../demo_test.mp4",
                    Set.of("HK"),
                    NOW.minusSeconds(1),
                    NOW.plusSeconds(1),
                    "ref")
                .blockedReason("HK", NOW)
            != null,
        "asset traversal denied");
    check(
        source(
                    Catalog.Rights.OWNED_DEMO,
                    "asset:///demo_test.mp4",
                    Set.of("HK"),
                    NOW.minusSeconds(1),
                    NOW.plusSeconds(1),
                    "")
                .blockedReason("HK", NOW)
            != null,
        "no evidence denied");
    rejects(
        () ->
            source(
                Catalog.Rights.OWNED_DEMO, "asset:///demo_test.mp4", Set.of("HK"), NOW, NOW, "x"),
        "invalid license interval");
  }

  private static Guide.Program program(
      String id, String channel, Instant start, Instant end, Instant fetched) {
    return new Guide.Program(id, channel, "海岸故事", "真實輸入的節目資料", "fixture-epg", start, end, fetched);
  }

  private static void guide() {
    Guide.Program now = program("p1", "01", NOW, NOW.plusSeconds(100), NOW);
    Guide.Program next = program("p2", "01", NOW.plusSeconds(100), NOW.plusSeconds(200), NOW);
    Guide g = new Guide(List.of(next, now), catalog());
    check(g.now("01", NOW) == now, "start inclusive");
    check(g.now("01", NOW.plusSeconds(100)) == next, "end exclusive");
    check(g.now("01", NOW.minusSeconds(1)) == null, "not started");
    check(g.next("01", NOW) == next, "next sorted");
    check(g.now("02", NOW) == null, "no epg remains unknown");
    check(g.now("01", NOW.plusSeconds(200)) == null, "expired coverage");
    Guide.Program stale =
        program(
            "old", "01", NOW.minusSeconds(1), NOW.plusSeconds(50), NOW.minus(Duration.ofHours(25)));
    check(!stale.onNow(NOW), "stale epg not live");
    check(
        !program("future", "01", NOW.minusSeconds(1), NOW.plusSeconds(50), NOW.plusSeconds(301))
            .onNow(NOW),
        "future fetch time rejected");
    check(Guide.parseXmltvTime("20261107200000 +0800").equals(NOW), "HK TW UTC conversion");
    check(Guide.parseXmltvTime("20261107070000 -0500").equals(NOW), "negative offset conversion");
    rejects(() -> Guide.parseXmltvTime("20261107200000"), "missing offset rejected");
    rejects(() -> Guide.parseXmltvTime("20260230000000 +0800"), "impossible date rejected");
    rejects(() -> Guide.parseXmltvTime("20261107200000 +2500"), "invalid offset rejected");
    rejects(() -> new Guide(List.of(now, now), catalog()), "duplicate EPG ID rejected");
    rejects(
        () -> new Guide(List.of(program("x", "unknown", NOW, NOW.plusSeconds(1), NOW)), catalog()),
        "orphan EPG rejected");
    rejects(() -> program("x", "01", NOW, NOW, NOW), "empty EPG interval rejected");
  }

  private static void search() {
    Catalog c = catalog();
    SearchEngine engine = new SearchEngine();
    Guide empty = new Guide(List.of(), c);
    check(
        engine.search(c, empty, engine.parse(""), Set.of(), NOW).size() == 3,
        "empty query lists catalog");
    check(
        engine.search(c, empty, engine.parse("我想看粵語新聞"), Set.of(), NOW).size() == 1,
        "local Cantonese news intent");
    check(
        engine.search(c, empty, engine.parse("國語卡通"), Set.of(), NOW).get(0).channel.id.equals("02"),
        "family language intent");
    check(
        engine.search(c, empty, engine.parse("英文紀錄"), Set.of(), NOW).get(0).channel.id.equals("03"),
        "culture language intent");
    check(
        engine.search(c, empty, engine.parse("CCTV-4"), Set.of(), NOW).isEmpty(),
        "no invented channel");
    check(
        engine.search(c, empty, engine.parse("正在播新聞"), Set.of(), NOW).isEmpty(),
        "no fabricated schedule");
    check(
        engine.search(c, empty, engine.parse("收藏"), Set.of("02"), NOW).size() == 1,
        "favorite restriction");
    check(
        engine.search(c, empty, engine.parse("港灣"), Set.of(), NOW).get(0).channel.id.equals("01"),
        "title lookup");
    check(
        engine.search(c, empty, engine.parse("不存在"), Set.of(), NOW).isEmpty(), "no false fallback");
    Guide guide =
        new Guide(
            List.of(
                program("p1", "01", NOW.minusSeconds(1), NOW.plusSeconds(10), NOW),
                program("p2", "01", NOW.plusSeconds(10), NOW.plusSeconds(20), NOW)),
            c);
    check(
        engine
            .search(c, guide, engine.parse("正在播新聞"), Set.of(), NOW)
            .get(0)
            .program
            .id
            .equals("p1"),
        "now grounded");
    check(
        engine
            .search(c, guide, engine.parse("接下來新聞"), Set.of(), NOW)
            .get(0)
            .program
            .id
            .equals("p2"),
        "next grounded");
    check(
        engine.search(c, guide, engine.parse("海岸"), Set.of(), NOW).size() == 1,
        "program title grounded");
    check(
        engine.search(c, guide, engine.parse("親子英文"), Set.of(), NOW).isEmpty(),
        "combined requirements strict");
    check(new IntentProvider.Local().disclosure().contains("未連接"), "honest AI disclosure");
    for (int i = 0; i < 20; i++)
      check(
          engine.search(c, empty, engine.parse("測試"), Set.of(), NOW).isEmpty(),
          "repeated query has no external state");
  }

  private static void recovery() {
    RecoveryPolicy p = new RecoveryPolicy();
    check(p.onFailure(RecoveryPolicy.Fault.TRANSIENT, false).delayMs == 1000, "first retry");
    check(p.onFailure(RecoveryPolicy.Fault.TRANSIENT, false).delayMs == 2000, "second retry");
    check(p.onFailure(RecoveryPolicy.Fault.TRANSIENT, false).delayMs == 4000, "third retry");
    check(
        p.onFailure(RecoveryPolicy.Fault.TRANSIENT, false).action == RecoveryPolicy.Action.STOP,
        "bounded retries");
    p.reset();
    for (int i = 0; i < 3; i++) p.onFailure(RecoveryPolicy.Fault.TRANSIENT, true);
    check(
        p.onFailure(RecoveryPolicy.Fault.TRANSIENT, true).action
            == RecoveryPolicy.Action.NEXT_SOURCE,
        "authorized failover");
    p.reset();
    check(
        p.onFailure(RecoveryPolicy.Fault.ACCESS_DENIED, true).action == RecoveryPolicy.Action.STOP,
        "no bypass on 403");
    check(
        p.onFailure(RecoveryPolicy.Fault.DRM, true).action == RecoveryPolicy.Action.STOP,
        "no DRM bypass");
    check(p.onFailure(RecoveryPolicy.Fault.SECURITY, true).action == RecoveryPolicy.Action.STOP,
        "certificate failures never retry or bypass through fallback");
    check(p.onFailure(RecoveryPolicy.Fault.NOT_FOUND, true).action == RecoveryPolicy.Action.NEXT_SOURCE,
        "gone source may select an already eligible alternative without repeated requests");
    check(p.onFailure(RecoveryPolicy.Fault.NOT_FOUND, false).action == RecoveryPolicy.Action.STOP,
        "gone source without alternative stops immediately");
    check(
        p.onFailure(RecoveryPolicy.Fault.UNSUPPORTED, true).action
            == RecoveryPolicy.Action.NEXT_SOURCE,
        "codec alternative");
    check(
        p.onFailure(RecoveryPolicy.Fault.UNSUPPORTED, false).action == RecoveryPolicy.Action.STOP,
        "codec no alternative");
    p.reset();
    for (int i=0;i<3;i++) check(p.onFailure(RecoveryPolicy.Fault.LIVE_WINDOW, true).action == RecoveryPolicy.Action.SEEK_LIVE, "bounded live-edge recovery");
    check(p.onFailure(RecoveryPolicy.Fault.LIVE_WINDOW, true).action == RecoveryPolicy.Action.STOP, "stale live window cannot loop forever");
  }

  private static void invariants() {
    Catalog c = catalog();
    rejects(
        () -> new Catalog("p", "s", NOW, List.of(c.channels.get(0), c.channels.get(0))),
        "duplicate channels rejected");
    rejects(
        () ->
            new Catalog.Channel(
                "id",
                "1",
                "name",
                Catalog.Category.TEST,
                "zxx",
                "test",
                "#fff",
                List.of(demo(), demo())),
        "duplicate sources rejected");
    rejects(() -> c.channels.clear(), "catalog immutable");
    rejects(() -> demo().territories.clear(), "territories immutable");
    check(c.find("missing") == null, "unknown channel safe");
    check(c.channels.get(0).eligible("US", NOW).isEmpty(), "eligibility enforces rights");
  }

  private static void check(boolean truth, String name) {
    if (!truth) throw new AssertionError(name);
    checks++;
  }

  private static void rejects(Runnable action, String name) {
    try {
      action.run();
    } catch (RuntimeException expected) {
      checks++;
      return;
    }
    throw new AssertionError(name);
  }
}
