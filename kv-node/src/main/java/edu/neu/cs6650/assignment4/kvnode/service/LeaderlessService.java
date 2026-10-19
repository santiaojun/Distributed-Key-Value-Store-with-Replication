package edu.neu.cs6650.assignment4.kvnode.service;

import edu.neu.cs6650.assignment4.kvnode.config.ClusterProperties;
import edu.neu.cs6650.assignment4.kvnode.model.KVEntry;
import edu.neu.cs6650.assignment4.kvnode.model.KVInternalRequest;
import edu.neu.cs6650.assignment4.kvnode.model.KVRequest;
import edu.neu.cs6650.assignment4.kvnode.model.KVResponse;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Implements the Leaderless replication strategy (W=N=5, R=1).
 *
 * <h3>Write path (PUT /kv)</h3>
 * <ol>
 *   <li>This node acts as write coordinator.</li>
 *   <li>Sleeps 200 ms (simulated write delay) and writes locally, assigning the version.</li>
 *   <li>Sequentially propagates the write with its assigned version to every peer listed in
 *       {@code PEER_URLS} via {@code PUT /kv/internal}.  Each peer also sleeps 200 ms.</li>
 *   <li>Returns success to the client only after all peer acknowledgments are received.</li>
 * </ol>
 *
 * <h3>Read path (GET /kv and GET /local_read)</h3>
 * Reads are purely local: sleep 50 ms and return the locally stored value (R=1).
 *
 * <h3>Internal write path (PUT /kv/internal)</h3>
 * Peer nodes receive the coordinator's version and store it with a 200 ms write delay.
 */
@Service
public class LeaderlessService implements KvModeService {

  private static final Logger log = LoggerFactory.getLogger(LeaderlessService.class);
  private static final int REQUIRED_PEER_ACKS = 4;

  private final KvStore kvStore;
  private final ClusterProperties props;
  private final RestTemplate restTemplate;

  public LeaderlessService(KvStore kvStore, ClusterProperties props, RestTemplate restTemplate) {
    this.kvStore = kvStore;
    this.props = props;
    this.restTemplate = restTemplate;
  }

  /**
   * Coordinator write: local write then sequential propagation to all peers (W=N).
   */
  @Override
  public KVResponse put(KVRequest request) {
    // Step 1: coordinator's own write with simulated delay
    DelaySimulator.sleepForWrite();
    KVEntry saved = kvStore.putWithNextVersion(request.key(), request.value());

    log.info("[LL] PUT key={} version={} (coordinator local write)", request.key(), saved.version());

    // Step 2: sequentially propagate to every peer so that W=N=5 is satisfied
    propagateToPeers(request.key(), request.value(), saved.version());

    return new KVResponse(request.key(), null, saved.version());
  }

  /**
   * Sends PUT /kv/internal to every peer in order, waiting for each acknowledgment.
   * Replication errors are logged, but the client receives 503 unless every
   * required peer acknowledges the write.
   */
  private void propagateToPeers(String key, String value, int version) {
    List<String> peers = props.getPeerUrls();
    if (peers.size() < REQUIRED_PEER_ACKS) {
      throw serviceUnavailable("Not enough peer URLs configured for leaderless W=5");
    }

    KVInternalRequest internalReq = new KVInternalRequest(key, value, version);
    int acknowledgments = 0;

    for (String peerUrl : peers) {
      try {
        restTemplate.put(peerUrl + "/kv/internal", internalReq);
        acknowledgments++;
        log.debug("[LL] Replicated key={} v={} to {}", key, version, peerUrl);
      } catch (Exception e) {
        log.warn("[LL] Replication to {} failed: {}", peerUrl, e.getMessage());
      }
    }

    if (acknowledgments < peers.size()) {
      throw serviceUnavailable("Leaderless write could not reach full peer acknowledgment");
    }
  }

  /**
   * Local read with 50 ms delay (R=1, no cross-node coordination).
   */
  @Override
  public Optional<KVResponse> get(String key) {
    DelaySimulator.sleepForRead();
    return kvStore.get(key).map(entry -> toReadResponse(key, entry));
  }

  /**
   * Local read bypassing any quorum logic; also applies the 50 ms read delay.
   */
  @Override
  public Optional<KVResponse> localRead(String key) {
    DelaySimulator.sleepForRead();
    return kvStore.get(key).map(entry -> toReadResponse(key, entry));
  }

  /**
   * Peer-side handler for coordinator-initiated replication.
   * Applies 200 ms write delay before persisting the coordinator-assigned version.
   */
  @Override
  public void applyInternalWrite(KVInternalRequest request) {
    DelaySimulator.sleepForWrite();
    kvStore.putWithVersion(request.key(), request.value(), request.version());
    log.debug("[LL] Internal write applied: key={} v={}", request.key(), request.version());
  }

  private KVResponse toReadResponse(String key, KVEntry entry) {
    return new KVResponse(key, entry.value(), entry.version());
  }

  private ResponseStatusException serviceUnavailable(String reason) {
    return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, reason);
  }
}
