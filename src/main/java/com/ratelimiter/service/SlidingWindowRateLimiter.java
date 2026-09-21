package com.ratelimiter.service;

import com.ratelimiter.model.RateLimitResult;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SlidingWindowRateLimiter {

    private final RedisTemplate<String, String> redisTemplate;
    private final DefaultRedisScript<List> slidingWindowScript;
    private final MeterRegistry meterRegistry;

    @Value("${rate-limiter.default-limit:100}")
    private long defaultLimit;

    @Value("${rate-limiter.default-window-seconds:60}")
    private long defaultWindowSeconds;

    private static final String KEY_PREFIX = "rate_limit:";

    public RateLimitResult checkLimit(String clientId) {
        return checkLimit(clientId, defaultLimit, defaultWindowSeconds);
    }

    /**
     * Atomically checks and records a request via Lua script in Redis.
     * Sub-5ms overhead: Lua removes extra round-trips, SHA caching avoids
     * re-sending the script body, Lettuce pool keeps TCP connections warm.
     */
    public RateLimitResult checkLimit(String clientId, long limit, long windowSeconds) {
        long start    = System.currentTimeMillis();
        String key    = KEY_PREFIX + clientId;
        long nowMs    = System.currentTimeMillis();
        long windowMs = windowSeconds * 1000;

        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            @SuppressWarnings("unchecked")
            List<Long> result = redisTemplate.execute(
                    slidingWindowScript,
                    List.of(key),
                    String.valueOf(nowMs),
                    String.valueOf(windowMs),
                    String.valueOf(limit)
            );

            long latencyMs = System.currentTimeMillis() - start;

            if (result == null || result.size() < 4) {
                log.error("Unexpected Redis response for key: {}", key);
                return failOpen(clientId, limit, windowSeconds, latencyMs);
            }

            boolean allowed   = result.get(0) == 1L;
            long currentCount = result.get(1);
            long retryAfterMs = result.get(3);

            meterRegistry.counter("rate_limiter.requests.total",
                    "client", clientId,
                    "allowed", String.valueOf(allowed)).increment();

            meterRegistry.gauge("rate_limiter.window.usage",
                    List.of(io.micrometer.core.instrument.Tag.of("client", clientId)),
                    currentCount);

            sample.stop(meterRegistry.timer("rate_limiter.redis.latency",
                    "client", clientId));

            log.debug("RateLimit [{}] allowed={} count={}/{} latency={}ms",
                    clientId, allowed, currentCount, limit, latencyMs);

            return RateLimitResult.builder()
                    .allowed(allowed).clientId(clientId)
                    .currentCount(currentCount).limit(limit)
                    .windowSeconds(windowSeconds).retryAfterMs(retryAfterMs)
                    .latencyMs(latencyMs).build();

        } catch (Exception e) {
            log.error("Redis error for client {}: {}", clientId, e.getMessage());
            return failOpen(clientId, limit, windowSeconds,
                    System.currentTimeMillis() - start);
        }
    }

    public void reset(String clientId) {
        redisTemplate.delete(KEY_PREFIX + clientId);
        log.info("Rate limit window reset for client: {}", clientId);
    }

    private RateLimitResult failOpen(String clientId, long limit,
                                     long windowSeconds, long latencyMs) {
        return RateLimitResult.builder()
                .allowed(true).clientId(clientId).limit(limit)
                .windowSeconds(windowSeconds).currentCount(0)
                .retryAfterMs(0).latencyMs(latencyMs).build();
    }
}
