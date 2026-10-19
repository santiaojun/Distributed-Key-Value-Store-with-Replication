package edu.neu.cs6650.assignment4.kvnode.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record KVResponse(String key, String value, Integer version) {
}
