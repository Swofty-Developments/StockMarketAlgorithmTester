package net.swofty.orders;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.locks.ReentrantLock;

public class Short {
    private final AtomicInteger quantity;
    private final double entryPrice;
    private final DoubleAdder realizedPnL;
    private volatile LocalDateTime lastUpdateTime;
    private final ReentrantLock positionLock;

    public Short(int initialQuantity, double entryPrice) {
        this.quantity = new AtomicInteger(initialQuantity);
        this.entryPrice = entryPrice;
        this.realizedPnL = new DoubleAdder();
        this.lastUpdateTime = LocalDateTime.now();
        this.positionLock = new ReentrantLock();
    }

    public Short addShares(int additionalShares, double price) {
        positionLock.lock();
        try {
            quantity.addAndGet(additionalShares);
            lastUpdateTime = LocalDateTime.now();
            return this;
        } finally {
            positionLock.unlock();
        }
    }

    public Short removeShares(int sharesToRemove) {
        positionLock.lock();
        try {
            int currentQty = quantity.get();
            if (currentQty < sharesToRemove) {
                throw new IllegalStateException("Insufficient shares to cover");
            }
            quantity.addAndGet(-sharesToRemove);
            lastUpdateTime = LocalDateTime.now();
            return this;
        } finally {
            positionLock.unlock();
        }
    }

    public void updateRealizedPnL(double coverPrice, int quantity) {
        realizedPnL.add((entryPrice - coverPrice) * quantity);
    }

    public int quantity() { return quantity.get(); }
    public double entryPrice() { return entryPrice; }
    public double realizedPnL() { return realizedPnL.sum(); }
    public LocalDateTime lastUpdate() { return lastUpdateTime; }
}