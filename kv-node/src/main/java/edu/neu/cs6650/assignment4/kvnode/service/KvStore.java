package edu.neu.cs6650.assignment4.kvnode.service;

import edu.neu.cs6650.assignment4.kvnode.model.KVEntry;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

@Component
public class KvStore {

  private final ConcurrentHashMap<String, KVEntry> store = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, AtomicInteger> versions = new ConcurrentHashMap<>();

  public KVEntry putWithNextVersion(String key, String value) {
    AtomicInteger versionCounter = versions.computeIfAbsent(key, unused -> new AtomicInteger(0));
    int nextVersion = versionCounter.incrementAndGet();
    KVEntry newEntry = new KVEntry(value, nextVersion);
    store.put(key, newEntry);
    return newEntry;
  }

  public KVEntry putWithVersion(String key, String value, int version) {
    versions.compute(
        key,
        (unused, current) -> {
          if (current == null) {
            return new AtomicInteger(version);
          }
          current.updateAndGet(existing -> Math.max(existing, version));
          return current;
        });

    KVEntry newEntry = new KVEntry(value, version);
    return store.compute(
        key,
        (unused, current) -> {
          if (current != null && current.version() > version) {
            return current;
          }
          return newEntry;
        });
  }

  public Optional<KVEntry> get(String key) {
    return Optional.ofNullable(store.get(key));
  }
}
