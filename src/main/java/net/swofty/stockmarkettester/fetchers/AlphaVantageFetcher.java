package net.swofty.stockmarkettester.fetchers;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import net.swofty.stockmarkettester.exceptions.MarketDataException;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.time.Duration;
import java.util.logging.Logger;

public class AlphaVantageFetcher {
    private static String apiKey;
    private static Path cacheDirectory;
    private static HttpClient client;
    private static ObjectMapper mapper;
    private static boolean isInitialized = false;

    private final LocalDateTime asOfDate;
    private static final String BASE_URL = "https://www.alphavantage.co/query";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final Map<String, CacheEntry<List<NewsSentiment>>> sentimentCache = new ConcurrentHashMap<>();

    // Cache structures
    private static final Map<String, CacheEntry<List<EarningsEvent>>> earningsCache = new ConcurrentHashMap<>();
    private static final Map<String, CacheEntry<FinancialMetrics>> metricsCache = new ConcurrentHashMap<>();
    private static final Map<String, CacheEntry<IncomeStatement>> incomeCache = new ConcurrentHashMap<>();

    public static void setup(String apiKeyInput, Path cachePath) {
        if (isInitialized) {
            throw new IllegalStateException("AlphaVantageFetcher already initialized");
        }

        apiKey = apiKeyInput;
        cacheDirectory = cachePath;
        client = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .build();
        mapper = new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT);

        // Create cache directory if it doesn't exist
        try {
            Files.createDirectories(cacheDirectory);
            loadCacheFromDisk();
        } catch (IOException e) {
            throw new RuntimeException("Failed to create cache directory", e);
        }

