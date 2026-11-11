package com.harbour.core;
/** Ad-adapter contract for a FUTURE authorized cue provider. No inference, SDK or ad requests. */
public final class AdBreakSession {
 public enum Action { KEEP_CONTENT, START_AD, KEEP_AD, STOP_AD_RETURN_LIVE }
 private boolean active;private long endMs;
 public Action cue(boolean replacementAuthorized,long startMs,long endMs,long nowMs) {
  if(active)return tick(nowMs);
  if(!replacementAuthorized||startMs>nowMs||endMs<=nowMs||endMs<=startMs||endMs-startMs>600000)return Action.KEEP_CONTENT;
  this.endMs=endMs;active=true;return Action.START_AD;
 }
 public Action tick(long nowMs){if(!active)return Action.KEEP_CONTENT;if(nowMs>=endMs){active=false;return Action.STOP_AD_RETURN_LIVE;}return Action.KEEP_AD;}
 /** An early end, error or lost cue connection must never leave the stream hidden. */
 public Action completeOrFail(){if(!active)return Action.KEEP_CONTENT;active=false;return Action.STOP_AD_RETURN_LIVE;}
}
