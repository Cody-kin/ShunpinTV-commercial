package com.harbour.core;

import java.util.*;
public final class ChannelFoldersTests {
  public static void main(String[] args) {
    ChannelFolders f = new ChannelFolders();
    String family = f.create("家人 · 新聞").id, sport = f.create("運動").id;
    f.setMembers(family, Arrays.asList("preset-cctv-1", "user-123", "user-123"));
    f.setMembers(sport, Collections.singletonList("user-123"));
    ChannelFolders restored = ChannelFolders.decode(f.encode());
    assert restored.get(family).name.equals("家人 · 新聞");
    assert restored.get(family).members.size() == 2;
    restored.setMembers(family, Collections.singletonList("preset-cctv-1"));
    assert restored.get(sport).members.contains("user-123");
    restored.rename(family, "新聞台");
    try { restored.rename(sport, "新聞台"); throw new AssertionError(); } catch (IllegalArgumentException expected) { }
    restored.removeFromAll(Collections.singleton("user-123"));
    assert restored.get(sport).members.isEmpty();
    restored.delete(sport);
    assert restored.get(family).members.contains("preset-cctv-1");
    assert ChannelFolders.decode(restored.encode()).all().size() == 1;
    try { ChannelFolders.decode("broken"); throw new AssertionError(); } catch (IllegalArgumentException expected) { }
    try { restored.create(" "); throw new AssertionError(); } catch (IllegalArgumentException expected) { }
    System.out.println("Channel folder persistence/membership/delete boundary checks passed");
  }
}
