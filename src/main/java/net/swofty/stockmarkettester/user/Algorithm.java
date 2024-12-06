package net.swofty.stockmarkettester.user;

import net.swofty.stockmarkettester.Portfolio;
import net.swofty.stockmarkettester.orders.MarketDataPoint;
import net.swofty.stockmarkettester.orders.Order;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public interface Algorithm {
    void onMarketOpen(Map<String, MarketDataPoint> initialData);
    void onMarketClose(Map<String, MarketDataPoint> finalData);
    void onUpdate(Map<String, MarketDataPoint> currentData, LocalDateTime timestamp, Portfolio portfolio);
    String getAlgorithmId();
}
