package net.swofty.orders;

public record StopOrder(
        String ticker,
        double price,
        int quantity,
        Type type
) {
    public enum Type {
        STOP_LOSS,
        TAKE_PROFIT
    }
}