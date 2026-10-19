package edu.neu.cs6650.assignment4.loadtester;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class LoadTesterConfigTest {

  @Test
  void defaultsUseTargetForReadsAndWrites() {
    LoadTesterConfig config = LoadTesterConfig.fromArgs(new String[] {});

    assertEquals("http://localhost:8080", config.target());
    assertEquals("http://localhost:8080", config.writeTarget());
    assertEquals(List.of("http://localhost:8080"), config.readTargets());
    assertEquals(100, config.keySpace());
    assertFalse(config.quiet());
  }

  @Test
  void parsesLeaderFollowerReadAndWriteTargets() {
    LoadTesterConfig config = LoadTesterConfig.fromArgs(new String[] {
        "--target", "http://alb",
        "--write-target", "http://leader:8080",
        "--read-targets", "http://node1:8080,http://node2:8080,http://node3:8080",
        "--key-space", "250",
        "--write-ratio", "0.5",
        "--quiet"
    });

    assertEquals("http://alb", config.target());
    assertEquals("http://leader:8080", config.writeTarget());
    assertEquals(
        List.of("http://node1:8080", "http://node2:8080", "http://node3:8080"),
        config.readTargets());
    assertEquals(250, config.keySpace());
    assertEquals(0.5, config.writeRatio());
    assertTrue(config.quiet());
  }
}
