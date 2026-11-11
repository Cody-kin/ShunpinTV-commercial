package com.harbour.core;
import java.time.*;
public final class CctvGuideTests {
 private static int checks;
 private static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
 public static void main(String[] args){
  Instant now=Instant.parse("2026-11-08T08:00:00Z");LocalDate today=LocalDate.of(2026,11,8);
  check(CctvGuide.allowedDate(today,now),"today");check(CctvGuide.allowedDate(today.minusDays(6),now),"oldest date");
  check(!CctvGuide.allowedDate(today.minusDays(7),now),"expired date");check(!CctvGuide.allowedDate(today.plusDays(1),now),"future date");
  check(CctvGuide.CHANNELS.size()==20,"20 verified keys");check(!CctvGuide.CHANNELS.contains("cctv9"),"no guessed alias");
  CctvGuide.Programme past=new CctvGuide.Programme("Programme",1794070890L,1794073530L);
  check(past.canReplay(now),"past real sample");check(past.replayUrl("cctv1",now).endsWith("stime=1794070890&etime=1794073530&type=lbacks"),"official route");
  check(!past.canReplay(now.plusSeconds(7*86400)),"expired programme");
  CctvGuide.Programme live=new CctvGuide.Programme("Live",now.getEpochSecond()-100,now.getEpochSecond()+100);
  check(live.isLive(now)&&!live.canReplay(now),"ongoing routes live");
  CctvGuide.Programme future=new CctvGuide.Programme("Future",now.getEpochSecond()+100,now.getEpochSecond()+200);
  check(!future.isLive(now)&&!future.canReplay(now),"future disabled");
  try{future.replayUrl("cctv1",now);throw new AssertionError("future route");}catch(IllegalArgumentException ok){checks++;}
  try{past.replayUrl("unknown",now);throw new AssertionError("unknown route");}catch(IllegalArgumentException ok){checks++;}
  try{new CctvGuide.Programme("Invalid",100,99);throw new AssertionError("reversed times");}catch(IllegalArgumentException ok){checks++;}
  System.out.println("CCTV guide: "+checks+" checks passed");
 }
}
