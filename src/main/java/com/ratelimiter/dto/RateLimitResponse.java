package com.ratelimiter.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class RateLimitResponse {
    private boolean allowed;
    private long currentCount;
    private long limit;
    private long windowSeconds;
    private long remaining;
    private long retryAfterMs;
    private long latencyMs;
    private String clientId;
}
