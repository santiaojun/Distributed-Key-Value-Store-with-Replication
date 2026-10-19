package edu.neu.cs6650.assignment4.kvnode.service;

import edu.neu.cs6650.assignment4.kvnode.model.KVInternalRequest;
import edu.neu.cs6650.assignment4.kvnode.model.KVRequest;
import edu.neu.cs6650.assignment4.kvnode.model.KVResponse;
import java.util.Optional;

public interface KvModeService {

  KVResponse put(KVRequest request);

  Optional<KVResponse> get(String key);

  Optional<KVResponse> localRead(String key);

  void applyInternalWrite(KVInternalRequest request);
}
