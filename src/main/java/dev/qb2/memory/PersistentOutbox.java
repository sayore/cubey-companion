package dev.qb2.memory;

import com.google.gson.*;
import dev.qb2.client.config.Configuration;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Accessed only by the cognition executor. Never evicts unacknowledged thoughts/speech. */
public final class PersistentOutbox {
  public static final int CAPACITY = 512;
  private final Path file;
  private final Gson json = new Gson();
  private final List<Episode> pending = new ArrayList<>();

  public PersistentOutbox(Path file) throws IOException {
    this.file = file;
    if (Files.exists(file)) {
      if (Files.size(file) > 4_000_000) throw new IOException("Outbox exceeds capacity");
      var entries = json.fromJson(Files.readString(file), Episode[].class);
      if (entries != null) pending.addAll(Arrays.asList(entries));
    }
  }

  public void add(Episode entry) throws IOException {
    if (pending.size() >= CAPACITY) throw new IOException("Memory queue full; cognition paused");
    pending.add(entry);
    try {
      persist();
    } catch (IOException e) {
      pending.removeLast();
      throw e;
    }
  }

  public void drain(dev.qb2.memory.nerve.NerveMemory memory) throws Exception {
    while (!pending.isEmpty()) {
      memory.write(pending.getFirst());
      Episode first = pending.removeFirst();
      try {
        persist();
      } catch (IOException e) {
        pending.addFirst(first);
        throw e;
      }
    }
  }

  public int size() {
    return pending.size();
  }

  private void persist() throws IOException {
    Configuration.write(file, json.toJson(pending));
  }
}
