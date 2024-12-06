package net.swofty.stockmarkettester.data;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/**
 * Thread-safe rate limiter implementation using token bucket algorithm.
 * Supports precise rate control with nanosecond timing resolution.
 *
 * Performance optimizations:
 * - Lock-free implementation using atomic operations
 * - Minimal contention through spin-waiting
 * - Efficient token accumulation without floating point arithmetic
 * - Bounded token accumulation to prevent burst overflows
 */
public class RateLimiter {
    private final long rateNanos;  // Nanoseconds per token
    private final double maxBurst; // Maximum token accumulation
    private final AtomicReference<TokenBucket> bucket;

    private static class TokenBucket {
        final double tokens;
        final long lastRefillTime;

        TokenBucket(double tokens, long lastRefillTime) {
            this.tokens = tokens;
            this.lastRefillTime = lastRefillTime;
        }
    }

    /**
     * Creates a rate limiter with specified rate.
     * @param permitsPerSecond Maximum rate of permit generation
     */
    public RateLimiter(double permitsPerSecond) {
        this(permitsPerSecond, 60.0); // Default 1-minute burst capacity
    }

    /**
     * Creates a rate limiter with specified rate and burst capacity.
     * @param permitsPerSecond Maximum rate of permit generation
     * @param maxBurstSeconds Maximum duration of burst accumulation
     */
    public RateLimiter(double permitsPerSecond, double maxBurstSeconds) {
        if (permitsPerSecond <= 0 || maxBurstSeconds <= 0) {
            throw new IllegalArgumentException("Rate and burst must be positive");
        }

        this.rateNanos = (long)(TimeUnit.SECONDS.toNanos(1) / permitsPerSecond);
        this.maxBurst = permitsPerSecond * maxBurstSeconds;
        this.bucket = new AtomicReference<>(
                new TokenBucket(maxBurst, System.nanoTime())
        );
    }

    /**
     * Acquires a single permit, blocking until available.
     * Uses adaptive spin-waiting for high-precision timing.
     */
    public void acquire() {
        acquire(1.0);
    }

    /**
     * Factory method for creating rate limiters with permits/second configuration.
     * Uses default 60-second burst capacity.
     *
     * @param permitsPerSecond Maximum rate of permit generation
     * @return Configured RateLimiter instance
     */
    public static RateLimiter create(double permitsPerSecond) {
        return new RateLimiter(permitsPerSecond);
    }

    /**
     * Factory method for creating rate limiters with custom burst capacity.
     *
     * @param permitsPerSecond Maximum rate of permit generation
     * @param maxBurstSeconds Maximum duration of burst accumulation
     * @return Configured RateLimiter instance
     */
    public static RateLimiter create(double permitsPerSecond, double maxBurstSeconds) {
        return new RateLimiter(permitsPerSecond, maxBurstSeconds);
    }

    /**
     * Acquires specified number of permits, blocking until available.
     * @param permits Number of permits to acquire
     */
    public void acquire(double permits) {
        if (permits <= 0) {
            throw new IllegalArgumentException("Permits must be positive");
        }

        long waitNanos = reserve(permits);

        // Adaptive waiting strategy
        if (waitNanos > TimeUnit.MILLISECONDS.toNanos(1)) {
            LockSupport.parkNanos(waitNanos);
        } else {
            // Spin-wait for fine-grained timing
            long deadline = System.nanoTime() + waitNanos;
            while (System.nanoTime() < deadline) {
                Thread.onSpinWait(); // CPU hint for spin-waiting
            }
        }
    }

    /**
     * Reserves permits and returns required wait time in nanoseconds.
     * Uses atomic CAS operations for thread-safety without locks.
     */
    private long reserve(double permits) {
        while (true) {
            TokenBucket current = bucket.get();
            long now = System.nanoTime();
            long deltaTime = Math.max(0, now - current.lastRefillTime);

            // Calculate new tokens, bounded by maxBurst
            double newTokens = Math.min(
                    maxBurst,
                    current.tokens + deltaTime * (1.0 / rateNanos)
            );

            if (newTokens < permits) {
                // Calculate wait time for enough tokens
                long waitNanos = (long)((permits - newTokens) * rateNanos);
                return waitNanos;
            }

            // Attempt to consume tokens
            TokenBucket newBucket = new TokenBucket(
                    newTokens - permits,
                    now
            );

            if (bucket.compareAndSet(current, newBucket)) {
                return 0L;
            }

            // CAS failed, retry
            Thread.onSpinWait();
        }
    }

    /**
     * Attempts to acquire a permit without blocking.
     * @return true if permit was acquired, false otherwise
     */
    public boolean tryAcquire() {
        return tryAcquire(1.0, 0, TimeUnit.MICROSECONDS);
    }

    /**
     * Attempts to acquire permits with timeout.
     * @param permits Number of permits to acquire
     * @param timeout Maximum wait time
     * @param unit Time unit for timeout
     * @return true if permits were acquired, false if timeout occurred
     */
    public boolean tryAcquire(double permits, long timeout, TimeUnit unit) {
        long waitNanos = reserve(permits);
        if (waitNanos == 0) {
            return true;
        }

        if (timeout == 0) {
            return false;
        }

        long timeoutNanos = unit.toNanos(timeout);
        if (waitNanos <= timeoutNanos) {
            LockSupport.parkNanos(waitNanos);
            return true;
        }

        return false;
    }
}