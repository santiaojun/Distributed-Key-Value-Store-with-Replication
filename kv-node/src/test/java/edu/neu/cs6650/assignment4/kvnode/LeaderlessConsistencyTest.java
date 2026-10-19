package edu.neu.cs6650.assignment4.kvnode;

import edu.neu.cs6650.assignment4.kvnode.model.KVInternalRequest;
import edu.neu.cs6650.assignment4.kvnode.model.KVRequest;
import edu.neu.cs6650.assignment4.kvnode.model.KVResponse;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests D, E, F: Leaderless replication — inconsistency window and post-ack consistency.
 *
 * The node runs in leaderless mode (PEER_URLS is non-empty, so
 * {@code ClusterProperties.isLeaderless()} returns {@code true}).  Outbound peer
 * HTTP calls from the coordinator path are mocked so no real peer nodes are needed.
 *
 * <p>Three distinct scenarios are covered:
 * <ul>
 *   <li>D – stale read from a peer node during coordinator propagation window</li>
 *   <li>E – coordinator's own local read is consistent after full acknowledgment</li>
 *   <li>F – peer node is consistent after receiving and acknowledging replication</li>
 * </ul>
 */
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = {
        "kv.peer-urls=http://localhost:19001,http://localhost:19002," +
            "http://localhost:19003,http://localhost:19004"
    })
class LeaderlessConsistencyTest {

    /** Mocks all outbound HTTP calls the coordinator makes to peers. */
    @MockBean
    RestTemplate restTemplate;

    @Autowired
    TestRestTemplate testRestTemplate;

