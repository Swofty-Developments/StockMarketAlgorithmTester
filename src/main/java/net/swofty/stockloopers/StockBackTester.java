package net.swofty.stockloopers;

import net.swofty.AlgorithmStatistics;
import net.swofty.MarketConfig;
import net.swofty.Portfolio;
import net.swofty.data.HistoricalMarketService;
import net.swofty.orders.MarketDataPoint;
import net.swofty.orders.Order;
import net.swofty.user.Algorithm;

import javax.sound.sampled.Port;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

public class StockBackTester {
    private static final double RISK_FREE_RATE = 0.02; // 2% annual risk-free rate
    private static final ZoneId MARKET_TIMEZONE = ZoneId.of("America/New_York");
    private static final DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            .withZone(MARKET_TIMEZONE);

    private final HistoricalMarketService marketService;
    private final Set<Algorithm> algorithms;
    private final Map<String, Portfolio> algorithmPortfolios;
    private final Map<String, AlgorithmStatistics> algorithmStatistics;
    private final Set<String> tickers;
    private final int previousDays;
    private final Duration interval;
    private final MarketConfig marketConfig;
    private final boolean runOnMarketClosed;
    private final boolean shouldPrint;

    public StockBackTester(HistoricalMarketService marketService, Set<String> tickers,
                           int previousDays, Duration interval, MarketConfig marketConfig, boolean runOnMarketClosed, boolean shouldPrint) {
        this.marketService = marketService;
        this.marketConfig = marketConfig;
        this.runOnMarketClosed = runOnMarketClosed;
        this.shouldPrint = shouldPrint;
        this.algorithms = ConcurrentHashMap.newKeySet();
        this.algorithmPortfolios = new ConcurrentHashMap<>();
        this.algorithmStatistics = new ConcurrentHashMap<>();
        this.tickers = new HashSet<>(tickers);
        this.previousDays = previousDays;
        this.interval = interval;
    }

    public void addAlgorithm(Algorithm algorithm, long initialValue) {
        LocalDateTime startTime = LocalDateTime.now().minusDays(previousDays);

        algorithms.add(algorithm);

        Portfolio portfolio = new Portfolio(initialValue);
        algorithmPortfolios.put(algorithm.getAlgorithmId(), portfolio);
        algorithmStatistics.put(
                algorithm.getAlgorithmId(),
                new AlgorithmStatistics(algorithm.getAlgorithmId(), initialValue, startTime)
        );
    }

    public CompletableFuture<BacktestResults> runBacktest(boolean shouldPrint) {
        LocalDateTime end = LocalDateTime.now();
        LocalDateTime start = end.minusDays(previousDays);

        if (shouldPrint) {
            System.out.println("Running " + algorithms.size() + " algorithms");
            System.out.println("Starting from " + start.format(dateFormatter));
            System.out.println("Ending at " + end.format(dateFormatter));
            System.out.println("Previous days: " + previousDays);
        }

        return marketService.fetchHistoricalData(tickers, start, end)
                .thenApply(historicalData -> processHistoricalData(historicalData, shouldPrint));
    }

    private BacktestResults processHistoricalData(Map<String, List<MarketDataPoint>> historicalData, boolean shouldPrint) {
        TreeMap<LocalDateTime, Map<String, MarketDataPoint>> timeline = createTimeline(historicalData);
        Map<String, MarketDataPoint> initialData = timeline.firstEntry().getValue();
        algorithms.forEach(algo -> algo.onMarketOpen(initialData));

        LocalDateTime lastProcessed = null;
        List<LocalDateTime> marketHourPoints = timeline.keySet().stream()
                .filter(this::isMarketHours)
                .toList();

        int totalPoints = marketHourPoints.size();
        int processedPoints = 0;

        if (shouldPrint) {
            System.out.println("Found " + totalPoints + " points during market hours");
        }

        for (LocalDateTime timestamp : marketHourPoints) {
            Map<String, MarketDataPoint> currentData = timeline.get(timestamp);

            if ((lastProcessed == null || Duration.between(lastProcessed, timestamp).compareTo(interval) >= 0)) {
                processTimepoint(currentData);
                lastProcessed = timestamp;
                processedPoints++;

                if (shouldPrint) {
                    System.out.println("Processing timepoint: " + timestamp.atZone(marketConfig.zoneId));
                    printProgress(processedPoints, totalPoints, timestamp);
                }
            }
        }

        Map<String, MarketDataPoint> finalData = timeline.lastEntry().getValue();
        algorithms.forEach(algo -> algo.onMarketClose(finalData));

        if (shouldPrint) {
            System.out.print("\r" + " ".repeat(150) + "\r");
            System.out.println("Backtest completed!");
            System.out.println("Processed " + processedPoints + " points out of " + totalPoints + " market hours points");
        }

        return new BacktestResults(
                algorithmStatistics,
                timeline.firstKey(),
                timeline.lastKey(),
                algorithmPortfolios
        );
    }

