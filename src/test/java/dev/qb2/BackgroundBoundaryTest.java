package dev.qb2;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class BackgroundBoundaryTest {
  @Test
  void cognitionMemoryAndInferenceCannotDependOnMinecraftHostApis() throws Exception {
    for (String domain : List.of("cognition", "memory", "inference")) {
      try (var files = Files.walk(Path.of("src/main/java/dev/qb2", domain))) {
        for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
          String source = Files.readString(file);
          assertFalse(
              source.contains("net.minecraft.") || source.contains("net.neoforged."),
              "Background domain imports a game host API: " + file);
        }
      }
    }
  }
}
