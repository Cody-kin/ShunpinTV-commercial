package com.harbour.tv;

/** Conservative local observation; no network probes and no claim of future availability. */
final class ContinuousPlaybackCheck {
 private long start=-1, previousTime, previousPosition, advanced;
 private boolean recorded;
 void reset(){start=-1;advanced=0;recorded=false;}
 boolean sample(long now,long position,boolean playing){
  if(!playing){reset();return false;}
  if(start<0){start=previousTime=now;previousPosition=position;return false;}
  long elapsed=now-previousTime,delta=position-previousPosition;
  // Pauses, long scheduler gaps, seeks and stalled media do not count as continuous playback.
  if(elapsed<=0||elapsed>2500||delta<=0||delta>elapsed+2000){reset();return false;}
  advanced+=delta;previousTime=now;previousPosition=position;
  if(!recorded&&now-start>=60000&&advanced>=55000){recorded=true;return true;}
  return false;
 }
}
