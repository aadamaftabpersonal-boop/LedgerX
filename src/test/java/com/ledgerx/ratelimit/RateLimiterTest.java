package com.ledgerx.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class RateLimiterTest {

    private final AtomicLong clock = new AtomicLong();
    private final UUID client = UUID.randomUUID();

    private void advanceSeconds(double seconds) {
        clock.addAndGet((long) (seconds * 1_000_000_000L));
    }

    @Test
    void allowsABurstThenRejects() {
        RateLimiter limiter = new RateLimiter(true, 1, 3, clock::get);

        assertThat(limiter.tryAcquire(client).allowed()).isTrue();
        assertThat(limiter.tryAcquire(client).allowed()).isTrue();
        assertThat(limiter.tryAcquire(client).allowed()).isTrue();
        RateLimiter.Decision rejected = limiter.tryAcquire(client);

        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.retryAfterSeconds()).isEqualTo(1);
    }

    @Test
    void refillsOverTimeButNeverBeyondTheBurst() {
        RateLimiter limiter = new RateLimiter(true, 2, 4, clock::get);
        for (int i = 0; i < 4; i++) {
            limiter.tryAcquire(client);
        }
        assertThat(limiter.tryAcquire(client).allowed()).isFalse();

        advanceSeconds(0.5); // one token at 2 per second
        assertThat(limiter.tryAcquire(client).allowed()).isTrue();
        assertThat(limiter.tryAcquire(client).allowed()).isFalse();

        advanceSeconds(3600); // a long idle period only refills up to the burst
        int allowed = 0;
        while (limiter.tryAcquire(client).allowed()) {
            allowed++;
        }
        assertThat(allowed).isEqualTo(4);
    }

    @Test
    void retryAfterReflectsTheMissingTokens() {
        RateLimiter limiter = new RateLimiter(true, 0.5, 1, clock::get); // one token every two seconds
        limiter.tryAcquire(client);

        assertThat(limiter.tryAcquire(client).retryAfterSeconds()).isEqualTo(2);
        advanceSeconds(1.5);
        assertThat(limiter.tryAcquire(client).retryAfterSeconds()).isEqualTo(1);
    }

    @Test
    void clientsHaveIndependentBuckets() {
        RateLimiter limiter = new RateLimiter(true, 1, 1, clock::get);
        UUID other = UUID.randomUUID();

        assertThat(limiter.tryAcquire(client).allowed()).isTrue();
        assertThat(limiter.tryAcquire(client).allowed()).isFalse();
        assertThat(limiter.tryAcquire(other).allowed()).isTrue();
    }

    @Test
    void disabledLimiterAllowsEverything() {
        RateLimiter limiter = new RateLimiter(false, 1, 1, clock::get);

        for (int i = 0; i < 1000; i++) {
            assertThat(limiter.tryAcquire(client).allowed()).isTrue();
        }
    }

    @Test
    void concurrentCallersNeverExceedTheBurst() throws Exception {
        RateLimiter limiter = new RateLimiter(true, 0, 100, clock::get); // no refill: exactly 100 may pass
        AtomicInteger allowed = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        java.util.List<Future<?>> futures = new java.util.ArrayList<>();
        for (int t = 0; t < 8; t++) {
            futures.add(pool.submit(() -> {
                for (int i = 0; i < 1000; i++) {
                    if (limiter.tryAcquire(client).allowed()) {
                        allowed.incrementAndGet();
                    }
                }
            }));
        }
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(allowed.get()).isEqualTo(100);
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThatThrownBy(() -> new RateLimiter(true, -1, 1, clock::get)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RateLimiter(true, 1, 0, clock::get)).isInstanceOf(IllegalArgumentException.class);
    }
}
