package com.store.inventory;

import com.store.inventory.api.InventoryService;
import com.store.inventory.api.StockAlertListener;
import com.store.inventory.application.usecase.InventoryUseCases;
import com.store.inventory.application.usecase.ReservationPolicies;
import com.store.inventory.infrastructure.adapters.in.InventoryServiceAdapter;
import com.store.inventory.infrastructure.adapters.out.notification.ListenerStockAlertAdapter;
import com.store.inventory.infrastructure.adapters.out.persistence.InMemoryInventoryRepository;

import java.time.Clock;

/**
 * Composition root; preserves the public factory contract.
 */
public final class Inventory {
    private Inventory() {
    }

    public static InventoryService create(Clock clock, StockAlertListener alertListener) {
        return new InventoryServiceAdapter(
                new InventoryUseCases(
                        clock,
                        new ListenerStockAlertAdapter(alertListener),
                        new InMemoryInventoryRepository(),
                        ReservationPolicies.defaults()
                )
        );
    }
}
