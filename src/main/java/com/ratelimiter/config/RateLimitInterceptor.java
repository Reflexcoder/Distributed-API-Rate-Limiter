package com.ratelimiter.config;

import com.ratelimiter.model.RateLimitResult;
import com.ratelimiter.service.SlidingWindowRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitInterceptor implements HandlerInterceptor {

    private final SlidingWindowRateLimiter rateLimiter;

    @Value("${rate-limiter.default-limit:100}")
    private long defaultLimit;

    @Value("${rate-limiter.default-window-seconds:60}")
    private long defaultWindowSeconds;

    /**
     * Intercepts every incoming request before the controller handles it.
     * Client identity: X-Client-ID header → fallback to IP address.
     * Sets standard rate limit headers on every response.
     */
    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws Exception {

        // Identify the client — prefer an explicit header, fall back to IP
        String clientId = request.getHeader("X-Client-ID");
        if (clientId == null || clientId.isBlank()) {
            clientId = getClientIp(request);
        }

        RateLimitResult result = rateLimiter.checkLimit(clientId,
                defaultLimit, defaultWindowSeconds);

        // Standard rate-limit response headers
        response.setHeader("X-RateLimit-Limit",     String.valueOf(result.getLimit()));
        response.setHeader("X-RateLimit-Remaining",
                String.valueOf(Math.max(0, result.getLimit() - result.getCurrentCount())));
        response.setHeader("X-RateLimit-Window",    result.getWindowSeconds() + "s");
        response.setHeader("X-RateLimit-Latency-Ms", String.valueOf(result.getLatencyMs()));

        if (!result.isAllowed()) {
            response.setHeader("Retry-After",
                    String.valueOf((int) Math.ceil(result.getRetryAfterMs() / 1000.0)));
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType("application/json");
            response.getWriter().write(String.format(
                    "{\"error\":\"Too Many Requests\",\"retryAfterMs\":%d,\"limit\":%d,\"window\":\"%ds\"}",
                    result.getRetryAfterMs(), result.getLimit(), result.getWindowSeconds()));
            return false;
        }

        return true;
    }

    private String getClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
