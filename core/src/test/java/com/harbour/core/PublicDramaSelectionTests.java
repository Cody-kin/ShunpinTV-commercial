package com.harbour.core;
import java.util.Arrays;
import java.util.Collections;
public final class PublicDramaSelectionTests {
 public static void main(String[] args) {
  PublicDrama a=new PublicDrama("series","title",3,0,Arrays.asList("111111","222222","333333"));
  check(a.indexOfEpisode("222222")==1);check(a.indexOfEpisode(null)==0);check(a.indexOfEpisode("missing")==0);
  PublicDrama reordered=new PublicDrama("series","title",3,0,Arrays.asList("333333","111111","222222"));
  check(reordered.indexOfEpisode("222222")==2);
  check(new PublicDrama("series","title",0,0,Collections.emptyList()).indexOfEpisode("222222")==0);
  check(a.pageAccessibleEpisodes==0);System.out.println("6 episode selection checks passed; no playback claim");
 }
 private static void check(boolean yes){if(!yes)throw new AssertionError();}
}
