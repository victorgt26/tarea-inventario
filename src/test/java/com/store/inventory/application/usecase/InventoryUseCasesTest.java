package com.store.inventory.application.usecase;

import com.store.inventory.api.*;
import com.store.inventory.application.port.out.InventoryPort;
import com.store.inventory.domain.InventoryState;
import com.store.inventory.domain.ReservationPolicy;
import java.time.*;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the application directly, without infrastructure adapters. */
class InventoryUseCasesTest {
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private final TestRepository repository = new TestRepository();

    private InventoryUseCases useCases(Instant now) {
        return new InventoryUseCases(Clock.fixed(now, ZoneOffset.UTC), (sku, units) -> { },
                repository, ReservationPolicies.defaults());
    }

    @Test
    void validatesInputAndStockWithoutAnInboundAdapter() {
        var service = useCases(START);
        assertThrows(IllegalArgumentException.class, () -> service.registerProduct(" ", ProductCategory.STANDARD));
        service.registerProduct("sku", ProductCategory.STANDARD);
        assertThrows(IllegalArgumentException.class, () -> service.addStock("sku", 0));
        service.addStock("sku", 10);
        assertThrows(IllegalArgumentException.class, () -> service.reserve("order", "sku", -1));
        assertThrows(InsufficientStockException.class, () -> service.reserve("order", "sku", 11));
        assertEquals(10, service.available("sku"));
    }

    @Test
    void enforcesTerminalReservationStatesAndIdempotency() {
        var service = useCases(START);
        service.registerProduct("sku", ProductCategory.STANDARD);
        service.addStock("sku", 10);
        var original = service.reserve("confirmed", "sku", 2);
        service.confirm("confirmed");
        service.confirm("confirmed");
        service.reserve("expired", "sku", 3);
        var later = useCases(START.plus(Duration.ofMinutes(15)));
        assertThrows(IllegalStateException.class, () -> later.confirm("expired"));
        assertThrows(IllegalStateException.class, () -> later.reserve("expired", "sku", 3));
        assertEquals(original, later.reserve("confirmed", "sku", 2));
        assertThrows(IllegalArgumentException.class, () -> later.reserve("confirmed", "sku", 3));
        assertEquals(8, later.available("sku"));
    }

    @Test
    void validatesPolicyDataAtApplicationBoundary() {
        for (var invalid : new ReservationPolicy[] {
                new ReservationPolicy(Duration.ZERO, 2),
                new ReservationPolicy(Duration.ofMinutes(-1), 2),
                new ReservationPolicy(null, 2),
                new ReservationPolicy(Duration.ofMinutes(5), 0)}) {
            var policies = new HashMap<>(ReservationPolicies.defaults());
            policies.put(ProductCategory.STANDARD, invalid);
            assertThrows(IllegalArgumentException.class, () -> new InventoryUseCases(
                    Clock.fixed(START, ZoneOffset.UTC), (sku, units) -> { }, repository, policies));
        }
        assertThrows(IllegalArgumentException.class, () -> new InventoryUseCases(
                Clock.fixed(START, ZoneOffset.UTC), (sku, units) -> { }, repository, Map.of()));
    }

    @Test
    void enforcesCategoryLimitsWithoutAnInboundAdapter() {
        var service = useCases(START);
        service.registerProduct("sku", ProductCategory.FLASH_SALE);
        service.addStock("sku", 10);
        assertThrows(OrderLimitExceededException.class, () -> service.reserve("order", "sku", 3));
        assertEquals(10, service.available("sku"));
        assertEquals(START.plus(Duration.ofMinutes(5)), service.reserve("order", "sku", 2).expiresAt());
    }

    private static final class TestRepository implements InventoryPort {
        private final InventoryState state = new InventoryState();
        @Override
        public <T> T execute(Function<InventoryState, T> operation) { return operation.apply(state); }
    }
}
