package net.swofty.builtin;

import net.swofty.MarketConfig;
import net.swofty.Portfolio;
import net.swofty.stockloopers.BacktestBuilder;
import net.swofty.data.HistoricalMarketService;
import net.swofty.data.providers.AlphaVantageProvider;
import net.swofty.fetchers.AlphaVantageFetcher;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;

public class TestAlgorithm {
    public static void main(String[] args) {
        // Create market service
        HistoricalMarketService marketService = new HistoricalMarketService(
                new AlphaVantageProvider("KEY"),
                1,
                Path.of("PATH/StockMarketAlgorithmMaker/cache/")
        );
        // Define tickers to trade
        Set<String> tickers = Set.of("TSLA");

        // Initialize the service with all required data
        marketService.initialize(tickers, 30, MarketConfig.NYSE)
                .thenRun(() -> {
                    // Create and run stockloopers after initialization
                    new BacktestBuilder()
                            .withStocks(tickers.toArray(new String[0]))
                            .withPreviousDays(30)
                            .withLimitTimesToMarket(MarketConfig.NYSE)
                            .withShouldPrint(false)
                            .withInterval(Duration.ofMinutes(1))
                            .withRunOnMarketClosed(true)  // Enable running outside market hours
                            .withProvider(marketService)  // Use the initialized service
                            .withAutomaticallySellOnFinish(true)
                            .withAlgorithm(new SimpleBuyAndHoldAlgorithm("simple-day-trader", tickers), 1_000_000)
                            .run()
                            .thenAccept(results -> {
                                System.out.println("Received results: " + results);

                                Portfolio portfolio = results.portfolios().get("simple-day-trader");
                            })
                            .exceptionally(error -> {
                                System.err.println("Backtest failed: " + error.getMessage());
                                error.printStackTrace();
                                return null;
                            })
                            .join(); // Wait for stockloopers to complete
                })
                .exceptionally(error -> {
                    System.err.println("Failed to initialize market service: " + error.getMessage());
                    error.printStackTrace();
                    return null;
                });
    }
}
