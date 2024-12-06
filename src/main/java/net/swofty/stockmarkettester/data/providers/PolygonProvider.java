package net.swofty.stockmarkettester.data.providers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.swofty.stockmarkettester.MarketConfig;
import net.swofty.stockmarkettester.data.MarketDataProvider;
import net.swofty.stockmarkettester.data.MarketDataResponse;
import net.swofty.stockmarkettester.data.RateLimiter;
import net.swofty.stockmarkettester.orders.HistoricalData;
import net.swofty.stockmarkettester.orders.MarketDataPoint;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class PolygonProvider implements MarketDataProvider {
    private final HttpClient client;
    private final String apiKey;
    private final ObjectMapper mapper;
    private final RateLimiter rateLimiter;
    private final String baseUrl;
    private final DateTimeFormatter formatter;

    private static final int MAX_RETRIES = 3;
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final int RATE_LIMIT = 5; // Calls per minute for free tier

    public PolygonProvider(String apiKey) {
        this.apiKey = apiKey;
        this.client = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .build();
        this.mapper = new ObjectMapper();
        this.rateLimiter = RateLimiter.create(RATE_LIMIT / 60.0);
        this.baseUrl = "https://api.polygon.io";
        this.formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    }

    @Override
    public CompletableFuture<MarketDataResponse> fetchRealTimeData(Set<String> tickers) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                rateLimiter.acquire();
                String tickersParam = String.join(",", tickers);
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(String.format(
                                "%s/v2/snapshot/locale/us/markets/stocks/tickers/%s?apiKey=%s",
                                baseUrl, tickersParam, apiKey)))
                        .timeout(TIMEOUT)
                        .GET()
                        .build();

                return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                        .thenApply(HttpResponse::body)
                        .thenApply(this::parseMarketData)
                        .get(TIMEOUT.getSeconds(), TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new MarketDataException("Failed to fetch real-time data", e);
            }
        });
    }

    private MarketDataResponse parseMarketData(String json) {
        try {
            JsonNode root = mapper.readTree(json);
            Map<String, MarketDataPoint> dataPoints = new HashMap<>();

            root.get("tickers").forEach(ticker -> {
                String symbol = ticker.get("ticker").asText();
                JsonNode day = ticker.get("day");

                // Extract OHLC values
                double open = day.get("o").asDouble();
                double high = day.get("h").asDouble();
                double low = day.get("l").asDouble();
                double close = day.get("c").asDouble();
                double volume = day.get("v").asDouble();

                dataPoints.put(symbol, new MarketDataPoint(
                        symbol,
                        open,
                        high,
                        low,
                        close,
                        volume,
                        LocalDateTime.now()
                ));
            });

            return new MarketDataResponse(dataPoints, LocalDateTime.now());
        } catch (Exception e) {
            throw new MarketDataException("Failed to parse market data", e);
        }
    }

    @Override
    public CompletableFuture<HistoricalData> fetchHistoricalData(
            Set<String> tickers,
            LocalDateTime start,
            LocalDateTime end,
            MarketConfig marketConfig) {
        return CompletableFuture.supplyAsync(() -> {
            if (tickers.size() != 1) {
                throw new IllegalArgumentException("HistoricalData requires exactly one ticker");
            }

            String ticker = tickers.iterator().next();
            HistoricalData historicalData = new HistoricalData(ticker);

            rateLimiter.acquire();
            try {
                long startTimestamp = start.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
                long endTimestamp = end.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(String.format(
                                "%s/v2/aggs/ticker/%s/range/1/minute/%d/%d?adjusted=true&sort=asc&limit=50000&apiKey=%s",
                                baseUrl, ticker, startTimestamp, endTimestamp, apiKey)))
                        .timeout(TIMEOUT)
                        .GET()
                        .build();

                String response = client.send(request, HttpResponse.BodyHandlers.ofString())
                        .body();

                JsonNode root = mapper.readTree(response);

                if (root.has("status") && !"OK".equals(root.get("status").asText())) {
                    throw new MarketDataException("Polygon API error: " + root.get("message").asText(), null);
                }

                JsonNode results = root.get("results");
                if (results == null || !results.isArray()) {
                    throw new MarketDataException("Invalid data format from Polygon API", null);
                }

                int totalPoints = 0;
                int marketHoursPoints = 0;

                for (JsonNode bar : results) {
                    totalPoints++;

                    long timestamp = bar.get("t").asLong();
                    LocalDateTime dateTime = LocalDateTime.ofInstant(
                            Instant.ofEpochMilli(timestamp),
                            ZoneId.systemDefault()
                    );

                    ZonedDateTime marketTime = dateTime.atZone(ZoneId.systemDefault())
                            .withZoneSameInstant(ZoneId.of("America/New_York"));
                    LocalTime time = marketTime.toLocalTime();

                    if (time.isAfter(LocalTime.of(9, 29)) &&
                            time.isBefore(LocalTime.of(16, 1)) &&
                            !isWeekend(marketTime.toLocalDate())) {

                        marketHoursPoints++;

                        // Extract OHLC values from the bar
                        double open = bar.get("o").asDouble();
                        double high = bar.get("h").asDouble();
                        double low = bar.get("l").asDouble();
                        double close = bar.get("c").asDouble();
                        double volume = bar.get("v").asDouble();

                        // Validate the data point
                        if (isValidOHLCData(open, high, low, close, volume)) {
                            MarketDataPoint point = new MarketDataPoint(
                                    ticker,
                                    open,
                                    high,
                                    low,
                                    close,
                                    volume,
                                    dateTime
                            );
                            historicalData.addDataPoint(point);
                        } else {
                            System.err.printf("Invalid OHLC data point detected for %s at %s: O=%.2f H=%.2f L=%.2f C=%.2f V=%.2f%n",
                                    ticker, dateTime, open, high, low, close, volume);
                        }
                    }
                }

                System.out.printf("Fetched %d total points, %d valid market hours points for %s%n",
                        totalPoints, marketHoursPoints, ticker);

                return historicalData;

            } catch (Exception e) {
                throw new MarketDataException("Failed to fetch historical data", e);
            }
        });
    }

    private boolean isValidOHLCData(double open, double high, double low, double close, double volume) {
        return high >= low &&                  // High must be >= Low
                high >= open &&                 // High must be >= Open
                high >= close &&                // High must be >= Close
                low <= open &&                  // Low must be <= Open
                low <= close &&                 // Low must be <= Close
                volume >= 0 &&                  // Volume must be non-negative
                !Double.isNaN(open) &&          // No NaN values
                !Double.isNaN(high) &&
                !Double.isNaN(low) &&
                !Double.isNaN(close) &&
                !Double.isInfinite(open) &&     // No infinite values
                !Double.isInfinite(high) &&
                !Double.isInfinite(low) &&
                !Double.isInfinite(close);
    }

    private boolean isWeekend(LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        return day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY;
    }

    @Override
    public boolean isAvailable() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/v1/marketstatus/now?apiKey=" + apiKey))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public int getRateLimit() {
        return RATE_LIMIT;
    }

    @Override
    public Capabilities getCapabilities() {
        return new Capabilities() {
            @Override
            public boolean supportsHistorical() {
                return true;
            }

            @Override
            public Duration dataGranularity() {
                return Duration.ofMinutes(1);
            }
        };
    }
}