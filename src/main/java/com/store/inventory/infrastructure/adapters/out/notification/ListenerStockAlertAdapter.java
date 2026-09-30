package com.store.inventory.infrastructure.adapters.out.notification;

import com.store.inventory.api.StockAlertListener;
import com.store.inventory.application.port.out.StockAlertPort;

import java.util.Objects;

/**
 * Connects the externally provided listener to the outbound application port.
 */
public final class ListenerStockAlertAdapter implements StockAlertPort {
    private final StockAlertListener listener;

    public ListenerStockAlertAdapter(StockAlertListener listener) {
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    @Override
    public void onLowStock(String sku, int availableUnits) {
        listener.onLowStock(sku, availableUnits);
    }
}
