package edu.neu.cs6650.assignment4.kvnode.service;

final class DelaySimulator {

  static final long WRITE_DELAY_MS = 200L;
  static final long READ_DELAY_MS = 50L;

  private DelaySimulator() {
  }

  static void sleepForWrite() {
    sleep(WRITE_DELAY_MS);
  }

  static void sleepForRead() {
    sleep(READ_DELAY_MS);
  }

  private static void sleep(long delayMs) {
    try {
      Thread.sleep(delayMs);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted during simulated node delay", ex);
    }
  }
}
