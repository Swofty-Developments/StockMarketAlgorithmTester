package net.swofty;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.DoubleAdder;

public class AlgorithmStatistics {
    private final String algorithmId;
    private final LocalDateTime startTime;
    private final AtomicInteger totalTrades;
    private final DoubleAdder totalProfit;
    private final DoubleAdder maxDrawdown;
    private final DoubleAdder peakValue;
    private final DoubleAdder sharpeRatio;
    private final DoubleAdder totalValue;
    private final List<Double> dailyReturns;
    private volatile double initialValue;

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
    }

    public void updateStatistics(double currentValue, double riskFreeRate) {
        // Update total profit/loss
        totalProfit.reset();
        totalProfit.add(currentValue - initialValue);

        // Update total value
        totalValue.reset();
        totalValue.add(currentValue);

        // Update peak value and calculate drawdown
        if (currentValue > peakValue.sum()) {
            peakValue.reset();
            peakValue.add(currentValue);
        }
        double currentDrawdown = (peakValue.sum() - currentValue) / peakValue.sum() * 100;
        if (currentDrawdown > maxDrawdown.sum()) {
            maxDrawdown.reset();
            maxDrawdown.add(currentDrawdown);
        }

        // Calculate and store daily return
        double dailyReturn = (currentValue - initialValue) / initialValue;
        dailyReturns.add(dailyReturn);

        // Calculate Sharpe Ratio using proper daily returns
        if (dailyReturns.size() > 1) {
            double averageReturn = dailyReturns.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
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

    public void setTrades(int trades) {
        totalTrades.set(trades);
    }

    public int getTotalTrades() {
        return totalTrades.get();
    }

    public double getTotalProfit() {
        return totalProfit.sum();
    }

    public double getMaxDrawdown() {
        return maxDrawdown.sum();
    }

    public double getSharpeRatio() {
        return sharpeRatio.sum();
    }

    @Override
    public String toString() {
        long daysRun = ChronoUnit.DAYS.between(startTime, LocalDateTime.now());
        double annualizedReturn = dailyReturns.isEmpty() ? 0 :
                Math.pow(1 + dailyReturns.get(dailyReturns.size() - 1), 252) - 1;

        return String.format("""
            Algorithm Statistics for %s:
            Backtest Period: %d days
            Total Trades: %d
            Total Profit/Loss: $%.2f
            Annualized Return: %.2f%%
            Maximum Drawdown: %.2f%%
            Sharpe Ratio: %.2f
            Average Trades Per Day: %.2f
            Total Value: $%.2f
            """,
                algorithmId,
                daysRun,
                totalTrades.get(),
                totalProfit.sum(),
                annualizedReturn * 100,
                maxDrawdown.sum(),
                sharpeRatio.sum(),
                daysRun > 0 ? (double) totalTrades.get() / daysRun : 0,
                totalValue.sum()
        );
    }
}