        isInitialized = true;
    }

    public AlphaVantageFetcher(LocalDateTime asOfDate) {
        if (!isInitialized) {
            throw new IllegalStateException("AlphaVantageFetcher not initialized. Call setup() first.");
        }
        this.asOfDate = asOfDate;
    }

    private static void loadCacheFromDisk() {
        try {
            Path earningsPath = cacheDirectory.resolve("earnings_cache.json");
            Path metricsPath = cacheDirectory.resolve("metrics_cache.json");
            Path incomePath = cacheDirectory.resolve("income_cache.json");
            Path sentimentPath = cacheDirectory.resolve("sentiment_cache.json");

            if (Files.exists(earningsPath)) {
                JsonNode node = mapper.readTree(earningsPath.toFile());
                node.fields().forEachRemaining(entry -> {
                    CacheEntry<List<EarningsEvent>> cacheEntry = mapper.convertValue(entry.getValue(),
                            mapper.getTypeFactory().constructParametricType(CacheEntry.class,
                                    mapper.getTypeFactory().constructCollectionType(List.class, EarningsEvent.class)));
                    earningsCache.put(entry.getKey(), cacheEntry);
                });
            }

            if (Files.exists(metricsPath)) {
                JsonNode node = mapper.readTree(metricsPath.toFile());
                node.fields().forEachRemaining(entry -> {
                    CacheEntry<FinancialMetrics> cacheEntry = mapper.convertValue(entry.getValue(),
                            mapper.getTypeFactory().constructParametricType(CacheEntry.class, FinancialMetrics.class));
                    metricsCache.put(entry.getKey(), cacheEntry);
                });
            }

            if (Files.exists(incomePath)) {
                JsonNode node = mapper.readTree(incomePath.toFile());
                node.fields().forEachRemaining(entry -> {
                    CacheEntry<IncomeStatement> cacheEntry = mapper.convertValue(entry.getValue(),
                            mapper.getTypeFactory().constructParametricType(CacheEntry.class, IncomeStatement.class));
                    incomeCache.put(entry.getKey(), cacheEntry);
                });
            }

            if (Files.exists(sentimentPath)) {
                JsonNode node = mapper.readTree(sentimentPath.toFile());
                node.fields().forEachRemaining(entry -> {
                    CacheEntry<List<NewsSentiment>> cacheEntry = mapper.convertValue(entry.getValue(),
                            mapper.getTypeFactory().constructParametricType(CacheEntry.class,
                                    mapper.getTypeFactory().constructCollectionType(List.class, NewsSentiment.class)));
                    sentimentCache.put(entry.getKey(), cacheEntry);
                });
            }
        } catch (IOException e) {
            // Log error but continue - worst case we start with empty cache
            System.err.println("Failed to load cache from disk: " + e.getMessage());
        }
    }

    private static void saveCacheToDisk() {
        try {
            mapper.writeValue(cacheDirectory.resolve("earnings_cache.json").toFile(), earningsCache);
            mapper.writeValue(cacheDirectory.resolve("metrics_cache.json").toFile(), metricsCache);
            mapper.writeValue(cacheDirectory.resolve("income_cache.json").toFile(), incomeCache);
            mapper.writeValue(cacheDirectory.resolve("sentiment_cache.json").toFile(), sentimentCache);
        } catch (IOException e) {
            System.err.println("Failed to save cache to disk: " + e.getMessage());
        }
    }

    public CompletableFuture<List<EarningsEvent>> getEarningsCalls(String symbol) {
        return CompletableFuture.supplyAsync(() -> {
            // Check cache first
            CacheEntry<List<EarningsEvent>> cached = earningsCache.get(symbol);
            if (cached != null && !cached.isExpired()) {
                return cached.data().stream()
                        .filter(event -> event.reportDate().isBefore(asOfDate))
                        .toList();
            }

            try {
                String url = String.format("%s?function=EARNINGS_CALENDAR&symbol=%s&horizon=12month&apikey=%s",
                        BASE_URL, symbol, apiKey);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(TIMEOUT)
                        .GET()
                        .build();

                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                List<EarningsEvent> events = new ArrayList<>();

                // Parse CSV response
                String[] lines = response.body().split("\n");
                for (int i = 1; i < lines.length; i++) {
                    String[] fields = lines[i].split(",");
                    if (fields.length >= 4) {
                        LocalDateTime reportDate = LocalDateTime.parse(fields[2] + "T00:00:00");
                        events.add(new EarningsEvent(
                                fields[0],
                                fields[1],
                                reportDate,
                                fields[3]
                        ));
                    }
                }

                // Cache the full results
                earningsCache.put(symbol, new CacheEntry<>(events));
                saveCacheToDisk();

                // Return filtered results
                return events.stream()
                        .filter(event -> event.reportDate().isBefore(asOfDate))
                        .toList();

            } catch (Exception e) {
                throw new RuntimeException("Failed to fetch earnings calls", e);
            }
        });
    }

    public CompletableFuture<FinancialMetrics> getFinancialMetrics(String symbol) {
        return CompletableFuture.supplyAsync(() -> {
            // Check cache first
            CacheEntry<FinancialMetrics> cached = metricsCache.get(symbol);
            if (cached != null && !cached.isExpired()) {
                return cached.data();
            }

            try {
                String url = String.format("%s?function=OVERVIEW&symbol=%s&apikey=%s",
                        BASE_URL, symbol, apiKey);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(TIMEOUT)
                        .GET()
                        .build();

                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                JsonNode root = mapper.readTree(response.body());

                FinancialMetrics metrics = new FinancialMetrics(
                        symbol,
                        root.get("PERatio").asDouble(),
                        root.get("ProfitMargin").asDouble(),
                        root.get("OperatingMarginTTM").asDouble(),
                        root.get("ReturnOnEquityTTM").asDouble()
                );

                // Cache the results
                metricsCache.put(symbol, new CacheEntry<>(metrics));
                saveCacheToDisk();

                return metrics;
            } catch (Exception e) {
                throw new RuntimeException("Failed to fetch financial metrics for " + symbol, e);
            }
        });
    }

    public CompletableFuture<IncomeStatement> getIncomeStatement(String symbol) {
        return CompletableFuture.supplyAsync(() -> {
            // Check cache first
            CacheEntry<IncomeStatement> cached = incomeCache.get(symbol);
            if (cached != null && !cached.isExpired()) {
                return filterIncomeStatement(cached.data());
            }

            try {
                String url = String.format("%s?function=INCOME_STATEMENT&symbol=%s&apikey=%s",
                        BASE_URL, symbol, apiKey);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(TIMEOUT)
                        .GET()
                        .build();

                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                JsonNode root = mapper.readTree(response.body());
                JsonNode statements = root.get("quarterlyReports");

                List<Map<String, Double>> quarterlyData = new ArrayList<>();
                for (JsonNode statement : statements) {
                    Map<String, Double> metrics = new HashMap<>();
                    metrics.put("totalRevenue", statement.get("totalRevenue").asDouble());
                    metrics.put("grossProfit", statement.get("grossProfit").asDouble());
                    metrics.put("netIncome", statement.get("netIncome").asDouble());
                    metrics.put("operatingIncome", statement.get("operatingIncome").asDouble());
                    metrics.put("reportDate", LocalDateTime.parse(statement.get("fiscalDateEnding").asText() + "T00:00:00")
                            .toEpochSecond(java.time.ZoneOffset.UTC) * 1.0);
                    quarterlyData.add(metrics);
                }

                IncomeStatement incomeStatement = new IncomeStatement(symbol, quarterlyData);

                // Cache the full results
                incomeCache.put(symbol, new CacheEntry<>(incomeStatement));
                saveCacheToDisk();

                return filterIncomeStatement(incomeStatement);
            } catch (Exception e) {
                throw new RuntimeException("Failed to fetch income statement for " + symbol, e);
            }
        });
    }

    private IncomeStatement filterIncomeStatement(IncomeStatement statement) {
        List<Map<String, Double>> filteredMetrics = statement.quarterlyMetrics().stream()
                .filter(metrics -> {
                    LocalDateTime reportDate = LocalDateTime.ofEpochSecond(metrics.get("reportDate").longValue(),
                            0, java.time.ZoneOffset.UTC);
                    return reportDate.isBefore(asOfDate);
                })
                .collect(java.util.stream.Collectors.toList());

        return new IncomeStatement(statement.symbol(), filteredMetrics);
    }

    // Cache entry class with expiration
    private static class CacheEntry<T> implements Serializable {
        private static final long CACHE_DURATION = Duration.ofHours(24).toMillis();

        private final T data;
        private final long timestamp;

        // Default constructor for Jackson
        @JsonCreator
        public CacheEntry(
                @JsonProperty("data") T data,
                @JsonProperty("timestamp") long timestamp
        ) {
            this.data = data;
            this.timestamp = timestamp;
        }

        // Constructor for normal use
        public CacheEntry(T data) {
            this.data = data;
            this.timestamp = System.currentTimeMillis();
        }

        @JsonProperty("data")
        public T data() {
            return data;
        }

        @JsonProperty("timestamp")
        public long getTimestamp() {
            return timestamp;
        }

        public boolean isExpired() {
            return System.currentTimeMillis() - timestamp > CACHE_DURATION;
        }
    }

    public CompletableFuture<List<NewsSentiment>> getNewsSentiments(String ticker) {
        return CompletableFuture.supplyAsync(() -> {
            // Check cache first
            CacheEntry<List<NewsSentiment>> cached = sentimentCache.get(ticker);
            if (cached != null && !cached.isExpired()) {
                return cached.data().stream()
                        .filter(news -> news.publishedTime().isBefore(asOfDate))
                        .toList();
            }

            try {
                String url = String.format("%s?function=NEWS_SENTIMENT&tickers=%s&apikey=%s",
                        BASE_URL, ticker, apiKey);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(TIMEOUT)
                        .GET()
                        .build();

                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                JsonNode root = mapper.readTree(response.body());
                List<NewsSentiment> sentiments = new ArrayList<>();

                if (!root.has("feed")) {
                    System.out.println(root);
                    throw new MarketDataException("No data found in response", null);
                }

                JsonNode feed = root.get("feed");
                for (JsonNode article : feed) {
                    List<TickerSentiment> tickerSentiments = new ArrayList<>();
                    JsonNode tickerSentimentNodes = article.get("ticker_sentiment");

                    for (JsonNode tickerSentiment : tickerSentimentNodes) {
                        tickerSentiments.add(new TickerSentiment(
                                tickerSentiment.get("ticker").asText(),
                                tickerSentiment.get("relevance_score").asDouble(),
                                tickerSentiment.get("ticker_sentiment_score").asDouble(),
                                tickerSentiment.get("ticker_sentiment_label").asText()
                        ));
                    }

                    LocalDateTime publishTime = LocalDateTime.parse(
                            article.get("time_published").asText(),
                            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
                    );

                    List<String> authors = new ArrayList<>();
                    JsonNode authorsNode = article.get("authors");
                    if (authorsNode != null && authorsNode.isArray()) {
                        authorsNode.forEach(author -> authors.add(author.asText()));
                    }

                    sentiments.add(new NewsSentiment(
                            article.get("title").asText(),
                            article.get("url").asText(),
                            publishTime,
                            authors,
                            article.get("summary").asText(),
                            article.get("source").asText(),
                            article.get("overall_sentiment_score").asDouble(),
                            article.get("overall_sentiment_label").asText(),
                            tickerSentiments
                    ));
                }

                // Cache full results
                sentimentCache.put(ticker, new CacheEntry<>(sentiments));
                saveCacheToDisk();

                // Return filtered results
                return sentiments.stream()
                        .filter(news -> news.publishedTime().isBefore(asOfDate))
                        .toList();

            } catch (Exception e) {
                throw new RuntimeException("Failed to fetch news sentiments", e);
            }
        });
    }

    // Data classes
    public record NewsSentiment(
            String title,
            String url,
            LocalDateTime publishedTime,
            List<String> authors,
            String summary,
            String source,
            double overallSentimentScore,
            String overallSentimentLabel,
            List<TickerSentiment> tickerSentiments
    ) implements Serializable {}

    public record TickerSentiment(
            String ticker,
            double relevanceScore,
            double sentimentScore,
            String sentimentLabel
    ) implements Serializable {}

    public record EarningsEvent(String symbol, String companyName, LocalDateTime reportDate,
                                String fiscalDateEnding) implements Serializable {}

    public record FinancialMetrics(String symbol, double peRatio, double profitMargin,
                                   double operatingMargin, double returnOnEquity) implements Serializable {}

    public record IncomeStatement(String symbol, List<Map<String, Double>> quarterlyMetrics) implements Serializable {}
}