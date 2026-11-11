package com.harbour.core;

import java.util.Collections;
import java.util.ArrayList;
import java.util.List;

/** Public metadata only. A candidate is not playback authorization or verification. */
public final class PublicDrama {
    public final String seriesId, title;
    /** -1 means absent or invalid upstream metadata; never inferred from list size. */
    public final int reportedEpisodes, pageAccessibleEpisodes;
    public final List<String> episodeIds;
    public PublicDrama(String id, String title, int reported, int accessible, List<String> episodes) {
        this.seriesId = id; this.title = title; this.reportedEpisodes = reported;
        this.pageAccessibleEpisodes = accessible;
        this.episodeIds = Collections.unmodifiableList(new ArrayList<>(episodes));
    }
    /** Stable ID selection survives list reordering; absent selections start at the first row. */
    public int indexOfEpisode(String episodeId) {
        int index = episodeIds.indexOf(episodeId);
        return index < 0 ? 0 : index;
    }
    public enum State { PROTECTED, UNKNOWN, PUBLIC_CANDIDATE }
    public static final class Assessment {
        public final State state;
        public final String reason;
        public Assessment(State state, String reason) { this.state = state; this.reason = reason; }
    }
}
