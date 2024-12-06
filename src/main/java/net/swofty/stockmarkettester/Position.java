package net.swofty.stockmarkettester;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.locks.ReentrantLock;

public class Position {
    private final AtomicInteger quantity;
    private final DoubleAdder costBasis;
    private final DoubleAdder realizedPnL;
    private volatile LocalDateTime lastUpdateTime;
    private final ReentrantLock positionLock;

    public Position(int initialQuantity, double initialPrice) {
        this.quantity = new AtomicInteger(initialQuantity);
        this.costBasis = new DoubleAdder();
        this.costBasis.add(initialQuantity * initialPrice);
        this.realizedPnL = new DoubleAdder();
        this.lastUpdateTime = LocalDateTime.now();
        this.positionLock = new ReentrantLock();
    }

    public Position addShares(int additionalShares, double price) {
        positionLock.lock();
        try {
            int newQuantity = quantity.addAndGet(additionalShares);
            costBasis.add(additionalShares * price);
            lastUpdateTime = LocalDateTime.now();
            return this;
        } finally {
            positionLock.unlock();
        }
    }

    public Position removeShares(int sharesToRemove) {
        positionLock.lock();
        try {
            int currentQty = quantity.get();
            if (currentQty < sharesToRemove) {
                throw new IllegalStateException(
                        String.format("Insufficient shares. Required: %d, Available: %d",
                                sharesToRemove, currentQty)
                );
            }

            int remainingShares = quantity.addAndGet(-sharesToRemove);
            double averageCost = costBasis.sum() / currentQty;
            costBasis.add(-(sharesToRemove * averageCost));

            if (remainingShares == 0) {
                costBasis.reset();
            }

            lastUpdateTime = LocalDateTime.now();
            return this;
        } finally {
            positionLock.unlock();
        }
    }

    public void updateRealizedPnL(double salePrice, int quantity) {
        double averageCost = costBasis.sum() / this.quantity.get();
        realizedPnL.add((salePrice - averageCost) * quantity);
    }

    public int quantity() { return quantity.get(); }
    public double costBasis() { return costBasis.sum(); }
    public double realizedPnL() { return realizedPnL.sum(); }
    public LocalDateTime lastUpdate() { return lastUpdateTime; }
    public double averageCost() { return costBasis.sum() / quantity.get(); }
}