package com.store.inventory.domain;

import java.util.HashMap;
import java.util.Map;

/**
 * In-memory data container. Access is restricted to the repository callback.
 */
public final class InventoryState {
    private final Map<String, StockProduct> products = new HashMap<>();
    private final Map<String, OrderReservation> orders = new HashMap<>();

    public Map<String, StockProduct> getProducts() {
        return products;
    }

    public Map<String, OrderReservation> getOrders() {
        return orders;
    }
}
