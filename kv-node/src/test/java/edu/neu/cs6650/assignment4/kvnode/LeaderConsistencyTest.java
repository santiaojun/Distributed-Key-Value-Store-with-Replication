package edu.neu.cs6650.assignment4.kvnode;

import edu.neu.cs6650.assignment4.kvnode.model.KVInternalRequest;
import edu.neu.cs6650.assignment4.kvnode.model.KVRequest;
import edu.neu.cs6650.assignment4.kvnode.model.KVResponse;
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
 * Test A: Leader-Follower strong consistency (W=5, R=1).
 *
 * Verifies that after a write completing with W=5 (leader + 4 followers all
 * acknowledged), a subsequent read from the leader always returns the latest
 * written value.  Follower HTTP calls are mocked so no real follower processes
 * are required.
 */
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = {
        "kv.role=leader",
        "kv.write-quorum-size=5",
        "kv.read-quorum-size=1",
        "kv.follower-urls=http://localhost:19001,http://localhost:19002," +
            "http://localhost:19003,http://localhost:19004"
    })
class LeaderConsistencyTest {

    /** Mocks all outbound HTTP calls the leader makes to followers. */
    @MockBean
    RestTemplate restTemplate;

    @Autowired
    TestRestTemplate testRestTemplate;

    /**
     * testStrongConsistencyLeader (README Test A)
     *
     * After a W=5 write (leader writes locally and propagates to all 4 mocked
     * followers), a R=1 read from the leader must return the exact value that was
     * written at the same version number.  This guarantees that the leader itself
     * is always strongly consistent after a full-quorum write.
     */
    @Test
    void testStrongConsistencyLeader() {
        String key = "leader-consistency-key";
        String value = "leader-consistency-value";

        // Write with W=5: leader writes locally (200 ms delay) then replicates to
        // 4 mocked followers.  Each mock call returns silently (default Mockito void).
        ResponseEntity<KVResponse> putResp = testRestTemplate.exchange(
                "/kv",
                HttpMethod.PUT,
                new HttpEntity<>(new KVRequest(key, value)),
                KVResponse.class);

        assertThat(putResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(putResp.getBody()).isNotNull();
        int writtenVersion = putResp.getBody().version();
        assertThat(writtenVersion).isGreaterThan(0);

        // Read with R=1: leader reads from its own local store (50 ms delay).
        // Must return the exact value and version from the write above.
        ResponseEntity<KVResponse> getResp = testRestTemplate.getForEntity(
                "/kv?key=" + key, KVResponse.class);

        assertThat(getResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getResp.getBody()).isNotNull();
        assertThat(getResp.getBody().key()).isEqualTo(key);
        assertThat(getResp.getBody().value()).isEqualTo(value);
        assertThat(getResp.getBody().version()).isEqualTo(writtenVersion);
    }

    @Test
    void testWriteReturnsServiceUnavailableWhenWriteQuorumFails() {
        doThrow(new ResourceAccessException("follower unavailable"))
                .when(restTemplate)
                .put(eq("http://localhost:19002/kv/internal"), any(KVInternalRequest.class));

        ResponseEntity<KVResponse> putResp = testRestTemplate.exchange(
                "/kv",
                HttpMethod.PUT,
                new HttpEntity<>(new KVRequest("leader-quorum-failure-key", "value")),
                KVResponse.class);

        assertThat(putResp.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }
}