    /**
     * testLeaderlessInconsistency (README Test D)
     *
     * Demonstrates the leaderless inconsistency window: between the coordinator's
     * local write and the moment a peer commits the propagated write, the peer
     * returns stale data on local reads.
     *
     * <p>This node acts as the receiving peer.  The coordinator sends a write via
     * PUT /kv/internal, which applies a 200 ms write delay before persisting.
     * A GET /local_read issued concurrently — after the request is received but
     * before the 200 ms elapses — returns HTTP 404 (stale), demonstrating the
     * inconsistency window.  After the write commits the peer is consistent.
     */
    @Test
    void testLeaderlessInconsistency() throws Exception {
        String key = "leaderless-stale-key";
        String value = "coordinator-propagated-value";
        int version = 3;

        // Peer has no data — coordinator has written locally but not yet propagated here.
        ResponseEntity<KVResponse> beforePropagation = testRestTemplate.getForEntity(
                "/local_read?key=" + key, KVResponse.class);
        assertThat(beforePropagation.getStatusCode())
                .as("peer has no data before coordinator propagation")
                .isEqualTo(HttpStatus.NOT_FOUND);

        // Start coordinator propagation in background: PUT /kv/internal blocks 200 ms.
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<ResponseEntity<Void>> writeFuture = executor.submit(() ->
                testRestTemplate.exchange(
                        "/kv/internal",
                        HttpMethod.PUT,
                        new HttpEntity<>(new KVInternalRequest(key, value, version)),
                        Void.class));

        // Allow the request to be received and enter the write-delay sleep (~30 ms buffer).
        Thread.sleep(30);

        // Concurrent read during propagation window — must observe stale state.
        // Read delay is 50 ms; write has ~170 ms remaining, so the read returns first.
        ResponseEntity<KVResponse> duringPropagation = testRestTemplate.getForEntity(
                "/local_read?key=" + key, KVResponse.class);
        assertThat(duringPropagation.getStatusCode())
                .as("peer local_read during coordinator propagation window must be stale (404)")
                .isEqualTo(HttpStatus.NOT_FOUND);

        // Wait for propagation to complete.
        ResponseEntity<Void> writeResult = writeFuture.get();
        assertThat(writeResult.getStatusCode()).isEqualTo(HttpStatus.OK);
        executor.shutdown();

        // After acknowledgment, the inconsistency window is closed.
        ResponseEntity<KVResponse> afterPropagation = testRestTemplate.getForEntity(
                "/local_read?key=" + key, KVResponse.class);
        assertThat(afterPropagation.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(afterPropagation.getBody()).isNotNull();
        assertThat(afterPropagation.getBody().value()).isEqualTo(value);
        assertThat(afterPropagation.getBody().version()).isEqualTo(version);
    }

    /**
     * testLeaderlessPostAckCoordinator (README Test E)
     *
     * After a coordinator write completes (local write + all 4 peers acknowledged,
     * W=N=5), a GET /local_read on the coordinator must return the latest value at
     * the correct version.
     *
     * Peer HTTP calls are mocked to simulate successful propagation acknowledgments.
     * This verifies that the coordinator itself is always consistent once it returns
     * a success response to the client.
     */
    @Test
    void testLeaderlessPostAckCoordinator() {
        String key = "leaderless-coordinator-postack-key";
        String value = "coordinator-confirmed-value";

        // Coordinator write: local write (200 ms delay) + propagation to 4 mocked peers.
        ResponseEntity<KVResponse> putResp = testRestTemplate.exchange(
                "/kv",
                HttpMethod.PUT,
                new HttpEntity<>(new KVRequest(key, value)),
                KVResponse.class);

        assertThat(putResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(putResp.getBody()).isNotNull();
        int writtenVersion = putResp.getBody().version();
        assertThat(writtenVersion).isGreaterThan(0);

        // Coordinator local read must return the same version and value.
        ResponseEntity<KVResponse> localRead = testRestTemplate.getForEntity(
                "/local_read?key=" + key, KVResponse.class);

        assertThat(localRead.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(localRead.getBody()).isNotNull();
        assertThat(localRead.getBody().key()).isEqualTo(key);
        assertThat(localRead.getBody().value()).isEqualTo(value);
        assertThat(localRead.getBody().version()).isEqualTo(writtenVersion);
    }

    @Test
    void testLeaderlessWriteReturnsServiceUnavailableWhenPeerAckFails() {
        doThrow(new ResourceAccessException("peer unavailable"))
                .when(restTemplate)
                .put(eq("http://localhost:19002/kv/internal"), any(KVInternalRequest.class));

        ResponseEntity<KVResponse> putResp = testRestTemplate.exchange(
                "/kv",
                HttpMethod.PUT,
                new HttpEntity<>(new KVRequest("leaderless-peer-failure-key", "value")),
                KVResponse.class);

        assertThat(putResp.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    /**
     * testLeaderlessPostAckPeer (README Test F)
     *
     * After the coordinator propagates a write to this peer via PUT /kv/internal and
     * the peer acknowledges it, a GET /local_read on the peer must return the
     * coordinator-assigned value and version.
     *
     * This confirms that once a peer has sent its acknowledgment, it is in a
     * fully consistent state and local reads reflect the latest committed data.
     */
    @Test
    void testLeaderlessPostAckPeer() {
        String key = "leaderless-peer-postack-key";
        String value = "peer-confirmed-value";
        int version = 5;

        // Coordinator sends replication to this peer via PUT /kv/internal.
        ResponseEntity<Void> internalPut = testRestTemplate.exchange(
                "/kv/internal",
                HttpMethod.PUT,
                new HttpEntity<>(new KVInternalRequest(key, value, version)),
                Void.class);

        assertThat(internalPut.getStatusCode()).isEqualTo(HttpStatus.OK);

        // After acknowledgment, peer local read must return the coordinator-assigned state.
        ResponseEntity<KVResponse> localRead = testRestTemplate.getForEntity(
                "/local_read?key=" + key, KVResponse.class);

        assertThat(localRead.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(localRead.getBody()).isNotNull();
        assertThat(localRead.getBody().key()).isEqualTo(key);
        assertThat(localRead.getBody().value()).isEqualTo(value);
        assertThat(localRead.getBody().version()).isEqualTo(version);
    }
}
