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
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests B and C: Leader-Follower follower-side behavior.
 *
 * The node runs in follower role.  Followers receive writes exclusively through
 * the internal replication endpoint PUT /kv/internal and expose their local state
 * via GET /local_read.  No outbound replication is performed by followers, so no
 * RestTemplate mock is required.
 */
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = {
        "kv.role=follower",
        "kv.write-quorum-size=5",
        "kv.read-quorum-size=1"
    })
class FollowerConsistencyTest {

    @Autowired
    TestRestTemplate testRestTemplate;

    /**
     * testStrongConsistencyFollower (README Test B)
     *
     * Simulates the leader replicating a W=5 write to this follower via
     * PUT /kv/internal.  After the replication is acknowledged, GET /local_read
     * must return the exact value and version that were replicated.
     *
     * This confirms that once a follower has acknowledged a write, it is in a
     * consistent state and any subsequent local read reflects the latest data.
     */
    @Test
    void testStrongConsistencyFollower() {
        String key = "follower-consistency-key";
        String value = "replicated-value";
        int version = 1;

        // Leader sends replication to this follower.
        // PUT /kv/internal applies a 200 ms write delay before storing.
        ResponseEntity<Void> internalPut = testRestTemplate.exchange(
                "/kv/internal",
                HttpMethod.PUT,
                new HttpEntity<>(new KVInternalRequest(key, value, version)),
                Void.class);

        assertThat(internalPut.getStatusCode()).isEqualTo(HttpStatus.OK);

        // After acknowledgment, local_read must reflect the replicated state.
        ResponseEntity<KVResponse> localRead = testRestTemplate.getForEntity(
                "/local_read?key=" + key, KVResponse.class);

        assertThat(localRead.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(localRead.getBody()).isNotNull();
        assertThat(localRead.getBody().key()).isEqualTo(key);
        assertThat(localRead.getBody().value()).isEqualTo(value);
        assertThat(localRead.getBody().version()).isEqualTo(version);
    }

    @Test
    void testOlderInternalWriteDoesNotOverwriteNewerVersion() {
        String key = "stale-internal-write-key";

        ResponseEntity<Void> newerWrite = testRestTemplate.exchange(
                "/kv/internal",
                HttpMethod.PUT,
                new HttpEntity<>(new KVInternalRequest(key, "newer-value", 2)),
                Void.class);
        assertThat(newerWrite.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Void> olderWrite = testRestTemplate.exchange(
                "/kv/internal",
                HttpMethod.PUT,
                new HttpEntity<>(new KVInternalRequest(key, "older-value", 1)),
                Void.class);
        assertThat(olderWrite.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<KVResponse> localRead = testRestTemplate.getForEntity(
                "/local_read?key=" + key, KVResponse.class);

        assertThat(localRead.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(localRead.getBody()).isNotNull();
        assertThat(localRead.getBody().value()).isEqualTo("newer-value");
        assertThat(localRead.getBody().version()).isEqualTo(2);
    }

    @Test
    void testFollowerRejectsClientFacingWrite() {
        String key = "follower-client-write-key";

        ResponseEntity<KVResponse> putResp = testRestTemplate.exchange(
                "/kv",
                HttpMethod.PUT,
                new HttpEntity<>(new KVRequest(key, "client-value")),
                KVResponse.class);

        assertThat(putResp.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        ResponseEntity<KVResponse> localRead = testRestTemplate.getForEntity(
                "/local_read?key=" + key, KVResponse.class);
        assertThat(localRead.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    /**
     * testInconsistencyWindow (README Test C)
     *
     * Demonstrates the follower inconsistency window that arises with W=1:
     * when the leader uses W=1 it writes only locally and does not replicate
     * to followers, leaving them with permanently stale state.
     *
     * <p>This test models the window directly by observing follower state at
     * three points in time:
     * <ol>
     *   <li>Before any replication: GET /local_read returns 404 — the follower
     *       has no data because the leader's W=1 write never propagated here.</li>
     *   <li>During replication: a PUT /kv/internal is started in a background
     *       thread (it sleeps 200 ms before committing).  A concurrent
     *       GET /local_read issued after the request is received but before
     *       the 200 ms delay elapses also returns 404 — the write is still
     *       in-flight and the key is not yet committed.</li>
     *   <li>After replication completes: GET /local_read returns 200 OK with
     *       the correct value, confirming the inconsistency was transient.</li>
     * </ol>
     *
     * The read delay (50 ms) is less than the write delay (200 ms), so the
     * concurrent read reliably completes and returns before the write commits.
     */
    @Test
    void testInconsistencyWindow() throws Exception {
        String key = "inconsistency-window-key";
        String value = "window-value";
        int version = 7;

        // Phase 1: no replication received — follower has stale (empty) state.
        ResponseEntity<KVResponse> beforeReplication = testRestTemplate.getForEntity(
                "/local_read?key=" + key, KVResponse.class);
        assertThat(beforeReplication.getStatusCode())
                .as("follower has no data before replication (W=1 scenario)")
                .isEqualTo(HttpStatus.NOT_FOUND);

        // Phase 2: start replication in background; it will block for 200 ms.
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<ResponseEntity<Void>> writeFuture = executor.submit(() ->
                testRestTemplate.exchange(
                        "/kv/internal",
                        HttpMethod.PUT,
                        new HttpEntity<>(new KVInternalRequest(key, value, version)),
                        Void.class));

        // Wait briefly for the request to be received and enter its write-delay sleep.
        Thread.sleep(30);

        // Concurrent local read during the write delay: must still see stale state.
        // The read completes in ~50 ms; the write won't commit for another ~170 ms.
        ResponseEntity<KVResponse> duringReplication = testRestTemplate.getForEntity(
                "/local_read?key=" + key, KVResponse.class);
        assertThat(duringReplication.getStatusCode())
                .as("follower local_read during replication window must observe stale data (404)")
                .isEqualTo(HttpStatus.NOT_FOUND);

        // Let the background write complete.
        ResponseEntity<Void> writeResult = writeFuture.get();
        assertThat(writeResult.getStatusCode()).isEqualTo(HttpStatus.OK);
        executor.shutdown();

        // Phase 3: after replication is acknowledged the inconsistency window is closed.
        ResponseEntity<KVResponse> afterReplication = testRestTemplate.getForEntity(
                "/local_read?key=" + key, KVResponse.class);
        assertThat(afterReplication.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(afterReplication.getBody()).isNotNull();
        assertThat(afterReplication.getBody().value()).isEqualTo(value);
        assertThat(afterReplication.getBody().version()).isEqualTo(version);
    }
}
