package com.harbour.llm;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.Properties;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Streaming, bounded private-file import. No URL/network/download functionality. */
public final class ModelFiles {
  public static final long MAX_BYTES = 768L * 1024 * 1024;
  public static final long STORAGE_RESERVE = 128L * 1024 * 1024;
  private ModelFiles() {}
  public static File model(File directory) {
    try {
      String name = metadata(directory).getProperty("file", "");
      if (name.matches("[a-f0-9]{64}\\.gguf")) return new File(directory, name);
    } catch (IOException ignored) { }
    return new File(directory, "no-imported-model.gguf");
  }
  interface PointerCommitter { void commit(Path source, Path target) throws IOException; }

  public static final class Imported {
    public final long size;
    public final String sha256;
    Imported(long size, String hash) { this.size = size; this.sha256 = hash; }
  }
  public static Imported importGguf(InputStream source, long declaredBytes, File directory, BooleanSupplier cancelled) throws Exception {
    return importGguf(source, declaredBytes, directory, cancelled,
        (from, to) -> Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING));
  }
  static Imported importGguf(InputStream source, long declaredBytes, File directory, BooleanSupplier cancelled,
      PointerCommitter commitPointer) throws Exception {
    if (source == null) throw new IOException("Unable to read selected document");
    if (declaredBytes > MAX_BYTES) throw new IOException("Model exceeds the 768 MiB limit");
    if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Could not create private model storage");
    if (directory.getUsableSpace() < STORAGE_RESERVE + Math.max(0, declaredBytes)) throw new IOException("Not enough private storage space");
    File temporary = File.createTempFile("import-", ".part", directory);
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    long count = 0;
    try {
      try (OutputStream target = new FileOutputStream(temporary)) {
        byte[] header = new byte[8];
        int read = 0;
        while (read < 8) {
          if (cancelled.getAsBoolean()) throw new CancellationException("Import cancelled");
          int n = source.read(header, read, 8 - read);
          if (n < 0) throw new IOException("Truncated GGUF header");
          if (n == 0) continue;
          read += n;
        }
        if (header[0] != 'G' || header[1] != 'G' || header[2] != 'U' || header[3] != 'F'
            || (header[4] != 2 && header[4] != 3) || header[5] != 0 || header[6] != 0 || header[7] != 0)
          throw new IOException("Not a supported GGUF v2/v3 file");
        target.write(header); digest.update(header); count = 8;
        byte[] buffer = new byte[65536];
        int n;
        while ((n = source.read(buffer)) != -1) {
          if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new CancellationException("Import cancelled");
          if (n == 0) continue;
          count += n;
          if (count > MAX_BYTES) throw new IOException("Model exceeds the 768 MiB limit");
          if (directory.getUsableSpace() < STORAGE_RESERVE + n) throw new IOException("Private storage reserve reached");
          target.write(buffer, 0, n); digest.update(buffer, 0, n);
        }
      }
      if (cancelled.getAsBoolean()) throw new CancellationException("Import cancelled");
      if (declaredBytes >= 0 && declaredBytes != count) throw new IOException("Document size changed or copy was truncated");
      byte[] hash = digest.digest(); StringBuilder hex = new StringBuilder();
      for (byte b : hash) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
      // The content file is immutable; the atomic metadata pointer is the only commit point.
      synchronized (ModelFiles.class) {
        File previous = model(directory);
        File content = new File(directory, hex + ".gguf");
        File metadataPart = File.createTempFile("metadata-", ".part", directory);
        boolean committed = false;
        try {
          Files.move(temporary.toPath(), content.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
          Properties metadata = new Properties();
          metadata.setProperty("bytes", Long.toString(count)); metadata.setProperty("sha256", hex.toString());
          metadata.setProperty("file", content.getName());
          try (OutputStream output = new FileOutputStream(metadataPart)) { metadata.store(output, "User-imported GGUF; hash is identity, not proof of trust"); }
          if (cancelled.getAsBoolean()) throw new CancellationException("Import cancelled");
          commitPointer.commit(metadataPart.toPath(), new File(directory, "model.properties").toPath());
          committed = true;
          if (!previous.equals(content)) previous.delete();
        } finally {
          metadataPart.delete();
          if (!committed && !previous.equals(content)) content.delete();
        }
      }
      return new Imported(count, hex.toString());
    } finally { Files.deleteIfExists(temporary.toPath()); }
  }
  public static Properties metadata(File directory) throws IOException {
    Properties data = new Properties(); File file = new File(directory, "model.properties");
    if (file.isFile()) try (InputStream input = new FileInputStream(file)) { data.load(input); }
    return data;
  }
  public static synchronized void delete(File directory) throws IOException {
    File old = model(directory);
    Files.deleteIfExists(new File(directory, "model.properties").toPath());
    Files.deleteIfExists(old.toPath());
  }
}
