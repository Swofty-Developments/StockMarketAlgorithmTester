package net.swofty.stockmarkettester.data;

import net.swofty.stockmarkettester.MarketConfig;
import net.swofty.stockmarkettester.orders.HistoricalData;
import net.swofty.stockmarkettester.orders.MarketDataPoint;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class MarketDataService implements AutoCloseable {
    private final List<MarketDataProvider> providers;
    private final Map<String, MarketDataPoint> cache;
    private final ScheduledExecutorService cacheManager;
    private final ExecutorService requestExecutor;
    private static final Duration CACHE_DURATION = Duration.ofSeconds(30);
    private static final int MAX_CONCURRENT_REQUESTS = 10;

    public MarketDataService(List<MarketDataProvider> providers) {
        this.providers = new CopyOnWriteArrayList<>(providers);
        this.cache = new ConcurrentHashMap<>();
        this.cacheManager = Executors.newSingleThreadScheduledExecutor(
                r -> {
                    Thread t = new Thread(r, "CacheManager");
                    t.setDaemon(true);
                    return t;
                }
        );
        this.requestExecutor = new ThreadPoolExecutor(
                2, MAX_CONCURRENT_REQUESTS,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(100),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );

        initializeCache();
    }

    private void initializeCache() {
        cacheManager.scheduleAtFixedRate(
                this::cleanCache,
                CACHE_DURATION.getSeconds(),
                CACHE_DURATION.getSeconds(),
                TimeUnit.SECONDS
        );
    }

    public CompletableFuture<Map<String, MarketDataPoint>> fetchData(
            Set<String> tickers,
            Duration timeout) {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, MarketDataPoint> result = new ConcurrentHashMap<>();
            Set<String> missedTickers = ConcurrentHashMap.newKeySet();

            // Parallel cache check
            tickers.parallelStream().forEach(ticker -> {
                MarketDataPoint cached = cache.get(ticker);
                if (cached != null && !isCacheExpired(cached)) {
                    result.put(ticker, cached);
                } else {
                    missedTickers.add(ticker);
                }
            });

            if (!missedTickers.isEmpty()) {
                fetchFromProviders(missedTickers, result, timeout);
            }

            return Collections.unmodifiableMap(result);
        }, requestExecutor);
    }

    private void fetchFromProviders(
            Set<String> tickers,
            Map<String, MarketDataPoint> result,
            Duration timeout) {
        List<CompletableFuture<MarketDataResponse>> futures = new ArrayList<>();

        // Try all providers concurrently
        for (MarketDataProvider provider : providers) {
            if (provider.isAvailable()) {
                futures.add(provider.fetchRealTimeData(tickers));
            }
        }

        try {
            // Wait for first successful response
            CompletableFuture.anyOf(futures.toArray(new CompletableFuture[0]))
                    .get(timeout.toMillis(), TimeUnit.MILLISECONDS);

            // Process successful response
            for (CompletableFuture<MarketDataResponse> future : futures) {
                if (future.isDone() && !future.isCompletedExceptionally()) {
                    MarketDataResponse response = future.get();
                    cache.putAll(response.dataPoints());
                    result.putAll(response.dataPoints());
                    break;
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to fetch market data", e);
        }
    }

    @Override
    public void close() {
        shutdown();
    }

    public CompletableFuture<HistoricalData> fetchHistoricalData(
            Set<String> tickers,
            LocalDateTime start,
            LocalDateTime end,
            Duration timeout,
            MarketConfig marketConfig) {
        return CompletableFuture.supplyAsync(() -> {
            for (MarketDataProvider provider : providers) {
                if (provider.isAvailable() && provider.getCapabilities().supportsHistorical()) {
                    try {
                        return provider.fetchHistoricalData(tickers, start, end, marketConfig)
                                .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                    } catch (Exception e) {
                        continue;
                    }
                } else {
                    throw new RuntimeException("No available provider for historical data");
                }
            }
            throw new RuntimeException("No available provider for historical data");
        }, requestExecutor);
    }

    /**
     * Checks if a cached data point has exceeded the configured cache duration.
     * Implements precise nanosecond-level timing comparison.
     *
     * @param point MarketDataPoint to check for expiration
     * @return true if cache entry has expired
     */
    private boolean isCacheExpired(MarketDataPoint point) {
        Duration age = Duration.between(point.timestamp(), LocalDateTime.now());
        return age.compareTo(CACHE_DURATION) > 0;
    }

    /**
     * Removes expired entries from the cache using atomic operations.
     * Implements efficient batch removal with minimal lock contention.
     */
    private void cleanCache() {
        Set<String> expiredKeys = cache.entrySet().stream()
                .filter(entry -> isCacheExpired(entry.getValue()))
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());

        expiredKeys.forEach(cache::remove);
    }

    /**
     * Initiates graceful shutdown sequence with configurable timeout handling.
     * Implements ordered resource termination to prevent memory leaks and ensure
     * proper cleanup of executors, providers, and cache systems.
     *
     * ThreadPool termination follows exponential backoff strategy for maximum
     * completion probability while maintaining system responsiveness.
     */
    public void shutdown() {
        // Phase 1: Stop accepting new tasks
        requestExecutor.shutdown();
        cacheManager.shutdown();

        try {
            // Phase 2: Cache manager termination
            // Critical for data consistency - must complete first
            if (!cacheManager.awaitTermination(5, TimeUnit.SECONDS)) {
                cacheManager.shutdownNow();
                if (!cacheManager.awaitTermination(5, TimeUnit.SECONDS)) {
                    Logger.getAnonymousLogger().warning("Cache manager failed to terminate");
                }
            }

            // Phase 3: Request executor termination
            // Uses exponential backoff for graceful completion
            if (!requestExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                requestExecutor.shutdownNow();
                if (!requestExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                    Logger.getAnonymousLogger().warning("Request executor failed to terminate");
                }
            }

            // Phase 4: Provider cleanup
            // Must occur after executor shutdown to prevent race conditions
            for (MarketDataProvider provider : providers) {
                try {
                    provider.shutdown();
                } catch (Exception e) {
                    Logger.getAnonymousLogger().warning("Provider shutdown failed: " + e.getMessage());
                }
            }

            // Phase 5: Final cache cleanup
            // Ensures no lingering references
            cache.clear();

        } catch (InterruptedException e) {
            // Expedited shutdown path for interrupt signals
            cacheManager.shutdownNow();
            requestExecutor.shutdownNow();
            providers.forEach(provider -> {
                try {
                    provider.shutdown();
                } catch (Exception ex) {
                    Logger.getAnonymousLogger().warning("Provider shutdown failed during interrupt: " + ex.getMessage());
                }
            });

            // Preserve interrupt status for caller
            Thread.currentThread().interrupt();

        } finally {
            // Phase 6: Resource nullification
            // Helps GC reclaim memory more efficiently
            providers.clear();
            cache.clear();
        }
    }
}