package edu.neu.cs6650.assignment4.kvnode.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record KVInternalRequest(
    @NotBlank(message = "key is required") String key,
    @NotBlank(message = "value is required") String value,
    @Positive(message = "version must be positive") int version
) {
}
