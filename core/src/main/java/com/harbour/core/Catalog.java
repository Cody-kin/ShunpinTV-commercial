package com.harbour.core;

import java.net.URI;
import java.time.Instant;
import java.util.*;

/** Immutable, provider-neutral catalog. License metadata is a technical gate, not legal proof. */
public final class Catalog {
  public enum Category {
    ALL,
    NEWS,
    CULTURE,
    FAMILY,
    TEST
  }

  public enum Rights {
    OWNED_DEMO,
    CONTRACT_REVIEWED,
    USER_PROVIDED,
    UNVERIFIED
  }

  @FunctionalInterface
  public interface HttpPlaybackPolicy {
    HttpPlaybackPolicy DENY = (sourceId, uri) -> false;
    boolean permits(String sourceId, URI uri);
  }

  public static final class Source {
    public final String id, uri, mimeType, licenseReference;
    public final Rights rights;
    private final HttpPlaybackPolicy httpPolicy;
    public final Set<String> territories;
    public final Instant validFrom, validUntil;

    public Source(
        String id,
        String uri,
        String mimeType,
        Rights rights,
        Set<String> territories,
        Instant validFrom,
        Instant validUntil,
        String licenseReference) {
      this(id, uri, mimeType, rights, territories, validFrom, validUntil, licenseReference,
          HttpPlaybackPolicy.DENY);
    }

    public Source(String id, String uri, String mimeType, Rights rights, Set<String> territories,
        Instant validFrom, Instant validUntil, String licenseReference, HttpPlaybackPolicy httpPolicy) {
      this.httpPolicy = Objects.requireNonNull(httpPolicy);
      this.id = required(id);
      this.uri = required(uri);
      this.mimeType = required(mimeType);
      this.rights = Objects.requireNonNull(rights);
      this.territories = Collections.unmodifiableSet(new HashSet<>(territories));
      this.validFrom = Objects.requireNonNull(validFrom);
      this.validUntil = Objects.requireNonNull(validUntil);
      this.licenseReference = licenseReference == null ? "" : licenseReference;
      if (!validFrom.isBefore(validUntil))
        throw new IllegalArgumentException("Invalid rights interval");
    }

    public String blockedReason(String territory, Instant now) {
      if (rights == Rights.UNVERIFIED || licenseReference.trim().isEmpty()) return "來源授權未核實";
      if (!territories.contains(territory)) return "此來源未獲選定地區授權";
      if (now.isBefore(validFrom) || !now.isBefore(validUntil)) return "來源授權未生效或已到期";
      URI parsed;
      try {
        parsed = URI.create(uri);
      } catch (RuntimeException e) {
        return "來源網址無效";
      }
      if (rights == Rights.OWNED_DEMO) {
        return (uri.matches("asset:///demo_[a-z_]+\\.mp4")
                || uri.equals("asset:///hls-owned/playlist.m3u8"))
            ? null
            : "示範來源必須是已封裝的自有素材";
      }
      if (parsed.getHost() == null) return "來源網址缺少主機";
      if (parsed.getUserInfo() != null) return "來源網址含帳密，請使用不嵌入帳密的網址";
      if ("http".equalsIgnoreCase(parsed.getScheme()))
        return httpPolicy.permits(id, parsed) ? null : "此 HTTP 來源尚未啟用播放，並非已確認失效";
      if (!"https".equalsIgnoreCase(parsed.getScheme())) return "此串流協定未支援播放";
      return null;
    }
  }

  public static final class Channel {
    public final String id, number, name, language, description, accent;
    public final Category category;
    public final List<Source> sources;

    public Channel(
        String id,
        String number,
        String name,
        Category category,
        String language,
        String description,
        String accent,
        List<Source> sources) {
      this.id = required(id);
      this.number = required(number);
      this.name = required(name);
      this.category = Objects.requireNonNull(category);
      this.language = required(language);
      this.description = required(description);
      this.accent = required(accent);
      this.sources = Collections.unmodifiableList(new ArrayList<>(sources));
      Set<String> ids = new HashSet<>();
      for (Source s : sources)
        if (!ids.add(s.id)) throw new IllegalArgumentException("Duplicate source ID");
    }

    public boolean isDemo() {
      return !sources.isEmpty() && sources.stream().allMatch(s -> s.rights == Rights.OWNED_DEMO);
    }

    public List<Source> eligible(String territory, Instant now) {
      List<Source> found = new ArrayList<>();
      for (Source s : sources) if (s.blockedReason(territory, now) == null) found.add(s);
      return Collections.unmodifiableList(new ArrayList<>(found));
    }
  }

  public final String provider, sourceReference;
  public final Instant updatedAt;
  public final List<Channel> channels;

  public Catalog(
      String provider, String sourceReference, Instant updatedAt, List<Channel> channels) {
    this.provider = required(provider);
    this.sourceReference = required(sourceReference);
    this.updatedAt = Objects.requireNonNull(updatedAt);
    this.channels = Collections.unmodifiableList(new ArrayList<>(channels));
    Set<String> ids = new HashSet<>();
    for (Channel c : channels)
      if (!ids.add(c.id)) throw new IllegalArgumentException("Duplicate channel ID");
  }

  public Channel find(String id) {
    return channels.stream().filter(c -> c.id.equals(id)).findFirst().orElse(null);
  }

  private static String required(String v) {
    if (v == null || v.trim().isEmpty())
      throw new IllegalArgumentException("Required field is empty");
    return v;
  }
}
