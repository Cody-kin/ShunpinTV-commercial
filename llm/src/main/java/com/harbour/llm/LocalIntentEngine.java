package com.harbour.llm;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Real CPU inference. Completion threads are worker threads; callers must dispatch UI updates. */
public final class LocalIntentEngine implements AutoCloseable {
  public static final int CONTEXT_TOKENS = 512, OUTPUT_TOKENS = 64, MAX_INPUT_CHARS = 160;
  public static final String LLAMA_COMMIT = "f477f795cb071baf5b8af6488378366a72034ac4";
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final long handle;
  private boolean busy, closed;
  private volatile boolean loaded;
  private final CompletableFuture<Void> terminated = new CompletableFuture<>();

  public LocalIntentEngine() {
    NativeBridge.requireRuntime();
    handle = NativeBridge.create();
    if (handle == 0) throw new IllegalStateException("Could not create native runtime");
  }
  public static boolean runtimeAvailable() {
    try { NativeBridge.requireRuntime(); return true; } catch (IllegalStateException unavailable) { return false; }
  }
  public synchronized boolean isBusy() { return busy; }
  public boolean isLoaded() { return loaded; }
  public CompletableFuture<String> load(File model) {
    if (model == null || !model.isFile() || model.length() < 8 || model.length() > ModelFiles.MAX_BYTES)
      throw new IllegalArgumentException("Choose a GGUF file within the supported size limit");
    return submit(120000, () -> {
      loaded = false;
      String description = new String(NativeBridge.load(handle, model.getAbsolutePath().getBytes(StandardCharsets.UTF_8),
          CONTEXT_TOKENS, Math.max(1, Math.min(2, Runtime.getRuntime().availableProcessors()))), StandardCharsets.UTF_8);
      loaded = true;
      return description;
    });
  }
  public CompletableFuture<String> generate(String input) {
    validateInput(input);
    if (!loaded) throw new IllegalStateException("Import and load a model first");
    return submit(15000, () -> new String(NativeBridge.generate(handle, input.getBytes(StandardCharsets.UTF_8), OUTPUT_TOKENS, true), StandardCharsets.UTF_8));
  }
  public CompletableFuture<Void> unload() {
    return submit(120000, () -> { NativeBridge.unload(handle); loaded = false; return null; });
  }
  private synchronized <T> CompletableFuture<T> submit(int timeoutMs, Callable<T> work) {
    if (closed) throw new IllegalStateException("Runtime is closed");
    if (busy) throw new IllegalStateException("A model operation is already running");
    busy = true;
    // Prepare before enqueue, while holding the same lock as cancel/close. A later cancel cannot be reset by the worker.
    NativeBridge.prepare(handle, timeoutMs);
    CompletableFuture<T> result = new CompletableFuture<>();
    worker.execute(() -> {
      T value = null; Throwable failure = null;
      try { value = work.call(); } catch (Throwable error) { failure = error; }
      synchronized (LocalIntentEngine.this) { busy = false; }
      if (failure == null) result.complete(value); else result.completeExceptionally(failure);
    });
    return result;
  }
  public synchronized void cancel() { if (!closed) NativeBridge.cancel(handle); }
  @Override public synchronized void close() {
    if (closed) return;
    closed = true;
    NativeBridge.cancel(handle);
    // Destruction runs behind any load/generate; never free model memory beneath an active native call.
    worker.execute(() -> {
      try { NativeBridge.destroy(handle); loaded = false; terminated.complete(null); }
      catch (Throwable error) { terminated.completeExceptionally(error); }
    });
    worker.shutdown();
  }
  public CompletableFuture<Void> termination() { return terminated; }
  public static void validateInput(String input) {
    if (input == null || input.trim().isEmpty() || input.length() > MAX_INPUT_CHARS)
      throw new IllegalArgumentException("Input must contain 1 to 160 characters");
    for (int i = 0; i < input.length(); i++) if (Character.isISOControl(input.charAt(i)))
      throw new IllegalArgumentException("Control characters are not supported");
  }
}
