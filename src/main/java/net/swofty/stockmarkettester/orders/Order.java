package net.swofty.stockmarkettester.orders;

public record Order(
        String ticker,
        OrderType type,
        int quantity,
        double price,
        String algorithmId
) {}