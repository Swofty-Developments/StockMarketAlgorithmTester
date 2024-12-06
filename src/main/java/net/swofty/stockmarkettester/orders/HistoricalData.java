package net.swofty.stockmarkettester.orders;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;

public class HistoricalData implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;
    private final ConcurrentNavigableMap<LocalDateTime, MarketDataPoint> historicalPoints;
    private final String ticker;

    public HistoricalData(String ticker) {
        this.ticker = Objects.requireNonNull(ticker, "ticker cannot be null");
        this.historicalPoints = new ConcurrentSkipListMap<>();
    }

    /**
     * Adds a market data point to the historical time series.
     * Thread-safe implementation using ConcurrentNavigableMap.
     *
     * @param point Market data point to add
     * @throws NullPointerException if point is null
     * @throws IllegalArgumentException if point ticker doesn't match
     */
    public void addDataPoint(MarketDataPoint point) {
        Objects.requireNonNull(point, "point cannot be null");
        if (!ticker.equals(point.symbol())) {
            throw new IllegalArgumentException(
                    "Point ticker " + point.symbol() +
                            " doesn't match historical data ticker " + ticker);
        }
        historicalPoints.put(point.timestamp(), point);
    }

    /**
     * Calculates percentage price change over specified time period.
     * Uses floor entry lookup for precise time-based comparison.
     *
     * @param seconds Time period to calculate change over
     * @return Percentage price change
     * @throws IllegalStateException if insufficient data points
     */
    public double getPercentageChange(int seconds) {
        if (seconds <= 0) {
            throw new IllegalArgumentException("Seconds must be positive");
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime past = now.minusSeconds(seconds);

        Map.Entry<LocalDateTime, MarketDataPoint> currentEntry =
                historicalPoints.floorEntry(now);
        Map.Entry<LocalDateTime, MarketDataPoint> pastEntry =
                historicalPoints.floorEntry(past);

        if (currentEntry == null || pastEntry == null) {
            throw new IllegalStateException(
                    "Insufficient historical data for " + seconds + " second range");
        }

        MarketDataPoint currentPoint = currentEntry.getValue();
        MarketDataPoint pastPoint = pastEntry.getValue();

        return ((currentPoint.close() - pastPoint.close()) / pastPoint.close()) * 100;
    }

    /**
     * Retrieves all data points within specified time range.
     * Returns immutable list to prevent external modifications.
     *
     * @param start Start of time range (inclusive)
     * @param end End of time range (inclusive)
     * @return List of market data points
     * @throws IllegalArgumentException if start is after end
     */
    public List<MarketDataPoint> getDataPoints(LocalDateTime start, LocalDateTime end) {
        Objects.requireNonNull(start, "start cannot be null");
        Objects.requireNonNull(end, "end cannot be null");

        if (start.isAfter(end)) {
            throw new IllegalArgumentException("Start time must not be after end time");
        }

        return Collections.unmodifiableList(
                new ArrayList<>(historicalPoints.subMap(start, true, end, true).values())
        );
    }

    /**
     * Returns the ticker symbol associated with this historical data.
     *
     * @return Ticker symbol
     */
    public String getTicker() {
        return ticker;
    }

    /**
     * Returns the number of historical data points stored.
     *
     * @return Count of stored data points
     */
    public int getPointCount() {
        return historicalPoints.size();
    }

    /**
     * Clears all historical data points while maintaining thread safety.
     */
    public void clear() {
        historicalPoints.clear();
    }
}