package edu.neu.cs6650.assignment4.kvnode.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Binds kv.* properties from application.yml / environment variables.
 *
 * Environment variables are mapped by Spring Boot's relaxed binding:
 *   ROLE               → kv.role
 *   FOLLOWER_URLS      → kv.follower-urls   (comma-separated string)
 *   PEER_URLS          → kv.peer-urls        (comma-separated string)
 *   WRITE_QUORUM_SIZE  → kv.write-quorum-size
 *   READ_QUORUM_SIZE   → kv.read-quorum-size
 *
 * NOTE: Spring Boot's relaxed binding does NOT automatically split a
 * comma-separated env-var string into a List<String>.  The setters here
 * handle both cases:
 *   - Spring injecting a real List  (from application.yml)
 *   - Spring injecting a single String containing commas (from env vars)
 *
 * Register this class with @EnableConfigurationProperties(ClusterProperties.class)
 * in AppConfig (or on the main application class).
 */
@ConfigurationProperties(prefix = "kv")
public class ClusterProperties {

    private String role = "leader";
    private List<String> followerUrls = List.of();
    private List<String> peerUrls = List.of();
    private int writeQuorumSize = 5;
    private int readQuorumSize = 1;

    // -------------------------------------------------------------------------
    // Getters
    // -------------------------------------------------------------------------

    public String getRole() { return role; }

    public List<String> getFollowerUrls() { return followerUrls; }

    public List<String> getPeerUrls() { return peerUrls; }

    public int getWriteQuorumSize() { return writeQuorumSize; }

    public int getReadQuorumSize() { return readQuorumSize; }

    // -------------------------------------------------------------------------
    // Setters — parse comma-separated strings produced by env vars
    // -------------------------------------------------------------------------

    public void setRole(String role) {
        this.role = role == null ? "leader" : role.trim();
    }

    public void setFollowerUrls(List<String> followerUrls) {
        this.followerUrls = sanitizeUrls(followerUrls);
    }

    /**
     * Called by Spring when FOLLOWER_URLS is a plain comma-separated string.
     * Spring's relaxed binding uses the List setter when it can, but falls
     * back to a String setter when the value is not a YAML sequence.
     */
    public void setFollowerUrlsAsString(String raw) {
        this.followerUrls = splitAndSanitize(raw);
    }

    public void setPeerUrls(List<String> peerUrls) {
        this.peerUrls = sanitizeUrls(peerUrls);
    }

    public void setPeerUrlsAsString(String raw) {
        this.peerUrls = splitAndSanitize(raw);
    }

    public void setWriteQuorumSize(int writeQuorumSize) {
        this.writeQuorumSize = writeQuorumSize;
    }

    public void setReadQuorumSize(int readQuorumSize) {
        this.readQuorumSize = readQuorumSize;
    }

    // -------------------------------------------------------------------------
    // Convenience predicates
    // -------------------------------------------------------------------------

    /** True when PEER_URLS is set — the node operates in Leaderless mode. */
    public boolean isLeaderless() {
        return !peerUrls.isEmpty();
    }

    public boolean isLeader() {
        return "leader".equalsIgnoreCase(role);
    }

    public boolean isFollower() {
        return "follower".equalsIgnoreCase(role);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static List<String> sanitizeUrls(List<String> urls) {
        if (urls == null) return List.of();
        return urls.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    private static List<String> splitAndSanitize(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }
}
