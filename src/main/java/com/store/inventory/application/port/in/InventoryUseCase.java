package com.store.inventory.application.port.in;

import com.store.inventory.api.InventoryService;

/**
 * Inbound port reusing the existing operations without redeclaring them.
 */
public interface InventoryUseCase extends InventoryService {
}
