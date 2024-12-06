package net.swofty.stockmarkettester;

import java.time.LocalTime;
import java.time.ZoneId;

public enum MarketConfig {
    NYSE(
            ZoneId.of("America/New_York"),
            LocalTime.of(9, 30),
            LocalTime.of(16, 0)
    ),
    LSE(
            ZoneId.of("Europe/London"),
            LocalTime.of(8, 0),
            LocalTime.of(16, 30)
    ),
    TSE(
            ZoneId.of("Asia/Tokyo"),
            LocalTime.of(9, 0),
            LocalTime.of(15, 30)
    );

    public final ZoneId zoneId;
    public final LocalTime openTime;
    public final LocalTime closeTime;

    MarketConfig(ZoneId zoneId, LocalTime openTime, LocalTime closeTime) {
        this.zoneId = zoneId;
        this.openTime = openTime;
        this.closeTime = closeTime;
    }

}