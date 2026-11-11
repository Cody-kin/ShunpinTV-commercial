package com.harbour.tv;
/** Counts actual rebuffer durations, excluding initial start and intentional pauses. */
final class RebufferWindow {
 private long windowStart=-1, started=-1, total; private int count;
 void reset(){windowStart=-1;started=-1;total=0;count=0;}
 void buffering(long now){if(started>=0)return;if(windowStart<0||now-windowStart>60000){reset();windowStart=now;}started=now;}
 boolean ready(long now){if(started<0)return false;long duration=Math.max(0,now-started);started=-1;if(duration<500)return false;total+=duration;count++;return count>=3&&total>=6000;}
}
