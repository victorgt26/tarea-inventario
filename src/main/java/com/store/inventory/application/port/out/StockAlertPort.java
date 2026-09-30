package com.store.inventory.application.port.out;

import com.store.inventory.api.StockAlertListener;

/**
 * Outbound port reusing the provided notification contract.
 */
@FunctionalInterface
public interface StockAlertPort extends StockAlertListener {
}
