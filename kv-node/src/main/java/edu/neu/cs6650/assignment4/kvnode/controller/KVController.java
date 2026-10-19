package edu.neu.cs6650.assignment4.kvnode.controller;

import edu.neu.cs6650.assignment4.kvnode.config.ClusterProperties;
import edu.neu.cs6650.assignment4.kvnode.model.KVInternalRequest;
import edu.neu.cs6650.assignment4.kvnode.model.KVRequest;
import edu.neu.cs6650.assignment4.kvnode.model.KVResponse;
import edu.neu.cs6650.assignment4.kvnode.service.KvModeService;
import edu.neu.cs6650.assignment4.kvnode.service.LeaderFollowerService;
import edu.neu.cs6650.assignment4.kvnode.service.LeaderlessService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
public class KVController {

  private final LeaderFollowerService leaderFollowerService;
  private final LeaderlessService leaderlessService;
  private final ClusterProperties clusterProperties;

  public KVController(
      LeaderFollowerService leaderFollowerService,
      LeaderlessService leaderlessService,
      ClusterProperties clusterProperties
  ) {
    this.leaderFollowerService = leaderFollowerService;
    this.leaderlessService = leaderlessService;
    this.clusterProperties = clusterProperties;
  }

  @PutMapping("/kv")
  public ResponseEntity<KVResponse> put(@Valid @RequestBody KVRequest request) {
    KVResponse response = activeService().put(request);
    return ResponseEntity.status(HttpStatus.CREATED).body(response);
  }

  @GetMapping("/kv")
  public ResponseEntity<KVResponse> get(@RequestParam("key") @NotBlank String key) {
    return activeService()
        .get(key)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  @GetMapping("/local_read")
  public ResponseEntity<KVResponse> localRead(@RequestParam("key") @NotBlank String key) {
    return activeService()
        .localRead(key)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  @PutMapping("/kv/internal")
  public ResponseEntity<Void> internalPut(@Valid @RequestBody KVInternalRequest request) {
    activeService().applyInternalWrite(request);
    return ResponseEntity.ok().build();
  }

  // Used by leader during read quorum (R > 1) to fetch local value from each follower
  @GetMapping("/kv/internal")
  public ResponseEntity<KVResponse> internalGet(@RequestParam("key") @NotBlank String key) {
    return activeService()
        .localRead(key)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  private KvModeService activeService() {
    if (clusterProperties.isLeaderless()) {
      return leaderlessService;
    }
    return leaderFollowerService;
  }
}