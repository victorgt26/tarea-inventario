package com.store.inventory.infrastructure.adapters.in;

import com.store.inventory.api.InventoryService;
import com.store.inventory.api.ProductCategory;
import com.store.inventory.api.Reservation;
import com.store.inventory.application.port.in.InventoryUseCase;

import java.util.Objects;

/**
 * Public entry adapter; delegates without duplicating models or business rules.
 */
public final class InventoryServiceAdapter implements InventoryService {
    private final InventoryUseCase useCase;

    public InventoryServiceAdapter(InventoryUseCase useCase) {
        this.useCase = Objects.requireNonNull(useCase, "useCase");
    }

    @Override
    public void registerProduct(String sku, ProductCategory category) {
        useCase.registerProduct(sku, category);
    }

    @Override
    public void addStock(String sku, int quantity) {
        useCase.addStock(sku, quantity);
    }

    @Override
    public Reservation reserve(String orderId, String sku, int quantity) {
        return useCase.reserve(orderId, sku, quantity);
    }

    @Override
    public void confirm(String orderId) {
        useCase.confirm(orderId);
    }

    @Override
    public int available(String sku) {
        return useCase.available(sku);
    }
}
