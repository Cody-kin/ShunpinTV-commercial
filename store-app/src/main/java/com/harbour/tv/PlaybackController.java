package com.harbour.tv;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import androidx.media3.common.*;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import com.harbour.core.Catalog;
import com.harbour.core.RecoveryPolicy;
import java.time.Instant;
import java.util.*;

@UnstableApi
final class PlaybackController {
  interface Observer {
    void onStatus(String status);

    void onFirstFrame(long milliseconds);

    void onKeepScreenOn(boolean active);
  }

  final ExoPlayer player;
  private final Context appContext;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final RecoveryPolicy recovery = new RecoveryPolicy();
  private final Observer observer;
  private final android.content.SharedPreferences playbackEvidence;
  private List<Catalog.Source> sources = Collections.emptyList();
  private Catalog.Channel channel;
  private String territory;
  private int sourceIndex, generation;
  private long startedAt;
  private boolean closed, firstFrame, terminal, desiredPlay;
  private Runnable timeout, pendingRetry, rightsExpiry, evidenceTick;
  private final ContinuousPlaybackCheck continuousCheck = new ContinuousPlaybackCheck();

  private void beginPlaybackObservation(Catalog.Source source) {
    if(evidenceTick!=null)handler.removeCallbacks(evidenceTick);
    continuousCheck.reset();
    final int expected=generation;
    evidenceTick=new Runnable(){public void run(){
      if(closed||terminal||generation!=expected)return;
      Timeline timeline=player.getCurrentTimeline();
      long position=player.getCurrentPosition();
      if(!timeline.isEmpty())position+=timeline.getWindow(player.getCurrentMediaItemIndex(),new Timeline.Window()).positionInFirstPeriodUs/1000;
      if(continuousCheck.sample(SystemClock.elapsedRealtime(),position,
          firstFrame&&player.isPlaying()&&player.getPlaybackState()==Player.STATE_READY&&!channel.isDemo())) {
        // A later outage gets a fresh bounded retry budget only after sustained recovery.
        recovery.reset();
        SourceManager.recordContinuousPlayback(appContext,source.uri.toString());
        android.util.Log.i("ShunpinPlayback","continuousPlaybackVerified channel="+channel.id+" seconds=60");
      }
      handler.postDelayed(this,1000);
    }};
    handler.postDelayed(evidenceTick,1000);
  }


