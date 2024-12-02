package net.swofty.data.providers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.swofty.MarketConfig;
import net.swofty.data.MarketDataProvider;
import net.swofty.data.MarketDataResponse;
import net.swofty.data.RateLimiter;
import net.swofty.orders.HistoricalData;
import net.swofty.orders.MarketDataPoint;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

public class AlphaVantageProvider implements MarketDataProvider {
    private final HttpClient client;
    private final String apiKey;
    private final ObjectMapper mapper;
    private final RateLimiter rateLimiter;
    private final String baseUrl;
    private final DateTimeFormatter formatter;

    private static final int MAX_RETRIES = 3;
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final int RATE_LIMIT = 5; // Calls per minute

    public AlphaVantageProvider(String apiKey) {
        this.apiKey = apiKey;
        this.client = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .build();
        this.mapper = new ObjectMapper();
        this.rateLimiter = RateLimiter.create(RATE_LIMIT / 60.0); // Calls per second
        this.baseUrl = "https://www.alphavantage.co/query";
        this.formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    }

    @Override
    public CompletableFuture<MarketDataResponse> fetchRealTimeData(Set<String> tickers) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                rateLimiter.acquire();
                String tickersParam = String.join(",", tickers);
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(String.format(
                                "%s?function=GLOBAL_QUOTE&symbols=%s&apikey=%s",
                                baseUrl, tickersParam, apiKey)))
                        .GET()
                        .build();

                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                validateResponse(response);

                return parseMarketData(response.body());
            } catch (Exception e) {
                throw new MarketDataException("Failed to fetch real-time data", e);
            }
        });
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

            try {
                rateLimiter.acquire();
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(String.format(
                                "%s?function=TIME_SERIES_INTRADAY&symbol=%s&apikey=%s&interval=1min&outputsize=full",
                                baseUrl, ticker, apiKey)))
                        .GET()
                        .build();

                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                validateResponse(response);

                populateHistoricalData(response.body(), historicalData, start, end);
                return historicalData;

            } catch (Exception e) {
                e.printStackTrace();
                throw new MarketDataException("Failed to fetch historical data for " + ticker, e);
            }
        });
    }

    private void validateResponse(HttpResponse<String> response) {
        if (response.statusCode() != 200) {
            throw new MarketDataException("API request failed with status: " + response.statusCode(), null);
        }

        // Check for API error messages
        try {
            JsonNode root = mapper.readTree(response.body());
            if (root.has("Error Message")) {
                throw new MarketDataException("API Error: " + root.get("Error Message").asText(), null);
            }
            if (root.has("Note")) {
                throw new MarketDataException("API Limit Reached: " + root.get("Note").asText(), null);
            }
        } catch (Exception e) {
            throw new MarketDataException("Failed to validate response", e);
        }
    }

    private void populateHistoricalData(
            String json,
            HistoricalData historicalData,
            LocalDateTime start,
            LocalDateTime end) {
        try {
            JsonNode root = mapper.readTree(json);
            if (root.has("Information")) {
                throw new MarketDataException("API Error: " + root.get("Information").asText(), null);
            }
            JsonNode timeSeries = root.get("Time Series (1min)");

            if (timeSeries == null) {
                throw new MarketDataException("Invalid time series data format", null);
            }

            Iterator<Map.Entry<String, JsonNode>> fields = timeSeries.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String dateStr = entry.getKey();
                LocalDateTime timestamp = LocalDateTime.parse(dateStr, formatter);

                JsonNode pointData = entry.getValue();
                double open = pointData.get("1. open").asDouble();
                double high = pointData.get("2. high").asDouble();
                double low = pointData.get("3. low").asDouble();
                double close = pointData.get("4. close").asDouble();
                double volume = pointData.get("5. volume").asDouble();

                MarketDataPoint point = new MarketDataPoint(
                        historicalData.getTicker(),
                        open,
                        high,
                        low,
                        close,
                        volume,
                        timestamp
                );

                historicalData.addDataPoint(point);
            }

            if (historicalData.getDataPoints(start, end).isEmpty()) {
                throw new MarketDataException("No data points found in specified date range", null);
            }

        } catch (MarketDataException e) {
            throw e;
        } catch (Exception e) {
            throw new MarketDataException("Failed to parse historical data", e);
        }
    }

    private MarketDataResponse parseMarketData(String json) {
        try {
            JsonNode root = mapper.readTree(json);
            Map<String, MarketDataPoint> dataPoints = new HashMap<>();
            LocalDateTime now = LocalDateTime.now();

            // Handle both single and multiple quotes
            if (root.has("Global Quote")) {
                JsonNode quote = root.get("Global Quote");
                addQuoteToDataPoints(quote, dataPoints, now);
            } else if (root.has("Global Quotes")) {
                root.get("Global Quotes").forEach(quote ->
                        addQuoteToDataPoints(quote, dataPoints, now));
            }

            return new MarketDataResponse(dataPoints, now);
        } catch (Exception e) {
            throw new MarketDataException("Failed to parse market data", e);
        }
    }

    private void addQuoteToDataPoints(JsonNode quote, Map<String, MarketDataPoint> dataPoints, LocalDateTime timestamp) {
        String symbol = quote.get("01. symbol").asText();
        double open = quote.get("02. open").asDouble();
        double high = quote.get("03. high").asDouble();
        double low = quote.get("04. low").asDouble();
        double close = quote.get("05. price").asDouble();
        double volume = quote.get("06. volume").asDouble();

        dataPoints.put(symbol, new MarketDataPoint(
                symbol,
                open,
                high,
                low,
                close,
                volume,
                timestamp
        ));
    }

    @Override
    public boolean isAvailable() {
        try {
            rateLimiter.acquire();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(String.format(
                            "%s?function=TIME_SERIES_INTRADAY&symbol=IBM&interval=1min&apikey=%s",
                            baseUrl, apiKey)))
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