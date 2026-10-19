package edu.neu.cs6650.assignment4.loadtester.model;

public record RequestResult(
    String type,
    String key,
    long latencyMs,
    int version,
    boolean isStale,
    long timestamp
) {
}
