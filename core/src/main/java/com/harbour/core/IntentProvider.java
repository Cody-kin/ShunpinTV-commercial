package com.harbour.core;

/**
 * Optional future server-side AI boundary. Default implementation is fully local. Do not send
 * source URLs, credentials, viewing history, or raw playlists to a model. Implementations must have
 * cancellation, a bounded timeout, explicit opt-in, and schema validation. Any model result must
 * still be resolved through SearchEngine against the current catalog.
 */
public interface IntentProvider {
  SearchEngine.Intent parse(String query);

  String disclosure();

  final class Local implements IntentProvider {
    private final SearchEngine engine = new SearchEngine();

    @Override
    public SearchEngine.Intent parse(String query) {
      return engine.parse(query);
    }

    @Override
    public String disclosure() {
      return "智能篩選（本地） · 未連接雲端 AI";
    }
  }
}
