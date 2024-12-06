package net.swofty.stockmarkettester.orders;

import java.time.LocalDateTime;

public record Option(
        String ticker,
        OptionType type,
        double strikePrice,
        LocalDateTime expiration,
        int contracts,
        double premium
) {
    public double getCurrentValue(double currentPrice) {
        if (LocalDateTime.now().isAfter(expiration)) {
            return 0;
        }

        double intrinsicValue = switch (type) {
            case CALL -> Math.max(0, currentPrice - strikePrice);
            case PUT -> Math.max(0, strikePrice - currentPrice);
        };

        return (intrinsicValue - premium) * contracts * 100;
    }
}
