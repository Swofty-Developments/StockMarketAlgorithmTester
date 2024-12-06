package net.swofty.stockmarkettester.orders;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Objects;

public final class MarketDataPoint implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;
    private final String symbol;
    private final double open;
    private final double high;
    private final double low;
    private final double close;
    private final double volume;
    private final LocalDateTime timestamp;

    public MarketDataPoint(String symbol, double open, double high, double low, double close, double volume, LocalDateTime timestamp) {
        this.symbol = Objects.requireNonNull(symbol, "symbol cannot be null");
        this.open = open;
        this.high = high;
        this.low = low;
        this.close = close;
        this.volume = volume;
        this.timestamp = Objects.requireNonNull(timestamp, "timestamp cannot be null");
    }

    public String symbol() { return symbol; }
    public double open() { return open; }
    public double high() { return high; }
    public double low() { return low; }
    public double close() { return close; }
    public double volume() { return volume; }
    public LocalDateTime timestamp() { return timestamp; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MarketDataPoint that)) return false;
        return Double.compare(that.open, open) == 0 &&
                Double.compare(that.high, high) == 0 &&
                Double.compare(that.low, low) == 0 &&
                Double.compare(that.close, close) == 0 &&
                Double.compare(that.volume, volume) == 0 &&
                Objects.equals(symbol, that.symbol) &&
                Objects.equals(timestamp, that.timestamp);
    }

    @Override
    public int hashCode() {
        return Objects.hash(symbol, open, high, low, close, volume, timestamp);
    }
}