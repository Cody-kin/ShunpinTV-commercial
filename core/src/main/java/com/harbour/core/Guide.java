package com.harbour.core;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** EPG facts retain source and fetch time; no synthetic schedules are generated. */
public final class Guide {
  public static final Duration MAX_AGE = Duration.ofHours(24);

  public static final class Program {
    public final String id, channelId, title, description, sourceId;
    public final Instant start, end, fetchedAt;

    public Program(
        String id,
        String channelId,
        String title,
        String description,
        String sourceId,
        Instant start,
        Instant end,
        Instant fetchedAt) {
      if (id == null
          || id.trim().isEmpty()
          || channelId == null
          || channelId.trim().isEmpty()
          || title == null
          || title.trim().isEmpty()
          || sourceId == null
          || sourceId.trim().isEmpty())
        throw new IllegalArgumentException("Missing EPG identity/source");
      if (!Objects.requireNonNull(start).isBefore(Objects.requireNonNull(end)))
        throw new IllegalArgumentException("Invalid EPG interval");
      this.id = id;
      this.channelId = channelId;
      this.title = title;
      this.description = description == null ? "" : description;
      this.sourceId = sourceId;
      this.start = start;
      this.end = end;
      this.fetchedAt = Objects.requireNonNull(fetchedAt);
    }

    public boolean fresh(Instant now) {
      return !fetchedAt.isAfter(now.plusSeconds(300)) && !fetchedAt.isBefore(now.minus(MAX_AGE));
    }

    public boolean onNow(Instant now) {
      return fresh(now) && !now.isBefore(start) && now.isBefore(end);
    }
  }

  private final List<Program> programs;

  public Guide(List<Program> input, Catalog catalog) {
    Set<String> ids = new HashSet<>();
    for (Program p : input) {
      if (!ids.add(p.id)) throw new IllegalArgumentException("Duplicate program ID");
      if (catalog.find(p.channelId) == null)
        throw new IllegalArgumentException("Unknown EPG channel");
    }
    List<Program> sorted = new ArrayList<>(input);
    sorted.sort(Comparator.comparing(p -> p.start));
    this.programs = Collections.unmodifiableList(new ArrayList<>(sorted));
  }

  public List<Program> programs() {
    return programs;
  }

  public Program now(String channel, Instant at) {
    return programs.stream()
        .filter(p -> p.channelId.equals(channel) && p.onNow(at))
        .findFirst()
        .orElse(null);
  }

  public Program next(String channel, Instant at) {
    return programs.stream()
        .filter(p -> p.channelId.equals(channel) && p.fresh(at) && p.start.isAfter(at))
        .findFirst()
        .orElse(null);
  }

  public static Instant parseXmltvTime(String value) {
    // XMLTV input MUST include an explicit offset. Never infer a device timezone.
    if (value == null || !value.matches("\\d{14} [+-]\\d{4}"))
      throw new IllegalArgumentException("Explicit XMLTV timezone required");
    return OffsetDateTime.parse(
            value,
            DateTimeFormatter.ofPattern("uuuuMMddHHmmss xx")
                .withResolverStyle(java.time.format.ResolverStyle.STRICT))
        .toInstant();
  }
}
