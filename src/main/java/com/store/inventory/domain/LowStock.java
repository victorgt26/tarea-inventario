package com.store.inventory.domain;

/**
 * Availability captured at the business transition, not at delivery time.
 */
public record LowStock(String sku, int availableUnits) {
}
