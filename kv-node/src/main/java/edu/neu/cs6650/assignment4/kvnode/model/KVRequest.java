package edu.neu.cs6650.assignment4.kvnode.model;

import jakarta.validation.constraints.NotBlank;

public record KVRequest(
    @NotBlank(message = "key is required") String key,
    @NotBlank(message = "value is required") String value
) {
}
