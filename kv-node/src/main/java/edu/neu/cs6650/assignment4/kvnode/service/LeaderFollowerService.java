package edu.neu.cs6650.assignment4.kvnode.service;

import edu.neu.cs6650.assignment4.kvnode.config.ClusterProperties;
import edu.neu.cs6650.assignment4.kvnode.model.KVEntry;
import edu.neu.cs6650.assignment4.kvnode.model.KVInternalRequest;
import edu.neu.cs6650.assignment4.kvnode.model.KVRequest;
import edu.neu.cs6650.assignment4.kvnode.model.KVResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

/**
 * Implements the Leader-Follower replication strategy.
 *
 * <h3>Write path (PUT /kv)</h3>
 * <ol>
 *   <li>Leader sleeps 200 ms (simulates its own disk write) and stores locally.</li>
 *   <li>Leader then sequentially forwards the write to (W − 1) followers via
 *       PUT /kv/internal.  Each follower also sleeps 200 ms.</li>
 *   <li>Total write latency ≈ W × 200 ms (e.g. ~1 000 ms for W=5).</li>
 * </ol>
 *
 * <h3>Read path (GET /kv)</h3>
 * <ol>
 *   <li>Leader sleeps 50 ms and reads its local store.</li>
 *   <li>If R > 1, Leader sequentially calls GET /kv/internal on (R − 1)
 *       followers.  Each follower also sleeps 50 ms.</li>
 *   <li>Leader returns the entry with the highest version number.</li>
 * </ol>
 *
 * <h3>Follower path (PUT /kv/internal and GET /kv/internal)</h3>
 * Followers never initiate replication; they only accept internal calls from
 * the leader and apply the same delays before responding.
 */
@Service
public class LeaderFollowerService implements KvModeService {

    private static final Logger log = LoggerFactory.getLogger(LeaderFollowerService.class);

    private final KvStore kvStore;
    private final ClusterProperties props;
    private final RestTemplate restTemplate;

    public LeaderFollowerService(KvStore kvStore,
                                 ClusterProperties props,
                                 RestTemplate restTemplate) {
        this.kvStore = kvStore;
        this.props = props;
        this.restTemplate = restTemplate;
    }

    // -------------------------------------------------------------------------
    // Client-facing write: PUT /kv
    // -------------------------------------------------------------------------

    /**
     * Leader-only operation.
     *
     * 1. Sleep 200 ms and write locally (leader counts as 1 in the quorum).
     * 2. Sequentially replicate to (W − 1) followers.
     *
     * Followers reject client-facing writes because the leader is the only valid
     * write entry point in Leader-Follower mode.
     */
    @Override
    public KVResponse put(KVRequest request) {
        if (props.isFollower()) {
            throw serviceUnavailable("Leader-Follower writes must be sent to the leader");
        }

        // Step 1: leader's own write (200 ms delay)
        DelaySimulator.sleepForWrite();
        KVEntry saved = kvStore.putWithNextVersion(request.key(), request.value());

        log.info("[LF] PUT key={} version={} role={}", request.key(), saved.version(), props.getRole());

        // Step 2: replicate to followers and fail the request if quorum is not met.
        replicateToFollowers(request.key(), request.value(), saved.version());

        return new KVResponse(request.key(), null, saved.version());
    }

    /**
     * Sequentially sends PUT /kv/internal until (W - 1) follower acknowledgments
     * are collected. If quorum cannot be reached, the client receives 503.
     */
    private void replicateToFollowers(String key, String value, int version) {
        List<String> followers = props.getFollowerUrls();
        int writeQuorum = props.getWriteQuorumSize();

        // Leader already counts as 1; W=1 requires no follower acknowledgments.
        int requiredFollowerAcks = Math.max(writeQuorum - 1, 0);
        if (requiredFollowerAcks == 0) {
            return;
        }
        if (followers.size() < requiredFollowerAcks) {
            throw serviceUnavailable("Not enough follower URLs configured for write quorum");
        }

        KVInternalRequest internalReq = new KVInternalRequest(key, value, version);
        int acknowledgments = 0;

        for (String followerUrl : followers) {
            try {
                restTemplate.put(followerUrl + "/kv/internal", internalReq);
                acknowledgments++;
                log.debug("[LF] Replicated key={} v={} to {}", key, version, followerUrl);
                if (acknowledgments >= requiredFollowerAcks) {
                    return;
                }
            } catch (Exception e) {
                log.warn("[LF] Replication failed to {}: {}", followerUrl, e.getMessage());
            }
        }

        throw serviceUnavailable("Write quorum could not be reached");
    }

