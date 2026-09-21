package com.ratelimiter.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class RateLimitResult {
    private boolean allowed;
    private long currentCount;
    private long limit;
    private long windowSeconds;
    private long retryAfterMs;       // 0 if allowed
    private long latencyMs;          // how long the Redis round-trip took
    private String clientId;
}
