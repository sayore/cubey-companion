package dev.qb2.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;

/** Local desktop configuration; secrets are deliberately stored separately. */
public final class Configuration {
  private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
  private final Path directory;
  private Settings settings = new Settings();
  private String apiKey = "", nerveKey = "", fallbackApiKey = "";

  public Configuration(Path directory) {
    this.directory = directory;
  }

  public void load() throws IOException {
    Files.createDirectories(directory);
    Path config = directory.resolve("qb2.json");
    if (Files.exists(config)) settings = JSON.fromJson(Files.readString(config), Settings.class);
    if (settings == null) throw new IOException("Invalid QB2 configuration");
    settings.validate();
    Path secrets = directory.resolve("qb2-secrets.json");
    if (Files.exists(secrets)) {
      var data = JSON.fromJson(Files.readString(secrets), Secrets.class);
      apiKey = data.apiKey() == null ? "" : data.apiKey();
      nerveKey = data.nerveKey() == null ? "" : data.nerveKey();
      fallbackApiKey = data.fallbackApiKey() == null ? "" : data.fallbackApiKey();
    }
  }

  public Settings settings() {
    return settings;
  }

  public String apiKey() {
    return apiKey;
  }

  public String nerveKey() {
    return nerveKey;
  }

  public String fallbackApiKey() {
    return fallbackApiKey;
  }

  public void save(Settings next, String key, String memoryKey) throws IOException {
    save(next, key, memoryKey, fallbackApiKey);
  }

  public void save(Settings next, String key, String memoryKey, String fallbackKey)
      throws IOException {
    next.validate();
    String safeKey = key == null ? "" : key;
    String safeMemoryKey = memoryKey == null ? "" : memoryKey;
    String safeFallbackKey = fallbackKey == null ? "" : fallbackKey;
    write(directory.resolve("qb2.json"), JSON.toJson(next));
    write(
        directory.resolve("qb2-secrets.json"),
        JSON.toJson(new Secrets(safeKey, safeMemoryKey, safeFallbackKey)));
    settings = next;
    apiKey = safeKey;
    nerveKey = safeMemoryKey;
    fallbackApiKey = safeFallbackKey;
  }

  public static void write(Path file, String text) throws IOException {
    Files.createDirectories(file.toAbsolutePath().getParent());
    Path tmp = Files.createTempFile(file.toAbsolutePath().getParent(), ".qb2-", ".tmp");
    try {
      try {
        Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
      } catch (UnsupportedOperationException ignored) {
        /* Windows uses inherited ACLs. */
      }
      Files.writeString(tmp, text);
      Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(tmp);
    }
  }

  private record Secrets(String apiKey, String nerveKey, String fallbackApiKey) {}
}
