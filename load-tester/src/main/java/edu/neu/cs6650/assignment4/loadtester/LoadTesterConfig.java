package edu.neu.cs6650.assignment4.loadtester;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public record LoadTesterConfig(
    String target,
    String writeTarget,
    List<String> readTargets,
    int threads,
    int durationSeconds,
    double writeRatio,
    int keySpace,
    boolean quiet
) {

  public LoadTesterConfig {
    if (readTargets == null || readTargets.isEmpty()) {
      throw new IllegalArgumentException("At least one read target is required");
    }
    if (keySpace <= 0) {
      throw new IllegalArgumentException("key-space must be positive");
    }
    if (writeRatio < 0.0 || writeRatio > 1.0) {
      throw new IllegalArgumentException("write-ratio must be between 0.0 and 1.0");
    }
    readTargets = List.copyOf(readTargets);
  }

  public static LoadTesterConfig fromArgs(String[] args) {
    Map<String, String> parsed = parseArgs(args);
    String target = parsed.getOrDefault("target", "http://localhost:8080");
    String writeTarget = parsed.getOrDefault("write-target", target);
    List<String> readTargets = parseCsv(parsed.getOrDefault("read-targets", target));
    int threads = Integer.parseInt(parsed.getOrDefault("threads", "32"));
    int durationSeconds = Integer.parseInt(parsed.getOrDefault("duration", "60"));
    double writeRatio = Double.parseDouble(parsed.getOrDefault("write-ratio", "0.1"));
    int keySpace = Integer.parseInt(parsed.getOrDefault("key-space", "100"));
    boolean quiet = Boolean.parseBoolean(parsed.getOrDefault("quiet", "false"));
    return new LoadTesterConfig(
        target, writeTarget, readTargets, threads, durationSeconds, writeRatio, keySpace, quiet);
  }

  private static Map<String, String> parseArgs(String[] args) {
    Map<String, String> values = new HashMap<>();
    for (int i = 0; i < args.length; i++) {
      String arg = args[i];
      if (!arg.startsWith("--")) {
        continue;
      }
      String key = arg.substring(2);
      if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
        values.put(key, args[i + 1]);
        i++;
      } else {
        values.put(key, "true");
      }
    }
    return values;
  }

  private static List<String> parseCsv(String raw) {
    return Arrays.stream(raw.split(","))
        .map(String::trim)
        .filter(value -> !value.isEmpty())
        .toList();
  }
}
