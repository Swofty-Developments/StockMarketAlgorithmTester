package net.swofty.stockloopers;

import net.swofty.MarketConfig;
import net.swofty.data.HistoricalMarketService;
import net.swofty.data.MarketDataProvider;
import net.swofty.user.Algorithm;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class BacktestBuilder {
    private Set<String> tickers = new HashSet<>();
    private int previousDays = 30;
    private HistoricalMarketService provider = null;
    private Map<Algorithm, Long> algorithms = new HashMap<>();
    private boolean shouldPrint = true;
    private Duration interval;
    private MarketConfig marketConfig = MarketConfig.NYSE; // Default to NYSE
    private boolean runOnMarketClosed = false;

    public BacktestBuilder withRunOnMarketClosed(boolean runOnMarketClosed) {
        this.runOnMarketClosed = runOnMarketClosed;
        return this;
    }

    public BacktestBuilder withLimitTimesToMarket(MarketConfig market) {
        this.marketConfig = market;
        return this;
    }

    public BacktestBuilder withStocks(String... tickers) {
        this.tickers.addAll(Arrays.asList(tickers));
        return this;
    }

    public BacktestBuilder withPreviousDays(int days) {
        this.previousDays = days;
        return this;
    }

    public BacktestBuilder withProvider(MarketDataProvider provider) {
        this.provider = new HistoricalMarketService(provider, 1);
        return this;
    }

    public BacktestBuilder withProvider(HistoricalMarketService provider) {
        this.provider = provider;
        return this;
    }

    public BacktestBuilder withAlgorithm(Algorithm algorithm, long initialValue) {
        this.algorithms.put(algorithm, initialValue);
        return this;
    }

    public BacktestBuilder withInterval(Duration interval) {
        this.interval = interval;
        return this;
    }

    public BacktestBuilder withInterval(long amount, TimeUnit unit) {
        this.interval = Duration.ofMillis(unit.toMillis(amount));
        return this;
    }

    public BacktestBuilder withShouldPrint(boolean shouldPrint) {
        this.shouldPrint = shouldPrint;
        return this;
    }

    public CompletableFuture<StockBackTester.BacktestResults> run() {
        validate();

        if (shouldPrint) {
            System.out.println("Starting stockloopers...");
        }

        StockBackTester backtester = new StockBackTester(provider, tickers, previousDays, interval, marketConfig, runOnMarketClosed, shouldPrint);
        algorithms.forEach(backtester::addAlgorithm);
        return backtester.runBacktest(shouldPrint);
    }

    private void validate() {
        if (tickers.isEmpty()) {
            throw new IllegalStateException("At least one stock ticker must be specified");
        }
        if (provider == null) {
            throw new IllegalStateException("The data provider must be specified");
        }
        if (algorithms.isEmpty()) {
            throw new IllegalStateException("At least one algorithm must be specified");
        }
        if (previousDays <= 0) {
            throw new IllegalStateException("Previous days must be positive");
        }
        if (interval == null) {
            throw new IllegalStateException("Interval must be specified");
        }
        if (interval.isNegative() || interval.isZero()) {
            throw new IllegalStateException("Interval cannot be negative or zero");
        }
    }
}