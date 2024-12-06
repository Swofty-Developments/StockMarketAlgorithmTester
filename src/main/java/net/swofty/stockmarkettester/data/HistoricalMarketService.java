package net.swofty.stockmarkettester.data;

import lombok.Getter;
import net.swofty.stockmarkettester.MarketConfig;
import net.swofty.stockmarkettester.orders.HistoricalData;
import net.swofty.stockmarkettester.orders.MarketDataPoint;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

public class HistoricalMarketService implements AutoCloseable {
    private final Map<String, HistoricalData> historicalCache;
    @Getter
    private final MarketDataProvider provider;

    private final ExecutorService requestExecutor;
    private final ExecutorService fetchExecutor;

    private final int maxRetries;
    private volatile boolean isInitialized = false;
    private final Object initLock = new Object();
    private final Optional<Path> cacheDirectory;
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    public HistoricalMarketService(MarketDataProvider provider, int maxRetries, Path cacheDirectory) {
        this.provider = provider;
        this.historicalCache = new ConcurrentHashMap<>();
        this.requestExecutor = Executors.newSingleThreadExecutor();
        this.fetchExecutor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
        this.maxRetries = maxRetries;
        this.cacheDirectory = Optional.ofNullable(cacheDirectory);

        if (this.cacheDirectory.isPresent()) {
            try {
                Files.createDirectories(this.cacheDirectory.get());
            } catch (IOException e) {
                throw new RuntimeException("Failed to create cache directory", e);
            }
        }
    }

    public HistoricalMarketService(MarketDataProvider provider, int maxRetries) {
        this(provider, maxRetries, null);
    }

    private Path getCacheFilePath(String ticker, LocalDateTime start, LocalDateTime end) {
        if (cacheDirectory.isEmpty()) {
            throw new IllegalStateException("Cache directory not configured");
        }
        String filename = String.format("%s_%s_to_%s.cache",
                ticker,
                start.format(DATE_FORMAT),
                end.format(DATE_FORMAT));
        return cacheDirectory.get().resolve(filename);
    }

    private void saveToCache(String ticker, HistoricalData data, LocalDateTime start, LocalDateTime end) {
        if (cacheDirectory.isEmpty()) return;

        Path cacheFile = getCacheFilePath(ticker, start, end);
        try (ObjectOutputStream oos = new ObjectOutputStream(Files.newOutputStream(cacheFile))) {
            oos.writeObject(data);
        } catch (IOException e) {
            System.err.println("Failed to save cache for " + ticker + ": " + e.getMessage());
        }
    }

    private HistoricalData loadFromCache(String ticker, LocalDateTime start, LocalDateTime end) {
        if (cacheDirectory.isEmpty()) return null;

        Path cacheFile = getCacheFilePath(ticker, start, end);
        if (!Files.exists(cacheFile)) {
            return null;
        }

        try (ObjectInputStream ois = new ObjectInputStream(Files.newInputStream(cacheFile))) {
            return (HistoricalData) ois.readObject();
        } catch (IOException | ClassNotFoundException e) {
            System.err.println("Failed to load cache for " + ticker + ": " + e.getMessage());
            try {
                Files.deleteIfExists(cacheFile); // Delete corrupted cache file
            } catch (IOException ignored) {}
            return null;
        }
    }

    public CompletableFuture<Void> initialize(Set<String> tickers, int previousDays, MarketConfig marketConfig) {
        return CompletableFuture.runAsync(() -> {
            synchronized (initLock) {
                if (isInitialized) {
                    return;
                }

                LocalDateTime end = LocalDateTime.now();
                LocalDateTime start = end.minusDays(previousDays);
                System.out.println("Fetching historical data for " + tickers.size() + " tickers");

                for (String ticker : tickers) {
                    boolean success = false;
                    int attempts = 0;

                    // Try to load from cache if caching is enabled
                    if (cacheDirectory.isPresent()) {
                        HistoricalData cachedData = loadFromCache(ticker, start, end);
                        if (cachedData != null) {
                            historicalCache.put(ticker, cachedData);
                            System.out.println("Loaded cached data for " + ticker);
                            success = true;
                            continue;
                        }
                    }

                    while (!success && attempts < maxRetries) {
                        try {
                            if (provider.isAvailable()) {
                                System.out.println("Fetching historical data for " + ticker);
                                HistoricalData data = provider.fetchHistoricalData(
                                        Set.of(ticker), start, end, marketConfig).get();
                                historicalCache.put(ticker, data);

                                long cooldown = (60 / provider.getRateLimit()) * 1000;

                                // Save to cache if enabled
                                if (cacheDirectory.isPresent()) {
                                    saveToCache(ticker, data, start, end);
                                    System.out.println("Successfully fetched and cached data for " + ticker);
                                } else {
                                    System.out.println("Successfully fetched data for " + ticker);
                                }

                                success = true;
                                int amountOfTickersLeft = tickers.size() - attempts;
                                if (amountOfTickersLeft > 0) {
                                    System.out.println("Waiting " + cooldown + "ms due to rate limits");
                                    Thread.sleep(cooldown);
                                }
                            } else {
                                throw new RuntimeException("Provider is not available");
                            }
                        } catch (Exception e) {
                            attempts++;
                            if (attempts >= maxRetries) {
                                e.printStackTrace();
                                throw new RuntimeException("Failed to fetch data for " + ticker, e);
                            }
                            try {
                                Thread.sleep(5000L * attempts);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                throw new RuntimeException("Interrupted while fetching data", ie);
                            }
                        }
                    }
                }

                isInitialized = true;
            }
        }, requestExecutor);
    }

    public CompletableFuture<Map<String, List<MarketDataPoint>>> fetchHistoricalData(
            Set<String> tickers,
            LocalDateTime start,
            LocalDateTime end) {

        if (!isInitialized) {
            throw new IllegalStateException("HistoricalMarketService not initialized");
        }

        Map<String, List<MarketDataPoint>> result = new ConcurrentHashMap<>();
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (String ticker : tickers) {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    HistoricalData cachedData = historicalCache.get(ticker);
                    if (cachedData == null && cacheDirectory.isPresent()) {
                        cachedData = loadFromCache(ticker, start, end);
                        if (cachedData == null) {
                            throw new IllegalStateException("No cached data for ticker: " + ticker);
                        }
                        historicalCache.put(ticker, cachedData);
                    }
                    if (cachedData != null) {
                        result.put(ticker, cachedData.getDataPoints(start, end));
                    } else {
                        throw new IllegalStateException("No data available for ticker: " + ticker);
                    }
                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            }, fetchExecutor);

            futures.add(future);
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> result);
    }

    public void clearCache() {
        if (cacheDirectory.isEmpty()) return;

        try {
            Files.walk(cacheDirectory.get())
                    .filter(Files::isRegularFile)
                    .forEach(file -> {
                        try {
                            Files.delete(file);
                        } catch (IOException e) {
                            System.err.println("Failed to delete cache file: " + file);
                        }
                    });
        } catch (IOException e) {
            System.err.println("Failed to clear cache directory: " + e.getMessage());
        }
    }

    @Override
    public void close() {
        requestExecutor.shutdown();
        fetchExecutor.shutdown();
        try {
            if (!requestExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                requestExecutor.shutdownNow();
            }
            if (!fetchExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                fetchExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            requestExecutor.shutdownNow();
            fetchExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}