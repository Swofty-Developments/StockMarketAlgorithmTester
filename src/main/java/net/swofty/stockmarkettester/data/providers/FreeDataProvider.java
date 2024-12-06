package net.swofty.stockmarkettester.data.providers;

import net.swofty.stockmarkettester.MarketConfig;
import net.swofty.stockmarkettester.data.MarketDataProvider;
import net.swofty.stockmarkettester.data.MarketDataResponse;
import net.swofty.stockmarkettester.orders.HistoricalData;
import net.swofty.stockmarkettester.orders.MarketDataPoint;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class FreeDataProvider implements MarketDataProvider {
    private static final String BASE_URL = "https://frd001.s3.us-east-2.amazonaws.com/frd_sample_stock_";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private final HttpClient client;
    private final Path cacheDir;
    private final DateTimeFormatter timestampFormatter;
    private MarketConfig marketConfig = null;

    public FreeDataProvider(Path cacheDirectory) {
        this.client = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .build();
        this.cacheDir = cacheDirectory;
        this.timestampFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmm");

        // Ensure cache directory exists
        try {
            Files.createDirectories(cacheDirectory);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create cache directory", e);
        }
    }

    @Override
    public CompletableFuture<MarketDataResponse> fetchRealTimeData(Set<String> tickers) {
        // This is historical data only, so we'll return empty response
        return CompletableFuture.completedFuture(
                new MarketDataResponse(new HashMap<>(), LocalDateTime.now())
        );
    }

    @Override
    public CompletableFuture<HistoricalData> fetchHistoricalData(
            Set<String> tickers,
            LocalDateTime start,
            LocalDateTime end,
            MarketConfig marketConfig) {
        this.marketConfig = marketConfig;
        return CompletableFuture.supplyAsync(() -> {
            if (tickers.size() != 1) {
                throw new IllegalArgumentException("Historical data requires exactly one ticker");
            }

            String ticker = tickers.iterator().next();
            HistoricalData historicalData = new HistoricalData(ticker);

            try {
                // Check cache first
                Path zipPath = cacheDir.resolve(ticker + "_sample.zip");
                if (!Files.exists(zipPath)) {
                    downloadZipFile(ticker, zipPath);
                }

                // Process the ZIP file
                processZipFile(zipPath, ticker, historicalData, start, end);

                System.out.printf("Processed historical data for %s", ticker);

                return historicalData;

            } catch (Exception e) {
                throw new RuntimeException("Failed to fetch historical data for " + ticker, e);
            }
        });
    }

    private void downloadZipFile(String ticker, Path zipPath) throws IOException {
        String url = BASE_URL + ticker + ".zip";
        System.out.println("Downloading data from: " + url);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(TIMEOUT)
                    .GET()
                    .build();

            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());

            if (response.statusCode() != 200) {
                throw new IOException("Failed to download file: HTTP " + response.statusCode());
            }

            Files.write(zipPath, response.body());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Download interrupted", e);
        }
    }

    private void processZipFile(
            Path zipPath,
            String ticker,
            HistoricalData historicalData,
            LocalDateTime start,
            LocalDateTime end) throws IOException {

        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(Files.newInputStream(zipPath)))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().equals(ticker + "_1min_sample.csv")) {
                    processCSVData(zis, ticker, historicalData, start, end);
                    break;
                }
            }
        }
    }

    private void processCSVData(
            InputStream inputStream,
            String ticker,
            HistoricalData historicalData,
            LocalDateTime start,
            LocalDateTime end) throws IOException {

        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
        String line;
        int totalPoints = 0;
        int validPoints = 0;

        // Skip header if present
        line = reader.readLine();
        if (!line.matches("\\d{4}-\\d{2}-\\d{2}.*")) {
            line = reader.readLine();
        }

        while (line != null) {
            totalPoints++;
            try {
                String[] parts = line.split(",");
                if (parts.length >= 6) {
                    // Parse the datetime which is in format "2024-11-12 09:58:00"
                    LocalDateTime csvTime = LocalDateTime.parse(
                            parts[0],
                            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    );

                    // IMPORTANT: The CSV data is assumed to be in America/New_York timezone
                    ZonedDateTime marketTime = csvTime.atZone(ZoneId.of("America/New_York"));

                    // Convert to system timezone for storage
                    LocalDateTime timestamp = marketTime.withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();

                    // Only include points within the requested time range
                    if (!timestamp.isBefore(start) && !timestamp.isAfter(end)) {
                        // Check if it's during market hours (directly in market timezone)
                        LocalTime time = marketTime.toLocalTime();
                        if (!time.isBefore(LocalTime.of(9, 30)) &&
                                !time.isAfter(LocalTime.of(16, 0)) &&
                                !isWeekend(marketTime.toLocalDate())) {

                            MarketDataPoint point = new MarketDataPoint(
                                    ticker,
                                    Double.parseDouble(parts[1]),  // Open price
                                    Double.parseDouble(parts[2]),  // High price
                                    Double.parseDouble(parts[3]),  // Low price
                                    Double.parseDouble(parts[4]),  // Close price
                                    Double.parseDouble(parts[5]),  // Volume
                                    timestamp
                            );
                            historicalData.addDataPoint(point);
                            validPoints++;
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("Error processing line: " + line);
                e.printStackTrace();
            }
            line = reader.readLine();
        }

        System.out.printf("Processed %d total points, included %d valid market hours points%n",
                totalPoints, validPoints);
    }

    private boolean isMarketHours(LocalDateTime timestamp) {
        // Convert timestamp TO market timezone by first specifying it's in UTC
        ZonedDateTime utcTime = timestamp.atZone(ZoneId.of("UTC"));
        ZonedDateTime marketTime = utcTime.withZoneSameInstant(marketConfig.zoneId);

        // Check if it's a weekend
        if (isWeekend(marketTime.toLocalDate())) {
            return false;
        }

        // Check if within market hours using market config
        LocalTime time = marketTime.toLocalTime();

        // Debug log the actual times being checked
        System.out.printf("Checking market hours - UTC: %s, Market: %s, Hours: %s-%s%n",
                utcTime,
                marketTime,
                marketConfig.openTime,
                marketConfig.closeTime);

        return !time.isBefore(marketConfig.openTime) &&
                !time.isAfter(marketConfig.closeTime);
    }

    private boolean isWeekend(LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        return day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY;
    }

    @Override
    public boolean isAvailable() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "AAPL.zip"))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .timeout(Duration.ofSeconds(5))
                    .build();

            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public int getRateLimit() {
        return Integer.MAX_VALUE;  // No rate limit for local files
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