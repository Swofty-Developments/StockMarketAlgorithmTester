package net.swofty.stockloopers;

import net.swofty.MarketConfig;
import net.swofty.data.HistoricalMarketService;
import net.swofty.orders.MarketDataPoint;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public class IndividualStockLooper {
    private static final DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            .withZone(ZoneId.systemDefault());

    private final HistoricalMarketService marketService;
    private final String ticker;
    private final int previousDays;
    private final Duration interval;
    private final MarketConfig marketConfig;
    private final boolean runOnMarketClosed;
    private final boolean shouldPrint;

    public IndividualStockLooper(
            HistoricalMarketService marketService,
            String ticker,
            int previousDays,
            Duration interval,
            MarketConfig marketConfig,
            boolean runOnMarketClosed,
            boolean shouldPrint
    ) {
        this.marketService = marketService;
        this.ticker = ticker;
        this.previousDays = previousDays;
        this.interval = interval;
        this.marketConfig = marketConfig;
        this.runOnMarketClosed = runOnMarketClosed;
        this.shouldPrint = shouldPrint;
    }

    public CompletableFuture<List<TimePoint>> run() {
        LocalDateTime end = LocalDateTime.now();
        LocalDateTime start = end.minusDays(previousDays);

        if (shouldPrint) {
            System.out.println("Starting individual stock loop for " + ticker);
            System.out.println("Starting from " + start.format(dateFormatter));
            System.out.println("Ending at " + end.format(dateFormatter));
            System.out.println("Previous days: " + previousDays);
        }

        return marketService.fetchHistoricalData(Set.of(ticker), start, end)
                .thenApply(historicalData -> processHistoricalData(historicalData, shouldPrint));
    }

    private List<TimePoint> processHistoricalData(Map<String, List<MarketDataPoint>> historicalData, boolean shouldPrint) {
        List<MarketDataPoint> dataPoints = historicalData.get(ticker);
        if (dataPoints == null || dataPoints.isEmpty()) {
            throw new IllegalStateException("No data points available for ticker: " + ticker);
        }

        List<TimePoint> timePoints = new ArrayList<>();
        LocalDateTime lastProcessed = null;

        // Filter points during market hours
        List<MarketDataPoint> marketHourPoints = dataPoints.stream()
                .filter(point -> isMarketHours(point.timestamp()))
                .toList();

        int totalPoints = marketHourPoints.size();
        int processedPoints = 0;

        if (shouldPrint) {
            System.out.println("Found " + totalPoints + " points during market hours for " + ticker);
        }

        for (MarketDataPoint point : marketHourPoints) {
            if (lastProcessed == null || Duration.between(lastProcessed, point.timestamp()).compareTo(interval) >= 0) {
                timePoints.add(new TimePoint(point.timestamp(), point));
                lastProcessed = point.timestamp();
                processedPoints++;

                if (shouldPrint) {
                    printProgress(processedPoints, totalPoints, point.timestamp());
                }
            }
        }

        if (shouldPrint) {
            System.out.print("\r" + " ".repeat(150) + "\r");
            System.out.println("Loop completed for " + ticker + "!");
            System.out.println("Processed " + processedPoints + " points out of " + totalPoints + " market hours points");
        }

        return timePoints;
    }

    private boolean isMarketHours(LocalDateTime timestamp) {
        ZonedDateTime marketTime = timestamp.atZone(ZoneId.systemDefault())
                .withZoneSameInstant(marketConfig.zoneId);

        if (runOnMarketClosed) {
            return true;
        }

        if (isWeekend(marketTime.toLocalDate())) {
            return false;
        }

        LocalTime time = marketTime.toLocalTime();
        return !time.isBefore(marketConfig.openTime) && !time.isAfter(marketConfig.closeTime);
    }

    private boolean isWeekend(LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        return day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY;
    }

    private void printProgress(int current, int total, LocalDateTime timestamp) {
        int barWidth = 50;
        float progress = (float) current / total;
        int progressBars = (int) (progress * barWidth);

        String progressBar = "["
                + "=".repeat(progressBars)
                + ">"
                + " ".repeat(barWidth - progressBars)
                + "]";

        ZonedDateTime marketTime = timestamp.atZone(ZoneId.systemDefault())
                .withZoneSameInstant(marketConfig.zoneId);
        String dateTimeStr = marketTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String percentage = String.format("%.1f%%", progress * 100);

        System.out.print("\r" + progressBar + " " + percentage + " | Market Time: " + dateTimeStr +
                " " + marketConfig.zoneId.getId());
    }

    public record TimePoint(LocalDateTime timestamp, MarketDataPoint dataPoint) {}
}