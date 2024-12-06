package net.swofty.stockmarkettester.data;

import net.swofty.stockmarkettester.orders.MarketDataPoint;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Immutable container for market data responses with thread-safe access patterns.
 * Optimizes memory usage through intelligent data structures and implements
 * value semantics for reliable distributed operations.
 */
public final class MarketDataResponse {
    private final Map<String, MarketDataPoint> dataPoints;
    private final LocalDateTime timestamp;
    private final ResponseMetadata metadata;
    private volatile int hashCode; // Cache for concurrent access optimization

    /**
     * Constructs response with deep defensive copying for immutability guarantee.
     * @param dataPoints Map of ticker to market data points
     * @param timestamp Response timestamp with nanosecond precision
     */
    public MarketDataResponse(Map<String, MarketDataPoint> dataPoints, LocalDateTime timestamp) {
        this(dataPoints, timestamp, new ResponseMetadata());
    }

    /**
     * Full constructor with metadata for advanced tracking capabilities.
     * @param dataPoints Market data points keyed by ticker
     * @param timestamp High-precision timestamp
     * @param metadata Response metadata for diagnostics
     */
    public MarketDataResponse(
            Map<String, MarketDataPoint> dataPoints,
            LocalDateTime timestamp,
            ResponseMetadata metadata) {
        // Defensive copy using ConcurrentHashMap for thread-safe reads
        this.dataPoints = Collections.unmodifiableMap(
                new ConcurrentHashMap<>(Objects.requireNonNull(dataPoints, "dataPoints"))
        );
        this.timestamp = Objects.requireNonNull(timestamp, "timestamp");
        this.metadata = Objects.requireNonNull(metadata, "metadata");
    }

    /**
     * Returns unmodifiable view of market data points.
     * @return Thread-safe, immutable map of market data
     */
    public Map<String, MarketDataPoint> dataPoints() {
        return dataPoints;
    }

    /**
     * Retrieves specific data point with null-safety guarantee.
     * @param ticker Stock ticker symbol
     * @return MarketDataPoint or null if not found
     */
    public MarketDataPoint getDataPoint(String ticker) {
        return dataPoints.get(Objects.requireNonNull(ticker, "ticker"));
    }

    public LocalDateTime timestamp() {
        return timestamp;
    }

    public ResponseMetadata metadata() {
        return metadata;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MarketDataResponse)) return false;
        MarketDataResponse that = (MarketDataResponse) o;
        return Objects.equals(dataPoints, that.dataPoints) &&
                Objects.equals(timestamp, that.timestamp) &&
                Objects.equals(metadata, that.metadata);
    }

    @Override
    public int hashCode() {
        int result = hashCode;
        if (result == 0) {
            result = Objects.hash(dataPoints, timestamp, metadata);
            hashCode = result;
        }
        return result;
    }

    /**
     * Metadata container for response diagnostics and tracking.
     */
    public static class ResponseMetadata {
        private final long requestDurationMs;
        private final int requestAttempts;
        private final String provider;
        private final Map<String, String> headers;

        public ResponseMetadata() {
            this(0, 1, "unknown", Collections.emptyMap());
        }

        public ResponseMetadata(
                long requestDurationMs,
                int requestAttempts,
                String provider,
                Map<String, String> headers) {
            this.requestDurationMs = requestDurationMs;
            this.requestAttempts = requestAttempts;
            this.provider = Objects.requireNonNull(provider);
            this.headers = Collections.unmodifiableMap(new HashMap<>(headers));
        }

        public long requestDurationMs() { return requestDurationMs; }
        public int requestAttempts() { return requestAttempts; }
        public String provider() { return provider; }
        public Map<String, String> headers() { return headers; }
    }

    /**
     * Builder pattern implementation for flexible response construction.
     */
    public static class Builder {
        private final Map<String, MarketDataPoint> dataPoints = new HashMap<>();
        private LocalDateTime timestamp;
        private long requestDurationMs;
        private int requestAttempts = 1;
        private String provider = "unknown";
        private final Map<String, String> headers = new HashMap<>();

        public Builder withDataPoint(String ticker, MarketDataPoint point) {
            dataPoints.put(ticker, point);
            return this;
        }

        public Builder withTimestamp(LocalDateTime timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public Builder withRequestDuration(long durationMs) {
            this.requestDurationMs = durationMs;
            return this;
        }

        public Builder withRequestAttempts(int attempts) {
            this.requestAttempts = attempts;
            return this;
        }

        public Builder withProvider(String provider) {
            this.provider = provider;
            return this;
        }

        public Builder withHeader(String key, String value) {
            headers.put(key, value);
            return this;
        }

        public MarketDataResponse build() {
            if (timestamp == null) {
                timestamp = LocalDateTime.now();
            }

            ResponseMetadata metadata = new ResponseMetadata(
                    requestDurationMs,
                    requestAttempts,
                    provider,
                    headers
            );

            return new MarketDataResponse(dataPoints, timestamp, metadata);
        }
    }
}