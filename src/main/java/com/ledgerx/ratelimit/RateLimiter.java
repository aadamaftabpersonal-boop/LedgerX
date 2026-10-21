package com.ledgerx.ratelimit;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Per-client token bucket: each request costs one token, tokens refill continuously at a fixed rate up to a burst
 * capacity. State is in memory, so with several application instances each enforces its own limit; a shared store
 * (for example Redis) would be needed for a global limit.
 */
@Component
public class RateLimiter {

    public record Decision(boolean allowed, long retryAfterSeconds) {
        static final Decision ALLOWED = new Decision(true, 0);
    }

    private static final class Bucket {
        double tokens;
        long lastNanos;

        Bucket(double tokens, long lastNanos) {
            this.tokens = tokens;
            this.lastNanos = lastNanos;
        }
    }

    private final boolean enabled;
    private final double tokensPerSecond;
    private final int burst;
    private final LongSupplier nanoClock;
    private final ConcurrentHashMap<UUID, Bucket> buckets = new ConcurrentHashMap<>();

    @Autowired
    public RateLimiter(
            @Value("${ledgerx.rate-limit.enabled:true}") boolean enabled,
            @Value("${ledgerx.rate-limit.requests-per-second:100}") double tokensPerSecond,
            @Value("${ledgerx.rate-limit.burst:200}") int burst) {
        this(enabled, tokensPerSecond, burst, System::nanoTime);
    }

    public RateLimiter(boolean enabled, double tokensPerSecond, int burst, LongSupplier nanoClock) {
        if (tokensPerSecond < 0 || burst < 1) {
            throw new IllegalArgumentException("rate must be >= 0 and burst >= 1");
        }
        this.enabled = enabled;
        this.tokensPerSecond = tokensPerSecond;
        this.burst = burst;
        this.nanoClock = nanoClock;
    }

    public Decision tryAcquire(UUID clientId) {
        if (!enabled) {
            return Decision.ALLOWED;
        }
        long now = nanoClock.getAsLong();
        Bucket bucket = buckets.computeIfAbsent(clientId, id -> new Bucket(burst, now));
        synchronized (bucket) {
            double elapsedSeconds = Math.max(0, now - bucket.lastNanos) / 1_000_000_000.0;
            bucket.tokens = Math.min(burst, bucket.tokens + elapsedSeconds * tokensPerSecond);
            bucket.lastNanos = now;
            if (bucket.tokens >= 1) {
                bucket.tokens -= 1;
                return Decision.ALLOWED;
            }
            long retryAfter = tokensPerSecond == 0
                    ? Long.MAX_VALUE
                    : (long) Math.ceil((1 - bucket.tokens) / tokensPerSecond);
            return new Decision(false, Math.max(1, retryAfter));
        }
    }
}
