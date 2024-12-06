package net.swofty.stockmarkettester;

import lombok.Getter;
import net.swofty.stockmarkettester.exceptions.InsufficientFundsException;
import net.swofty.stockmarkettester.orders.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class Portfolio {
    @Getter
    private int totalPositions;
    private final Map<String, Position> positions;
    private final Map<String, List<Option>> options;
    private final Map<String, List<StopOrder>> stopOrders;
    private final Map<String, ShortOrder> shortPositions;
    private final Map<String, Double> cachedValues;
    private double cash;
    private double marginAvailable;
    private static final double MARGIN_REQUIREMENT = 0.5; // 50% margin requirement

    public Portfolio(double initialCash) {
        this.totalPositions = 0;
        this.positions = new ConcurrentHashMap<>();
        this.options = new ConcurrentHashMap<>();
        this.stopOrders = new ConcurrentHashMap<>();
        this.shortPositions = new ConcurrentHashMap<>();
        this.cachedValues = new ConcurrentHashMap<>();
        this.cash = initialCash;
        this.marginAvailable = initialCash * 2; // 2x leverage
    }

    // Long position methods
    public synchronized void buyStock(String ticker, int quantity, double price) {
        totalPositions++;

        double totalCost = quantity * price;
        if (totalCost > cash) {
            throw new InsufficientFundsException("Insufficient funds for purchase");
        }

        positions.compute(ticker, (k, v) -> {
            if (v == null) {
                return new Position(quantity, price);
            }
            return v.addShares(quantity, price);
        });
        cash -= totalCost;
    }

    public synchronized void sellStock(String ticker, int quantity, double price) {
        totalPositions++;

        Position position = positions.get(ticker);
        if (position == null || position.quantity() < quantity) {
            throw new InsufficientFundsException("Insufficient shares for sale");
        }

        positions.computeIfPresent(ticker, (k, v) -> {
            v.updateRealizedPnL(price, quantity);
            return v.removeShares(quantity);
        });
        cash += quantity * price;
    }

    // Short selling methods
    public synchronized void shortStock(String ticker, int quantity, double price) {
        totalPositions++;

        double marginRequired = quantity * price * MARGIN_REQUIREMENT;
        if (marginRequired > marginAvailable) {
            throw new InsufficientFundsException("Insufficient margin available");
        }

        shortPositions.compute(ticker, (k, v) -> {
            ShortOrder currentShort = v == null ? new ShortOrder(quantity, price) : v.addShares(quantity, price);
            marginAvailable -= marginRequired;
            cash += quantity * price; // Proceeds from short sale
            return currentShort;
        });
    }

    public synchronized void coverShort(String ticker, int quantity, double price) {
        totalPositions++;

        ShortOrder shortPosition = shortPositions.get(ticker);
        if (shortPosition == null || shortPosition.quantity() < quantity) {
            throw new IllegalStateException("No short position to cover");
        }

        double totalCost = quantity * price;
        if (totalCost > cash) {
            throw new InsufficientFundsException("Insufficient funds to cover short");
        }

        shortPositions.computeIfPresent(ticker, (k, v) -> {
            v.updateRealizedPnL(price, quantity);
            return v.removeShares(quantity);
        });
        cash -= totalCost;
        marginAvailable += quantity * shortPosition.entryPrice() * MARGIN_REQUIREMENT;
    }

    public Map<String, Position> getAllPositions() {
        return positions;
    }

    public Map<String, ShortOrder> getAllShortPositions() {
        return shortPositions;
    }

    // Stop order methods
    public void setStopLoss(String ticker, double stopPrice, int quantity) {
        totalPositions++;

        stopOrders.computeIfAbsent(ticker, k -> new ArrayList<>())
                .add(new StopOrder(ticker, stopPrice, quantity, StopOrder.Type.STOP_LOSS));
    }

    public void setTakeProfit(String ticker, double targetPrice, int quantity) {
        totalPositions++;

        stopOrders.computeIfAbsent(ticker, k -> new ArrayList<>())
                .add(new StopOrder(ticker, targetPrice, quantity, StopOrder.Type.TAKE_PROFIT));
    }

    // Options trading methods
    public void buyOption(String ticker, OptionType type, double strikePrice,
                          LocalDateTime expiration, int contracts, double premium) {
        totalPositions++;

        double totalCost = contracts * premium * 100; // Each contract is 100 shares
        if (totalCost > cash) {
            throw new InsufficientFundsException("Insufficient funds for option purchase");
        }

        options.computeIfAbsent(ticker, k -> new ArrayList<>())
                .add(new Option(ticker, type, strikePrice, expiration, contracts, premium));
        cash -= totalCost;
    }

    // Position tracking methods
    public double getPositionValue(String ticker, MarketDataPoint currentPrice) {
        Position position = positions.get(ticker);
        return position != null ? position.quantity() * currentPrice.close() : 0;
    }

    public double getUnrealizedPnL(String ticker, MarketDataPoint currentPrice) {
        Position position = positions.get(ticker);
        if (position == null) return 0;

        return position.quantity() * (currentPrice.close() - position.averageCost());
    }

    public double getRealizedPnL(String ticker) {
        Position position = positions.get(ticker);
        return position != null ? position.realizedPnL() : 0;
    }

    public double getTotalValue(Map<String, MarketDataPoint> currentPrices) {
        positions.forEach((k, v) -> {
            if (currentPrices.containsKey(k)) {
                cachedValues.put(k, currentPrices.get(k).close());
            }
        });

        double longValue = positions.entrySet().stream()
                .mapToDouble(entry -> {
                    MarketDataPoint price = currentPrices.get(entry.getKey());
                    if (price == null) return cachedValues.get(entry.getKey());
                    return entry.getValue().quantity() * price.close();
                })
                .sum();

        double shortValue = shortPositions.entrySet().stream()
                .mapToDouble(entry -> {
                    MarketDataPoint price = currentPrices.get(entry.getKey());
                    if (price == null) return cachedValues.get(entry.getKey());
                    return -entry.getValue().quantity() * price.close();
                })
                .sum();

        double optionsValue = calculateOptionsValue(currentPrices);

        return cash + longValue + shortValue + optionsValue;
    }

    private double calculateOptionsValue(Map<String, MarketDataPoint> currentPrices) {
        return options.entrySet().stream()
                .flatMap(entry -> entry.getValue().stream())
                .mapToDouble(option -> option.getCurrentValue(currentPrices.get(option.ticker()).close()))
                .sum();
    }

    // Utility methods
    public double getCash() {
        return cash;
    }

    public double getMarginAvailable() {
        return marginAvailable;
    }

    public Position getPosition(String ticker) {
        return positions.get(ticker);
    }

    public ShortOrder getShortPosition(String ticker) {
        return shortPositions.get(ticker);
    }

    public List<Option> getOptions(String ticker) {
        return options.getOrDefault(ticker, new ArrayList<>());
    }

    public List<StopOrder> getStopOrders(String ticker) {
        return stopOrders.getOrDefault(ticker, new ArrayList<>());
    }
}