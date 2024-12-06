package net.swofty.stockmarkettester.data;

import lombok.Getter;
import lombok.NonNull;
import net.swofty.stockmarkettester.MarketConfig;
import net.swofty.stockmarkettester.orders.HistoricalData;
import org.jetbrains.annotations.Nullable;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.time.Duration;

/**
 * Core interface for market data provider implementations with non-blocking async operations.
 * Supports real-time and historical data retrieval with configurable rate limiting and timeout policies.
 */
public interface MarketDataProvider {
    /**
     * Asynchronously fetches real-time market data for specified tickers.
     * Implementations must handle rate limiting and connection pooling internally.
     *
     * @param tickers Set of ticker symbols to fetch
     * @return CompletableFuture containing market data response
     * @throws MarketDataException if provider encounters unrecoverable error
     */
    CompletableFuture<MarketDataResponse> fetchRealTimeData(Set<String> tickers);

    /**
     * Asynchronously fetches historical market data within specified time range.
     *
     * @param tickers Set of ticker symbols to fetch
     * @param start Start timestamp (inclusive)
     * @param end End timestamp (inclusive)
     * @return CompletableFuture containing historical data response
     * @throws MarketDataException if provider encounters unrecoverable error
     */
    CompletableFuture<HistoricalData> fetchHistoricalData(
            Set<String> tickers,
            LocalDateTime start,
            LocalDateTime end,
            MarketConfig marketConfig
    );

    /**
     * Configures rate limiting parameters for the provider.
     *
     * @param rateLimit Maximum requests per time unit
     * @param duration Time unit for rate limit
     */
    default void configureRateLimit(int rateLimit, Duration duration) {
        // Default implementation uses token bucket algorithm
        throw new UnsupportedOperationException("Rate limiting not implemented");
    }

    /**
     * Configures retry policy for failed requests.
     *
     * @param maxRetries Maximum number of retry attempts
     * @param retryPredicate Predicate determining if error is retryable
     * @param backoffStrategy Strategy for calculating retry delays
     */
    default void configureRetryPolicy(
            int maxRetries,
            Predicate<Throwable> retryPredicate,
            BackoffStrategy backoffStrategy
    ) {
        throw new UnsupportedOperationException("Retry policy not implemented");
    }

    /**
     * Checks provider availability and API health.
     *
     * @return true if provider is operational
     */
    boolean isAvailable();

    /**
     * Returns current rate limit configuration.
     *
     * @return Requests per time unit allowed
     */
    int getRateLimit();

    /**
     * Initializes provider resources and connections.
     * Must be called before making any data requests.
     *
     * @throws MarketDataException if initialization fails
     */
    default void initialize() {
        // Default no-op implementation
    }

    /**
     * Releases provider resources and connections.
     * Must be called when provider is no longer needed.
     */
    default void shutdown() {
        // Default no-op implementation
    }

    /**
     * Enumeration of supported backoff strategies for retry handling.
     */
    enum BackoffStrategy {
        FIXED,          // Fixed delay between retries
        EXPONENTIAL,    // Exponentially increasing delays
        FIBONACCI,      // Fibonacci sequence delays
        RANDOM_JITTER   // Random jitter added to base delay
    }

    /**
     * Provider capabilities descriptor for runtime feature detection.
     */
    interface Capabilities {
        boolean supportsHistorical();
        Duration dataGranularity();
    }

    /**
     * Returns provider capabilities for feature detection.
     *
     * @return Provider capabilities descriptor
     */
    Capabilities getCapabilities();

    /**
     * Exception thrown for market data provider errors.
     */
    @Getter
    class MarketDataException extends RuntimeException {
        private final boolean retryable;
        private final String providerCode;

        public MarketDataException(String message, Throwable cause, boolean retryable, String providerCode) {
            super(message, cause);
            this.retryable = retryable;
            this.providerCode = providerCode;
        }

        public MarketDataException(String message, Throwable cause) {
            super(message, cause);

            this.retryable = false;
            this.providerCode = null;
        }

    }
}