    private void printProgress(int current, int total, LocalDateTime timestamp) {
        int barWidth = 50;

        // Calculate progress based on actual data points we have
        float progress = (float) current / (total + 1);  // Add 1 to avoid division by zero
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

    private TreeMap<LocalDateTime, Map<String, MarketDataPoint>> createTimeline(
            Map<String, List<MarketDataPoint>> historicalData) {
        TreeMap<LocalDateTime, Map<String, MarketDataPoint>> timeline = new TreeMap<>();

        // First collect all unique minutes
        Set<LocalDateTime> minutes = new HashSet<>();
        historicalData.values().forEach(dataPoints -> {
            dataPoints.forEach(point -> {
                // Truncate to minute precision
                LocalDateTime minute = point.timestamp()
                        .withSecond(0)
                        .withNano(0);
                minutes.add(minute);
            });
        });

        // For each minute, collect all data points from that minute
        minutes.forEach(minute -> {
            Map<String, MarketDataPoint> dataAtTime = new HashMap<>();
            historicalData.forEach((ticker, dataPoints) -> {
                // Find the closest data point within this minute
                dataPoints.stream()
                        .filter(point -> {
                            LocalDateTime pointMinute = point.timestamp()
                                    .withSecond(0)
                                    .withNano(0);
                            return pointMinute.equals(minute);
                        })
                        .findFirst()
                        .ifPresent(point -> dataAtTime.put(ticker, point));
            });

            if (!dataAtTime.isEmpty()) {
                timeline.put(minute, dataAtTime);
            }
        });

        if (timeline.isEmpty()) {
            throw new IllegalStateException("No data points available for backtesting");
        }

        // Debug information
        if (shouldPrint) {
            System.out.println("Timeline created with " + timeline.size() + " points");
            System.out.println("First point: " + timeline.firstKey());
            System.out.println("Last point: " + timeline.lastKey());
            System.out.println("Sample data points per minute:");
            timeline.entrySet().stream().limit(5).forEach(entry ->
                    System.out.printf("  %s: %d tickers available (%s)%n",
                            entry.getKey(),
                            entry.getValue().size(),
                            String.join(", ", entry.getValue().keySet())
                    ));
        }

        return timeline;
    }

    private boolean isMarketHours(LocalDateTime timestamp) {
        // Convert from system timezone to market timezone
        ZonedDateTime marketTime = timestamp.atZone(ZoneId.systemDefault())
                .withZoneSameInstant(marketConfig.zoneId);

        // If we're running on market closed, only check weekends
        if (runOnMarketClosed) {
            return true;
        }

        // Check if it's a weekend
        if (isWeekend(marketTime.toLocalDate())) {
            return false;
        }

        // Check if within market hours
        LocalTime time = marketTime.toLocalTime();

        boolean withinHours = !time.isBefore(marketConfig.openTime) &&
                !time.isAfter(marketConfig.closeTime);

        System.out.printf("Checking market hours - System: %s, Market: %s, Hours: %s-%s, Within: %b%n",
                timestamp,
                marketTime,
                marketConfig.openTime,
                marketConfig.closeTime,
                withinHours);

        return withinHours;
    }

    private boolean isWeekend(LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        return day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY;
    }

    private void processTimepoint(Map<String, MarketDataPoint> currentData) {
        algorithms.forEach(algo -> {
            String algoId = algo.getAlgorithmId();
            Portfolio portfolio = algorithmPortfolios.get(algoId);
            AlgorithmStatistics statistics = algorithmStatistics.get(algoId);

            LocalDateTime timestamp = currentData.values().iterator().next().timestamp();

            // Process algorithm decisions
            algo.onUpdate(currentData, timestamp, portfolio);
            statistics.setTrades(portfolio.getTotalPositions());

            // Update statistics with current portfolio value
            double portfolioValue = portfolio.getTotalValue(currentData);
            statistics.updateStatistics(portfolioValue, RISK_FREE_RATE / 252);
        });
    }

    private void processOrders(List<Order> orders, Portfolio portfolio, Map<String, MarketDataPoint> currentData, LocalDateTime timestamp) {
        orders.forEach(order -> {
            try {
                switch (order.type()) {
                    case BUY -> portfolio.buyStock(order.ticker(), order.quantity(), order.price());
                    case SELL -> portfolio.sellStock(order.ticker(), order.quantity(), order.price());
                }
            } catch (Exception e) {
                System.err.printf("Failed to execute order: %s - %s%n", order, e.getMessage());
            }
        });
    }

    public record BacktestResults(
            Map<String, AlgorithmStatistics> statistics,
            LocalDateTime startTime,
            LocalDateTime endTime,
            Map<String, Portfolio> portfolios
    ) {
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("Backtest Results\n");
            sb.append("================\n");
            sb.append(String.format("Period: %s to %s\n", startTime, endTime));
            sb.append("----------------\n");

            statistics.forEach((algoId, stats) -> {
                sb.append(stats.toString());
                sb.append("----------------\n");
            });

            return sb.toString();
        }
    }
}