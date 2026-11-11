package com.harbour.llm;

final class NativeBridge {
  private static final Throwable LOAD_ERROR;
  static {
    Throwable problem = null;
    try { System.loadLibrary("shunpin_llm"); } catch (LinkageError | SecurityException error) { problem = error; }
    LOAD_ERROR = problem;
  }
  static void requireRuntime() {
    if (LOAD_ERROR != null) throw new IllegalStateException("Native llama.cpp runtime is not available in this APK / ABI", LOAD_ERROR);
  }
  static native long create();
  static native void prepare(long handle, int timeoutMs);
  static native void cancel(long handle);
  static native byte[] load(long handle, byte[] path, int context, int threads);
  static native byte[] generate(long handle, byte[] input, int maxTokens, boolean chat);
  static native void unload(long handle);
  static native void destroy(long handle);
}
