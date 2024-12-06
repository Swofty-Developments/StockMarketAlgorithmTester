package net.swofty.stockmarkettester;

import net.swofty.stockmarkettester.orders.OrderType;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.ConcurrentHashMap;

public class AlgorithmStatistics {
    private final String algorithmId;
    private final LocalDateTime startTime;
    private final DoubleAdder totalProfit;
    private final DoubleAdder maxDrawdown;
    private final DoubleAdder peakValue;
    private final DoubleAdder sharpeRatio;
    private final DoubleAdder totalValue;
    private final List<Double> dailyReturns;
    private volatile double initialValue;

    // New fields for enhanced statistics
    private final Map<String, TickerStatistics> tickerStats;
    private final Map<LocalDate, WeeklyPerformance> weeklyPerformance = new ConcurrentHashMap<>();
    private final Map<String, TradeRecord> openTrades = new ConcurrentHashMap<>();
    private final List<TradeRecord> tradeHistory;
    private final AtomicInteger totalTrades;

    public AlgorithmStatistics(String algorithmId, double initialValue, LocalDateTime startTime) {
        this.algorithmId = algorithmId;
        this.totalTrades = new AtomicInteger(0);
        this.totalProfit = new DoubleAdder();
        this.maxDrawdown = new DoubleAdder();
        this.peakValue = new DoubleAdder();
        this.sharpeRatio = new DoubleAdder();
        this.totalValue = new DoubleAdder();
        this.dailyReturns = new ArrayList<>();
        this.startTime = startTime;
        this.initialValue = initialValue;
        this.peakValue.add(initialValue);

        // Initialize new tracking structures
        this.tickerStats = new ConcurrentHashMap<>();
        this.tradeHistory = Collections.synchronizedList(new ArrayList<>());
    }

    public void recordTrade(String ticker, OrderType type, int quantity, double price,
                            double portfolioValueBefore, LocalDateTime timestamp) {
        TradeRecord trade = new TradeRecord(
                ticker, type, quantity, price, portfolioValueBefore, timestamp
        );
        tradeHistory.add(trade);
        totalTrades.incrementAndGet();

        // Update ticker-specific statistics
        tickerStats.computeIfAbsent(ticker, k -> new TickerStatistics())
                .updateStats(trade);

        // Handle weekly performance tracking
        switch (type) {
            case BUY, SHORT -> openTrades.put(ticker, trade);
            case SELL, COVER -> {
                TradeRecord openTrade = openTrades.remove(ticker);
                if (openTrade != null) {
                    // Get the week of the SELL/COVER trade
                    LocalDate weekStart = timestamp.toLocalDate().with(DayOfWeek.MONDAY);
                    weeklyPerformance.computeIfAbsent(weekStart, k -> new WeeklyPerformance())
                            .recordCompletedTrade(openTrade, trade);
                }
            }
        }
    }

    public int getTotalTrades() {
        return totalTrades.get();
    }

    public void updateStatistics(double currentValue, double riskFreeRate) {
        // Existing statistics logic
        totalProfit.reset();
        totalProfit.add(currentValue - initialValue);

        totalValue.reset();
        totalValue.add(currentValue);

        if (currentValue > peakValue.sum()) {
            peakValue.reset();
            peakValue.add(currentValue);
        }
        double currentDrawdown = (peakValue.sum() - currentValue) / peakValue.sum() * 100;
        if (currentDrawdown > maxDrawdown.sum()) {
            maxDrawdown.reset();
            maxDrawdown.add(currentDrawdown);
        }

        double dailyReturn = (currentValue - initialValue) / initialValue;
        dailyReturns.add(dailyReturn);

        if (dailyReturns.size() > 1) {
            double averageReturn = dailyReturns.stream()
                    .mapToDouble(Double::doubleValue)
                    .average()
                    .orElse(0.0);
            double stdDev = calculateStandardDeviation(dailyReturns, averageReturn);
            double annualizedSharpe = stdDev != 0 ?
                    (Math.sqrt(252) * (averageReturn - riskFreeRate/252) / stdDev) : 0;

            sharpeRatio.reset();
            sharpeRatio.add(annualizedSharpe);
        }
    }

