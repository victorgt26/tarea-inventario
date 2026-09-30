package com.store.inventory.application.port.out;

import com.store.inventory.domain.InventoryState;

import java.util.function.Function;

/**
 * Executes with exclusive access to products and global order IDs.
 * Callbacks must not retain state or perform external I/O. No generic rollback is provided.
 * Persistent implementations must guarantee equivalent cross-instance atomicity.
 */
public interface InventoryPort {
    <T> T execute(Function<InventoryState, T> operation);
}
