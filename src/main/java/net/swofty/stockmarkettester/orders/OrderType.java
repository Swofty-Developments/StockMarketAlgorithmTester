package net.swofty.stockmarkettester.orders;

public enum OrderType {
    BUY,    // Open or add to long position
    SELL,   // Close or reduce long position
    SHORT,  // Open or add to short position
    COVER   // Close or reduce short position
}