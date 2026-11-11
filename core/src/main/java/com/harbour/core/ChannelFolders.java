package com.harbour.core;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Local user folders: membership never owns or deletes a channel. */
public final class ChannelFolders {
  public static final class Folder {
    public final String id;
    public String name;
    public final LinkedHashSet<String> members = new LinkedHashSet<>();
    private Folder(String id, String name) { this.id = id; this.name = name; }
  }
  private final LinkedHashMap<String, Folder> folders = new LinkedHashMap<>();
  public List<Folder> all() { return new ArrayList<>(folders.values()); }
  public Folder get(String id) { return folders.get(id); }
  private String validName(String name, String except) {
    String value = name == null ? "" : name.trim();
    if (value.isEmpty() || value.length() > 40 || value.matches(".*[\\p{Cntrl}].*"))
      throw new IllegalArgumentException("文件夾名稱需為 1 至 40 字");
    for (Folder f : folders.values())
      if (!f.id.equals(except) && f.name.equalsIgnoreCase(value))
        throw new IllegalArgumentException("已有同名文件夾");
    return value;
  }
  public Folder create(String name) {
    if (folders.size() >= 32) throw new IllegalArgumentException("最多建立 32 個文件夾");
    Folder f = new Folder(UUID.randomUUID().toString(), validName(name, null));
    folders.put(f.id, f); return f;
  }
  public void rename(String id, String name) { required(id).name = validName(name, id); }
  public void delete(String id) { folders.remove(id); }
  public void setMembers(String id, Collection<String> ids) {
    LinkedHashSet<String> checked = new LinkedHashSet<>();
    for (String value : ids) {
      if (value == null || value.isEmpty() || value.length() > 160 || value.matches(".*[\\p{Cntrl}].*"))
        throw new IllegalArgumentException("頻道識別資料錯誤");
      checked.add(value);
    }
    if (checked.size() > 1000) throw new IllegalArgumentException("文件夾頻道過多");
    Folder f = required(id); f.members.clear(); f.members.addAll(checked);
  }
  public void removeFromAll(Collection<String> ids) { for (Folder f : folders.values()) f.members.removeAll(ids); }
  private Folder required(String id) {
    Folder f = folders.get(id); if (f == null) throw new IllegalArgumentException("文件夾不存在"); return f;
  }
  private static String enc(String s) { return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8)); }
  private static String dec(String s) { return new String(Base64.getDecoder().decode(s), StandardCharsets.UTF_8); }
  public String encode() {
    StringBuilder out = new StringBuilder("SHUNPIN_FOLDERS_1\n");
    for (Folder f : folders.values()) {
      out.append(f.id).append('\t').append(enc(f.name));
      for (String id : f.members) out.append('\t').append(enc(id));
      out.append('\n');
    }
    return out.toString();
  }
  public static ChannelFolders decode(String saved) {
    ChannelFolders result = new ChannelFolders();
    if (saved == null || saved.isEmpty()) return result;
    if (saved.length() > 1024 * 1024 || !saved.startsWith("SHUNPIN_FOLDERS_1\n"))
      throw new IllegalArgumentException("文件夾儲存格式不正確");
    for (String line : saved.substring("SHUNPIN_FOLDERS_1\n".length()).split("\n")) {
      if (line.isEmpty()) continue;
      String[] fields = line.split("\t", -1);
      if (fields.length < 2 || result.folders.size() >= 32 || result.folders.containsKey(fields[0]))
        throw new IllegalArgumentException("文件夾儲存資料不完整");
      UUID.fromString(fields[0]);
      Folder f = new Folder(fields[0], result.validName(dec(fields[1]), null));
      result.folders.put(f.id, f);
      List<String> ids = new ArrayList<>();
      for (int i = 2; i < fields.length; i++) ids.add(dec(fields[i]));
      result.setMembers(f.id, ids);
    }
    return result;
  }
}
