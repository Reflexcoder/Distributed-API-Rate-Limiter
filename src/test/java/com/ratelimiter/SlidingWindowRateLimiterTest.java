package com.ratelimiter;

import com.ratelimiter.model.RateLimitResult;
import com.ratelimiter.service.SlidingWindowRateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import com.redis.testcontainers.RedisContainer;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class SlidingWindowRateLimiterTest {

    @Container
    static RedisContainer redis = new RedisContainer(
            RedisContainer.DEFAULT_IMAGE_NAME.withTag("7.2"));

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
    }

    @Autowired
    private SlidingWindowRateLimiter rateLimiter;

    @BeforeEach
    void reset() {
        rateLimiter.reset("test-client");
    }

    @Test
    void shouldAllowRequestsUnderLimit() {
        for (int i = 0; i < 5; i++) {
            RateLimitResult result = rateLimiter.checkLimit("test-client", 5, 60);
            assertThat(result.isAllowed()).isTrue();
        }
    }

    @Test
    void shouldDenyRequestOverLimit() {
        for (int i = 0; i < 5; i++) {
            rateLimiter.checkLimit("test-client", 5, 60);
        }
        RateLimitResult result = rateLimiter.checkLimit("test-client", 5, 60);
        assertThat(result.isAllowed()).isFalse();
        assertThat(result.getRetryAfterMs()).isPositive();
    }

    @Test
    void shouldBeAtomicUnderConcurrency() throws InterruptedException {
        int threads = 20;
        int limit   = 10;
        int[] allowedCount = {0};

        Thread[] pool = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            pool[i] = new Thread(() -> {
                RateLimitResult r = rateLimiter.checkLimit("concurrent-client", limit, 60);
                if (r.isAllowed()) {
                    synchronized (allowedCount) { allowedCount[0]++; }
                }
            });
        }
        for (Thread t : pool) t.start();
        for (Thread t : pool) t.join();

        // Exactly 'limit' requests should have been allowed — no more, no less
        assertThat(allowedCount[0]).isEqualTo(limit);
    }

    @Test
    void shouldHaveSubFiveMsLatency() {
        RateLimitResult result = rateLimiter.checkLimit("latency-client", 100, 60);
        assertThat(result.getLatencyMs()).isLessThan(5);
    }

    @Test
    void shouldResetWindowCorrectly() {
        for (int i = 0; i < 5; i++) {
            rateLimiter.checkLimit("reset-client", 5, 60);
        }
        rateLimiter.reset("reset-client");
        RateLimitResult result = rateLimiter.checkLimit("reset-client", 5, 60);
        assertThat(result.isAllowed()).isTrue();
    }
}
