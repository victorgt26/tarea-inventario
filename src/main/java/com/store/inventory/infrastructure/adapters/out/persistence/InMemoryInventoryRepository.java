package com.store.inventory.infrastructure.adapters.out.persistence;

import com.store.inventory.application.port.out.InventoryPort;
import com.store.inventory.domain.InventoryState;

import java.util.function.Function;

/**
 * Serializes access to product data and global order IDs in this instance.
 */
public final class InMemoryInventoryRepository implements InventoryPort {
    private final InventoryState state = new InventoryState();

    @Override
    public synchronized <T> T execute(Function<InventoryState, T> operation) {
        return operation.apply(state);
    }
}