  PlaybackController(Context context, Observer observer) {
    this.observer = observer;
    appContext = context.getApplicationContext();
    playbackEvidence = context.getSharedPreferences("harbour", Context.MODE_PRIVATE);
    player =
        new ExoPlayer.Builder(context)
            .setLoadControl(
                new DefaultLoadControl.Builder()
                    .setBufferDurationsMs(5000, 30000, 1000, 2000)
                    .build())
            .build();
    player.setAudioAttributes(
        new AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build(),
        true);
    player.setHandleAudioBecomingNoisy(true);
    player.addListener(
        new Player.Listener() {
          @Override
          public void onPlaybackStateChanged(int state) {
            playbackEvidence.edit().putInt("renderer_playback_state", state).apply();
            updateScreenOn();
            if (closed || terminal) return;
            if (state != Player.STATE_READY) continuousCheck.reset();
            if (state == Player.STATE_BUFFERING) {
              observer.onStatus(desiredPlay ? "正在緩衝…" : "已暫停 · 按 OK 開啟選單繼續");
              if (desiredPlay) armWatchdog();
            } else if (state == Player.STATE_READY) {
              cancelWatchdog();
              observer.onStatus(
                  !desiredPlay
                      ? "已暫停 · 按 OK 開啟選單繼續"
                      : channel != null && channel.isDemo() ? "本機循環 · 非直播" : "正在播放 · 線路 "+(sourceIndex+1)+"／"+sources.size());
            } else if (state == Player.STATE_ENDED) {
              terminal = true;
              cancelPending();
              observer.onStatus("播放已結束，按 OK 開啟選單重新連線");
            }
          }

          @Override
          public void onPlayWhenReadyChanged(boolean ready, int reason) {
            if(!ready)continuousCheck.reset();
            updateScreenOn();
          }

          @Override
          public void onPositionDiscontinuity(Player.PositionInfo oldPosition, Player.PositionInfo newPosition, int reason) {
            continuousCheck.reset();
          }

          @Override
          public void onRenderedFirstFrame() {
            if (closed) return;
            long count = playbackEvidence.getLong("rendered_frame_count", 0) + 1;
            long elapsed = SystemClock.elapsedRealtime() - startedAt;
            playbackEvidence.edit().putLong("rendered_frame_count", count).apply();
            android.util.Log.i("ShunpinPlayback", "renderedFrame count=" + count + " elapsedMs=" + elapsed);
            if (!firstFrame && !closed) {
              firstFrame = true;
              if(channel!=null)new LineHealthStore(appContext).channelFailed(channel.id,false);
              if (desiredPlay && player.getPlaybackState() == Player.STATE_BUFFERING) armWatchdog();
              observer.onFirstFrame(SystemClock.elapsedRealtime() - startedAt);
            }
          }

          @Override
          public void onPlayerError(PlaybackException error) {
            StringBuilder causes = new StringBuilder();
            Throwable cause = error;
            for (int depth = 0; cause != null && depth < 8; depth++, cause = cause.getCause()) {
              if (depth > 0) causes.append(" -> ");
              causes.append(cause.getClass().getName());
            }
            android.util.Log.w("ShunpinPlayback", "playerError code=" + error.errorCode
                + " name=" + error.getErrorCodeName() + " causes=" + causes);
            if (closed || terminal) return;
            continuousCheck.reset();
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
              recoverLiveWindow();
              return;
            }
            int httpStatus = 0;
            for (Throwable t = error; t != null; t = t.getCause()) {
              if (t instanceof HttpDataSource.InvalidResponseCodeException)
                httpStatus = ((HttpDataSource.InvalidResponseCodeException) t).responseCode;
            }
            PlaybackFailure failure = PlaybackFailure.classify(error.errorCode, httpStatus, error);
            fail(failure.fault, failure.message);
          }
        });
  }

  private void updateScreenOn() {
    int state = player.getPlaybackState();
    observer.onKeepScreenOn(
        !closed
            && !terminal
            && player.getPlayWhenReady()
            && (state == Player.STATE_READY || state == Player.STATE_BUFFERING));
  }

  void start(Catalog.Channel channel, String territory) {
    start(channel, territory, true);
  }

  void start(Catalog.Channel channel, String territory, boolean playWhenReady) {
    cancelPending();
    generation++;
    terminal = false;
    desiredPlay = playWhenReady;
    this.channel = channel;
    this.territory = territory;
    player.stop();
    player.clearMediaItems();
    sources = channel.eligible(territory, Instant.now());
    sourceIndex = 0;
    recovery.reset();
    if (sources.isEmpty()) {
      terminal = true;
      observer.onStatus("此地區沒有可用的播放來源；請檢查地區、授權與有效期");
      return;
    }
    prepareSource();
  }

  private void prepareSource() {
    if (closed || terminal) return;
    Catalog.Source source = sources.get(sourceIndex);
    String blocked = source.blockedReason(territory, Instant.now());
    if (blocked != null) {
      terminal = true;
      observer.onStatus(blocked);
      player.stop();
      return;
    }
    firstFrame = false;
    startedAt = SystemClock.elapsedRealtime();
    if (rightsExpiry != null) handler.removeCallbacks(rightsExpiry);
    final int expected = generation;
    rightsExpiry =
        () -> {
          if (!closed && generation == expected) {
            stop();
            observer.onStatus("來源授權已到期，播放已停止");
          }
        };
    handler.postDelayed(
        rightsExpiry,
        Math.max(1, java.time.Duration.between(Instant.now(), source.validUntil).toMillis()));
    MediaItem.Builder item =
        new MediaItem.Builder()
            .setMediaId(channel.id)
            .setUri(source.uri)
            .setMimeType(source.mimeType)
            .setMediaMetadata(new MediaMetadata.Builder().setTitle(channel.name).build());
    // Respect manifest/server live timing. A universal 3-second override can place
    // ordinary HLS too close to the live edge, overriding its hold-back guidance.
    player.setRepeatMode(channel.isDemo() ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
    final String sourceTerritory = territory;
    final RestrictedPlaybackDataSource.ManifestRedirects redirects=new RestrictedPlaybackDataSource.ManifestRedirects(source.uri);
    player.setMediaSource(
        new androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
            () -> new RestrictedPlaybackDataSource(appContext, source, sourceTerritory, redirects))
            .setLoadErrorHandlingPolicy(new androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy(0) {
              @Override public long getRetryDelayMsFor(androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo info) {
                // One recovery owner: surface failures immediately to the bounded source policy.
                return C.TIME_UNSET;
              }
            }).createMediaSource(item.build()));
    player.setPlayWhenReady(desiredPlay);
    player.prepare();
    beginPlaybackObservation(source);
    if (desiredPlay) armWatchdog();
    observer.onStatus("正在準備播放…");
  }

  private void recoverLiveWindow() {
    cancelWatchdog();
    RecoveryPolicy.Decision decision = recovery.onFailure(RecoveryPolicy.Fault.LIVE_WINDOW, false);
    if (decision.action != RecoveryPolicy.Action.SEEK_LIVE) {
      terminal = true;
      cancelPending();
      player.stop();
      observer.onStatus("直播時間窗持續失效；已停止重試，請更新來源或手動重新連線。");
      return;
    }
    // Media3's documented recovery keeps the same source and all access checks intact.
    player.seekToDefaultPosition();
    player.setPlayWhenReady(desiredPlay);
    player.prepare();
    observer.onStatus("已落後於直播時間窗；正在返回目前直播位置…");
    if (desiredPlay) armWatchdog();
  }

  private void fail(RecoveryPolicy.Fault fault) { fail(fault, null); }

  private void fail(RecoveryPolicy.Fault fault, String detail) {
    cancelPending();
    RecoveryPolicy.Decision decision = recovery.onFailure(fault, sourceIndex + 1 < sources.size());
    if (decision.action == RecoveryPolicy.Action.STOP) {
      if(channel!=null)new LineHealthStore(appContext).channelFailed(channel.id,true);
      terminal = true;
      player.stop();
      observer.onStatus(
          detail != null ? detail : fault == RecoveryPolicy.Fault.ACCESS_DENIED
              ? "來源拒絕存取；請確認地區與授權，不會繞過限制"
              : fault == RecoveryPolicy.Fault.DRM ? "內容授權或 DRM 驗證失敗" : "播放中斷。按 OK 開啟選單，選「重新連線」");
      return;
    }
    final int expected = generation;
    if (decision.action == RecoveryPolicy.Action.NEXT_SOURCE) {
      sourceIndex++;
      recovery.reset();
      observer.onStatus("線路失效，正在切換 "+(sourceIndex+1)+"／"+sources.size()+"…");
    } else observer.onStatus("連線中斷；" + (decision.delayMs / 1000) + " 秒後重試");
    player.stop();
    pendingRetry =
        () -> {
          pendingRetry = null;
          if (!closed && generation == expected && !terminal) prepareSource();
        };
    if (desiredPlay) handler.postDelayed(pendingRetry, decision.delayMs);
  }

  private void armWatchdog() {
    cancelWatchdog();
    final int expected = generation;
    final boolean startup = !firstFrame;
    final long armedAt = SystemClock.elapsedRealtime();
    final long timeoutMs = startup ? BuildConfig.STARTUP_TIMEOUT_MS : 15000L;
    timeout =
        () -> {
          timeout = null;
          if (!closed
              && !terminal
              && generation == expected
              && player.getPlaybackState() == Player.STATE_BUFFERING) {
            android.util.Log.w("ShunpinPlayback", "watchdog phase=" + (startup ? "startup" : "rebuffer")
                + " elapsedMs=" + (SystemClock.elapsedRealtime() - armedAt));
            fail(RecoveryPolicy.Fault.TRANSIENT);
          }
        };
    handler.postDelayed(timeout, timeoutMs);
  }

  private void cancelWatchdog() {
    if (timeout != null) handler.removeCallbacks(timeout);
    timeout = null;
  }

  private void cancelPending() {
    if(evidenceTick!=null)handler.removeCallbacks(evidenceTick);
    evidenceTick=null;continuousCheck.reset();
    cancelWatchdog();
    if (rightsExpiry != null) handler.removeCallbacks(rightsExpiry);
    rightsExpiry = null;
    if (pendingRetry != null) handler.removeCallbacks(pendingRetry);
    pendingRetry = null;
  }

  boolean isTerminal() {
    return terminal;
  }

  boolean wantsPlayback() {
    return desiredPlay;
  }

  void setPaused(boolean paused) {
    if (closed || terminal) return;
    desiredPlay = !paused;
    if (paused) {
      cancelWatchdog();
      if (pendingRetry != null) handler.removeCallbacks(pendingRetry);
      pendingRetry = null;
      player.pause();
      observer.onStatus("已暫停 · 按 OK 開啟選單繼續");
    } else {
      if (pendingRetry != null) handler.removeCallbacks(pendingRetry);
      pendingRetry = null;
      if (sources.isEmpty()) return;
      String blocked = sources.get(sourceIndex).blockedReason(territory, Instant.now());
      if (blocked != null) {
        stop();
        observer.onStatus(blocked);
        return;
      }
      if (player.getPlaybackState() == Player.STATE_IDLE) prepareSource();
      else {
        player.play();
        if (player.getPlaybackState() == Player.STATE_BUFFERING) armWatchdog();
        observer.onStatus(channel.isDemo() ? "本機循環 · 非直播" : "正在播放");
      }
    }
  }

  void stop() {
    generation++;
    terminal = true;
    observer.onKeepScreenOn(false);
    cancelPending();
    player.stop();
    player.clearMediaItems();
  }

  void close() {
    closed = true;
    observer.onKeepScreenOn(false);
    generation++;
    cancelPending();
    player.release();
  }
}
