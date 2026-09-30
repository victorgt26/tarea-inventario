package com.store.inventory.domain;

import com.store.inventory.api.ProductCategory;

/**
 * Immutable product data. Application use cases perform all stock transitions.
 */
public record StockProduct(ProductCategory category, int available, int onHand, boolean alerted) {
}
