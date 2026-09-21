package com.ratelimiter.controller;

import com.ratelimiter.dto.CheckLimitRequest;
import com.ratelimiter.dto.RateLimitResponse;
import com.ratelimiter.model.RateLimitResult;
import com.ratelimiter.service.SlidingWindowRateLimiter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/rate-limit")
@RequiredArgsConstructor
@Tag(name = "Rate Limiter", description = "Sliding Window Log rate limiting API")
@CrossOrigin(origins = "*")
public class RateLimitController {

    private final SlidingWindowRateLimiter rateLimiter;

    /**
     * Check the rate limit for a client with custom limit + window.
     * Useful for testing and per-client policy enforcement.
     */
    @PostMapping("/check")
    @Operation(summary = "Check rate limit for a client",
               description = "Executes the Lua sliding window script atomically in Redis. " +
                             "Returns allow/deny decision with current count, remaining quota, " +
                             "and retry-after if denied.")
    public ResponseEntity<RateLimitResponse> checkLimit(
            @Valid @RequestBody CheckLimitRequest request) {

        RateLimitResult result = rateLimiter.checkLimit(
                request.getClientId(),
                request.getLimit(),
                request.getWindowSeconds()
        );

        RateLimitResponse response = toResponse(result);

        return result.isAllowed()
                ? ResponseEntity.ok(response)
                : ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(response);
    }

    /**
     * Demo endpoint — simulates a protected API that is subject to rate limiting.
     * The RateLimitInterceptor runs before this method and blocks if over quota.
     */
    @GetMapping("/ping")
    @Operation(summary = "Demo protected endpoint",
               description = "A simple endpoint that is rate-limited by the interceptor. " +
                             "Pass X-Client-ID header to identify your client.")
    public ResponseEntity<String> ping(
            @RequestHeader(value = "X-Client-ID", defaultValue = "anonymous") String clientId) {
        return ResponseEntity.ok("pong — request accepted for client: " + clientId);
    }

    /**
     * Reset a client's rate limit window (useful for testing / admin tooling).
     */
    @DeleteMapping("/reset/{clientId}")
    @Operation(summary = "Reset a client's rate limit window")
    public ResponseEntity<String> reset(@PathVariable String clientId) {
        rateLimiter.reset(clientId);
        return ResponseEntity.ok("Rate limit window reset for client: " + clientId);
    }

    private RateLimitResponse toResponse(RateLimitResult r) {
        return RateLimitResponse.builder()
                .allowed(r.isAllowed())
                .clientId(r.getClientId())
                .currentCount(r.getCurrentCount())
                .limit(r.getLimit())
                .windowSeconds(r.getWindowSeconds())
                .remaining(Math.max(0, r.getLimit() - r.getCurrentCount()))
                .retryAfterMs(r.getRetryAfterMs())
                .latencyMs(r.getLatencyMs())
                .build();
    }
}