    private double calculateStandardDeviation(List<Double> returns, double mean) {
        return Math.sqrt(
                returns.stream()
                        .mapToDouble(r -> Math.pow(r - mean, 2))
                        .average()
                        .orElse(0.0)
        );
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        long daysRun = ChronoUnit.DAYS.between(startTime, LocalDateTime.now());
        double annualizedReturn = dailyReturns.isEmpty() ? 0 :
                Math.pow(1 + dailyReturns.get(dailyReturns.size() - 1), 252) - 1;

        // Overall Performance
        sb.append(String.format("""
            Algorithm Statistics for %s:
            Backtest Period: %d days
            Total Trades: %d
            Total Profit/Loss: $%.2f
            Annualized Return: %.2f%%
            Maximum Drawdown: %.2f%%
            Sharpe Ratio: %.2f
            Average Trades Per Day: %.2f
            Total Value: $%.2f
            
            Per-Ticker Performance:
            =====================
            """,
                algorithmId, daysRun, totalTrades.get(), totalProfit.sum(),
                annualizedReturn * 100, maxDrawdown.sum(), sharpeRatio.sum(),
                daysRun > 0 ? (double) totalTrades.get() / daysRun : 0,
                totalValue.sum()
        ));

        // Add per-ticker statistics
        tickerStats.forEach((ticker, stats) -> {
            double winRate = stats.totalSells > 0 ?
                    ((double) stats.profitableSells / stats.totalSells) * 100 : 0.0;

            sb.append(String.format("""
            %s:
              Total Sells: %d
              Profitable Sells: %d (%.1f%%)
              Total P/L: $%.2f
              Average P/L per Sale: $%.2f
              Largest Gain: $%.2f
              Largest Loss: $%.2f
              Win Rate: %.1f%%
              
            """,
                    ticker, stats.totalSells, stats.profitableSells,
                    winRate,
                    stats.totalPnL,
                    stats.totalSells > 0 ? stats.totalPnL / stats.totalSells : 0.0,
                    stats.largestGain,
                    stats.largestLoss,
                    winRate
            ));
        });

        // Add monthly performance
        sb.append("\nWeekly Performance:\n===================\n");
        if (weeklyPerformance.isEmpty()) {
            sb.append("No completed trades yet\n");
        } else {
            DateTimeFormatter weekFormatter = DateTimeFormatter.ofPattern("MM/dd/yyyy");
            weeklyPerformance.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .filter(entry -> entry.getValue().hasActivity())
                    .forEach(entry -> {
                        WeeklyPerformance perf = entry.getValue();
                        LocalDate weekStart = entry.getKey();
                        LocalDate weekEnd = weekStart.plusDays(6);
                        String weekRange = String.format("%s - %s",
                                weekStart.format(weekFormatter),
                                weekEnd.format(weekFormatter));

                        sb.append(String.format("Week %s:\n", weekRange));
                        sb.append(String.format("  P/L: $%.2f\n", perf.totalPnL));
                        sb.append(String.format("  Completed Trades: %d\n", perf.totalSells));
                        if (perf.totalSells > 0) {
                            sb.append(String.format("  Average P/L per Share: $%.2f\n", perf.profitPerShare));
                        }
                        sb.append("\n");
                    });
        }

        return sb.toString();
    }

    private static class TickerStatistics {
        private int totalSells;  // Changed from totalTrades
        private int profitableSells;  // Changed from profitableTrades
        private double totalPnL;
        private double largestGain;
        private double largestLoss;
        private Double lastBuyPrice;
        private int lastBuyQuantity;

        public synchronized void updateStats(TradeRecord trade) {
            switch (trade.type()) {
                case BUY -> {
                    lastBuyPrice = trade.price();
                    lastBuyQuantity = trade.quantity();
                }
                case SELL -> {
                    if (lastBuyPrice != null) {
                        totalSells++; // Only count sells
                        double profit = (trade.price() - lastBuyPrice) * trade.quantity();
                        totalPnL += profit;

                        if (profit > 0) {
                            profitableSells++; // Only increment on profitable sells
                            largestGain = Math.max(largestGain, profit);
                        } else {
                            largestLoss = Math.min(largestLoss, profit);
                        }

                        lastBuyPrice = null;
                        lastBuyQuantity = 0;
                    }
                }
                case SHORT -> {
                    lastBuyPrice = trade.price();
                    lastBuyQuantity = trade.quantity();
                }
                case COVER -> {
                    if (lastBuyPrice != null) {
                        totalSells++; // Count covers as sells for shorts
                        double profit = (lastBuyPrice - trade.price()) * trade.quantity();
                        totalPnL += profit;

                        if (profit > 0) {
                            profitableSells++;
                            largestGain = Math.max(largestGain, profit);
                        } else {
                            largestLoss = Math.min(largestLoss, profit);
                        }

                        lastBuyPrice = null;
                        lastBuyQuantity = 0;
                    }
                }
            }
        }
    }

    private static class WeeklyPerformance {
        private int totalSells;
        private double totalPnL;
        private double profitPerShare;

        public synchronized void recordCompletedTrade(TradeRecord buyTrade, TradeRecord sellTrade) {
            totalSells++;
            double profit;
            if (sellTrade.type() == OrderType.SELL) {
                profit = (sellTrade.price() - buyTrade.price()) * sellTrade.quantity();
            } else { // COVER
                profit = (buyTrade.price() - sellTrade.price()) * sellTrade.quantity();
            }
            profitPerShare = profit / sellTrade.quantity();
            totalPnL += profit;
        }

        public boolean hasActivity() {
            return totalSells > 0 || totalPnL != 0.0;
        }
    }

    private record TradeRecord(
            String ticker,
            OrderType type,
            int quantity,
            double price,
            double portfolioValueBefore,
            LocalDateTime timestamp
    ) {}
}