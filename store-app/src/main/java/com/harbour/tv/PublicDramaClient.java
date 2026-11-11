package com.harbour.tv;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.function.LongSupplier;

/** Explicit official HTML only, with a whole-operation deadline and active cancellation. */
public final class PublicDramaClient {
    private PublicDramaClient() {}
    public static final long TOTAL_TIMEOUT_MS = 30000;
    interface ConnectionFactory { HttpURLConnection open(String url) throws Exception; }
    public static final class Request {
        private volatile boolean cancelled;
        private volatile HttpURLConnection active;
        public synchronized void cancel() {
            cancelled = true;
            HttpURLConnection c = active;
            if (c != null) c.disconnect();
        }
        private synchronized void attach(HttpURLConnection c) throws InterruptedIOException {
            if (cancelled) { c.disconnect(); throw new InterruptedIOException("已取消"); }
            active = c;
        }
        private void check() throws InterruptedIOException {
            if (cancelled || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("已取消");
        }
    }
    public static String read(String page) throws Exception { return read(page, new Request()); }
    public static String read(String page, Request request) throws Exception {
        return read(page, request, url -> (HttpURLConnection) URI.create(url).toURL().openConnection(),
                System::nanoTime, TOTAL_TIMEOUT_MS);
    }
    static String read(String page, Request request, ConnectionFactory factory, LongSupplier clock, long timeoutMs) throws Exception {
        String next = PublicDramaParser.checkedPage(page);
        long started = clock.getAsLong();
        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "public-html-deadline"); thread.setDaemon(true); return thread;
        });
        ScheduledFuture<?> expiry = timer.schedule(request::cancel, timeoutMs, TimeUnit.MILLISECONDS);
        try {
            for (int redirect = 0; redirect <= 3; redirect++) {
                int remaining = remaining(request, clock, started, timeoutMs);
                HttpURLConnection c = factory.open(next);
                request.attach(c);
                try {
                    c.setInstanceFollowRedirects(false);
                    c.setConnectTimeout(Math.min(12000, remaining)); c.setReadTimeout(Math.min(12000, remaining));
                    c.setRequestProperty("Accept", "text/html");
                    c.setRequestProperty("User-Agent", "ShunpinTV-PublicMetadata/1.0");
                    int code = c.getResponseCode();
                    remaining(request, clock, started, timeoutMs);
                    if (code >= 300 && code < 400) {
                        String location = c.getHeaderField("Location");
                        if (location == null) throw new java.io.IOException("官網跳轉缺少地址");
                        String target = PublicDramaParser.checkedPage(URI.create(next).resolve(location).toString());
                        if (!PublicDramaParser.seriesId(page).equals(PublicDramaParser.seriesId(target)))
                            throw new java.io.IOException("官網跳轉到其他劇集");
                        next = target; continue;
                    }
                    if (code != 200) throw new java.io.IOException("官網回應 HTTP " + code);
                    String type = c.getContentType();
                    if (type == null || !type.toLowerCase(java.util.Locale.ROOT).startsWith("text/html"))
                        throw new java.io.IOException("官網未返回 HTML");
                    try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                        byte[] buffer = new byte[8192];
                        while (true) {
                            c.setReadTimeout(Math.min(12000, remaining(request, clock, started, timeoutMs)));
                            int n = in.read(buffer);
                            remaining(request, clock, started, timeoutMs);
                            if (n == -1) break;
                            if (out.size() + n > PublicDramaParser.MAX_HTML_BYTES) throw new java.io.IOException("官網頁面超過 2 MiB");
                            out.write(buffer, 0, n);
                        }
                        return new String(out.toByteArray(), StandardCharsets.UTF_8);
                    }
                } finally { c.disconnect(); request.active = null; }
            }
            throw new java.io.IOException("官網跳轉次數過多");
        } finally { expiry.cancel(false); timer.shutdownNow(); }
    }
    private static int remaining(Request request, LongSupplier clock, long started, long limit) throws InterruptedIOException {
        request.check();
        long elapsed = TimeUnit.NANOSECONDS.toMillis(clock.getAsLong() - started);
        if (elapsed >= limit) throw new java.net.SocketTimeoutException("公開頁面整次讀取逾時");
        return (int) Math.max(1, limit - elapsed);
    }
}
