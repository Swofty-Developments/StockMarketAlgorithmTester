package net.swofty.stockmarkettester.builtin;

import net.swofty.stockmarkettester.Portfolio;
import net.swofty.stockmarkettester.fetchers.AlphaVantageFetcher;
import net.swofty.stockmarkettester.orders.MarketDataPoint;
import net.swofty.stockmarkettester.orders.Order;
import net.swofty.stockmarkettester.orders.OrderType;
import net.swofty.stockmarkettester.user.Algorithm;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

public class SimpleBuyAndHoldAlgorithm implements Algorithm {
    private final String algorithmId;
    private final Set<String> tickers;
    private final Map<String, Boolean> positionHeld;
    private static final ZoneId MARKET_TIMEZONE = ZoneId.of("America/New_York");

    public SimpleBuyAndHoldAlgorithm(String algorithmId, Set<String> tickers) {
        this.algorithmId = algorithmId;
        this.tickers = new HashSet<>(tickers);
        this.positionHeld = new HashMap<>();
        tickers.forEach(ticker -> positionHeld.put(ticker, false));
    }

    @Override
    public void onMarketOpen(Map<String, MarketDataPoint> initialData) {
        // No need to reset positions as we're holding long term
    }

    @Override
    public void onMarketClose(Map<String, MarketDataPoint> finalData) {
        // Do nothing as we're holding the positions
    }

    @Override
    public void onUpdate(Map<String, MarketDataPoint> currentData, LocalDateTime timestamp, Portfolio portfolio) {
        for (String ticker : tickers) {
            MarketDataPoint data = currentData.get(ticker);
            if (data == null) {
                System.out.println("No data available for ticker: " + ticker);
                continue;
            }

            // Only buy if we don't already have a position
            if (!positionHeld.get(ticker)) {
                portfolio.buyStock(ticker, calculatePositionSize(data.close()), data.close());
                positionHeld.put(ticker, true);

                System.out.println("Buying " + ticker + " at $" + data.close() +
                        " - Quantity: " + calculatePositionSize(data.close()));
            }
        }
    }

    private int calculatePositionSize(double price) {
        // Simple position sizing: Try to invest ~$10,000 per trade
        return (int) (10_000 / price);
    }

    @Override
    public String getAlgorithmId() {
        return algorithmId;
    }
}