    // -------------------------------------------------------------------------
    // Client-facing read: GET /kv
    // -------------------------------------------------------------------------

    /**
     * Quorum read.
     *
     * R=1 : sleep 50 ms, return local value.
     * R>1 : sleep 50 ms, read local, then sequentially fetch from (R − 1)
     *        followers via GET /kv/internal (each follower also sleeps 50 ms).
     *        Return the entry with the highest version.
     *
     * Total read latency ≈ R × 50 ms (e.g. ~250 ms for R=5).
     */
    @Override
    public Optional<KVResponse> get(String key) {
        int readQuorum = props.getReadQuorumSize();

        // Leader's own read (50 ms delay)
        DelaySimulator.sleepForRead();
        Optional<KVEntry> localEntry = kvStore.get(key);

        if (!props.isLeader() || readQuorum <= 1) {
            return localEntry.map(e -> toReadResponse(key, e));
        }

        // Collect from followers until (R - 1) responses are observed and keep the highest version.
        KVEntry best = localEntry.orElse(null);
        List<String> followers = props.getFollowerUrls();
        int requiredFollowerReads = Math.max(readQuorum - 1, 0);
        if (followers.size() < requiredFollowerReads) {
            throw serviceUnavailable("Not enough follower URLs configured for read quorum");
        }
        int successfulFollowerReads = 0;

        for (String followerUrl : followers) {
            try {
                ResponseEntity<KVResponse> resp = restTemplate.getForEntity(
                        followerUrl + "/kv/internal?key=" + key, KVResponse.class);

                successfulFollowerReads++;
                KVResponse body = resp.getBody();
                if (body != null && body.version() != null
                        && (best == null || body.version() > best.version())) {
                    best = new KVEntry(body.value(), body.version());
                }
                log.debug("[LF] Read quorum from {}: key={} v={}", followerUrl, key,
                        body != null ? body.version() : "null");
                if (successfulFollowerReads >= requiredFollowerReads) {
                    return Optional.ofNullable(best).map(e -> toReadResponse(key, e));
                }
            } catch (HttpClientErrorException.NotFound e) {
                successfulFollowerReads++;
                if (successfulFollowerReads >= requiredFollowerReads) {
                    return Optional.ofNullable(best).map(entry -> toReadResponse(key, entry));
                }
            } catch (Exception e) {
                log.warn("[LF] Read quorum failed from {}: {}", followerUrl, e.getMessage());
            }
        }

        if (successfulFollowerReads < requiredFollowerReads) {
            throw serviceUnavailable("Read quorum could not be reached");
        }
        return Optional.ofNullable(best).map(e -> toReadResponse(key, e));
    }

    // -------------------------------------------------------------------------
    // Local read: GET /local_read
    // -------------------------------------------------------------------------

    /**
     * Returns this node's local value without any quorum logic.
     * Used in consistency tests to observe the inconsistency window.
     * Still applies the 50 ms read delay as specified.
     */
    @Override
    public Optional<KVResponse> localRead(String key) {
        DelaySimulator.sleepForRead();
        return kvStore.get(key).map(e -> toReadResponse(key, e));
    }

    // -------------------------------------------------------------------------
    // Internal write: PUT /kv/internal  (called by the leader on each follower)
    // -------------------------------------------------------------------------

    /**
     * Follower-side handler for leader-initiated replication.
     * Sleeps 200 ms (simulates disk write) then stores at the given version.
     * Using putWithVersion ensures version counters stay consistent with the
     * leader even if internal writes arrive out of order.
     */
    @Override
    public void applyInternalWrite(KVInternalRequest request) {
        DelaySimulator.sleepForWrite();
        kvStore.putWithVersion(request.key(), request.value(), request.version());
        log.debug("[LF] Internal write applied: key={} v={}", request.key(), request.version());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private KVResponse toReadResponse(String key, KVEntry entry) {
        return new KVResponse(key, entry.value(), entry.version());
    }

    private ResponseStatusException serviceUnavailable(String reason) {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, reason);
    }
}
