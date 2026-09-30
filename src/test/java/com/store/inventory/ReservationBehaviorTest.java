package com.store.inventory;

import com.store.inventory.api.*;
import java.time.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;

class ReservationBehaviorTest {
    private final MutableClock clock = new MutableClock();
    private final List<Integer> alerts = new ArrayList<>();
    private final InventoryService service = Inventory.create(clock, (sku, units) -> alerts.add(units));

    private void stock(ProductCategory category, int units) {
        service.registerProduct("sku", category);
        service.addStock("sku", units);
    }

    @ParameterizedTest
    @EnumSource(ProductCategory.class)
    void categoryDeadlineAndExactExpiry(ProductCategory category) {
        stock(category, 10);
        Duration lifetime = switch (category) {
            case STANDARD -> Duration.ofMinutes(15);
            case PRE_ORDER -> Duration.ofHours(24);
            case FLASH_SALE -> Duration.ofMinutes(5);
        };
        var reservation = service.reserve("order", "sku", 2);
        assertEquals(clock.instant().plus(lifetime), reservation.expiresAt());
        clock.advance(lifetime.minusNanos(1));
        assertEquals(8, service.available("sku"));
        clock.advance(Duration.ofNanos(1));
        assertEquals(10, service.available("sku"));
        assertThrows(IllegalStateException.class, () -> service.confirm("order"));
        assertThrows(IllegalStateException.class, () -> service.reserve("order", "sku", 2));
        assertEquals(10, service.available("sku"));
        service.reserve("new-order", "sku", 2);
    }

    @Test
    void retriesKeepOriginalDeadlineAndConfirmedOrdersStaySold() {
        stock(ProductCategory.STANDARD, 10);
        var original = service.reserve("order", "sku", 4);
        clock.advance(Duration.ofMinutes(2));
        assertEquals(original, service.reserve("order", "sku", 4));
        service.confirm("order");
        service.confirm("order");
        clock.advance(Duration.ofDays(2));
        assertEquals(original, service.reserve("order", "sku", 4));
        assertEquals(6, service.available("sku"));
    }

    @Test
    void conflictsDoNotModifyStock() {
        stock(ProductCategory.STANDARD, 10);
        service.reserve("order", "sku", 2);
        assertThrows(IllegalArgumentException.class, () -> service.reserve("order", "sku", 3));
        assertThrows(IllegalArgumentException.class, () -> service.reserve("order", "other", 2));
        service.registerProduct("sku", ProductCategory.STANDARD);
        assertThrows(IllegalArgumentException.class, () -> service.registerProduct("sku", ProductCategory.PRE_ORDER));
        assertEquals(8, service.available("sku"));
    }

    @Test
    void categoryLimitAndFailedRequestCanBeRetried() {
        stock(ProductCategory.FLASH_SALE, 10);
        assertThrows(OrderLimitExceededException.class, () -> service.reserve("order", "sku", 3));
        service.reserve("order", "sku", 2);
        assertEquals(8, service.available("sku"));
        var other = Inventory.create(clock, (sku, units) -> { });
        for (var category : List.of(ProductCategory.STANDARD, ProductCategory.PRE_ORDER)) {
            other.registerProduct(category.name(), category);
            other.addStock(category.name(), 100);
            other.reserve(category.name(), category.name(), 100);
            assertEquals(0, other.available(category.name()));
        }
    }

    @Test
    void alertsAreRearmedOnlyByReplenishment() {
        stock(ProductCategory.STANDARD, 10);
        service.reserve("one", "sku", 5);
        service.reserve("one", "sku", 5);
        service.reserve("two", "sku", 1);
        assertEquals(List.of(5), alerts);
        clock.advance(Duration.ofMinutes(15));
        assertEquals(10, service.available("sku"));
        service.reserve("three", "sku", 6);
        assertEquals(List.of(5), alerts);
        service.addStock("sku", 1);
        assertEquals(List.of(5, 5), alerts);
        service.reserve("four", "sku", 1);
        service.addStock("sku", 10);
        service.reserve("five", "sku", 9);
        assertEquals(List.of(5, 5, 5), alerts);
    }

    @Test
    void expiryIsAppliedByReserveAndConfirmWithoutAnAvailabilityRead() {
        stock(ProductCategory.STANDARD, 10);
        service.reserve("old", "sku", 10);
        clock.advance(Duration.ofMinutes(15));
        assertThrows(IllegalStateException.class, () -> service.confirm("old"));
        service.reserve("new", "sku", 10);
        clock.advance(Duration.ofMinutes(15));
        service.reserve("newer", "sku", 10);
        assertEquals(0, service.available("sku"));
    }

    @Test
    void invalidInputUnknownProductsAndOverflow() {
        assertEquals(0, service.available("unknown"));
        assertThrows(IllegalArgumentException.class, () -> service.addStock("unknown", 1));
        assertThrows(InsufficientStockException.class, () -> service.reserve("order", "unknown", 1));
        assertThrows(IllegalStateException.class, () -> service.confirm("missing"));
        stock(ProductCategory.STANDARD, Integer.MAX_VALUE);
        for (int quantity : new int[] {0, -1}) {
            assertThrows(IllegalArgumentException.class, () -> service.reserve("order", "sku", quantity));
            assertThrows(IllegalArgumentException.class, () -> service.addStock("sku", quantity));
        }
        for (String identifier : new String[] {null, "", " "}) {
            assertThrows(IllegalArgumentException.class, () -> service.reserve(identifier, "sku", 1));
            assertThrows(IllegalArgumentException.class, () -> service.available(identifier));
        }
        service.reserve("order", "sku", 10);
        assertThrows(IllegalArgumentException.class, () -> service.addStock("sku", 1));
        clock.advance(Duration.ofMinutes(15));
        assertEquals(Integer.MAX_VALUE, service.available("sku"));
    }

    @Test
    void listenerFailureDoesNotInvalidateCommittedReservation() {
        var failing = Inventory.create(clock, (sku, units) -> { throw new IllegalStateException("mail unavailable"); });
        failing.registerProduct("sku", ProductCategory.STANDARD);
        failing.addStock("sku", 10);
        var result = assertDoesNotThrow(() -> failing.reserve("order", "sku", 5));
        assertEquals(result, failing.reserve("order", "sku", 5));
        assertEquals(5, failing.available("sku"));
    }

    @Test
    void listenerRunsOutsideRepositoryLock() {
        AtomicReference<InventoryService> reference = new AtomicReference<>();
        try (var executor = Executors.newSingleThreadExecutor()) {
            reference.set(Inventory.create(clock, (sku, units) -> {
                try {
                    assertEquals(units, executor.submit(() -> reference.get().available(sku)).get(2, TimeUnit.SECONDS));
                } catch (Exception failure) {
                    throw new AssertionError("Listener blocked inventory access", failure);
                }
            }));
            reference.get().registerProduct("sku", ProductCategory.STANDARD);
            reference.get().addStock("sku", 5);
        }
    }

    @Test
    void concurrentOrdersCannotOversell() throws Exception {
        stock(ProductCategory.STANDARD, 50);
        List<Integer> outcomes = concurrently(100, index -> {
            try {
                service.reserve("order-" + index, "sku", 1);
                service.confirm("order-" + index);
                return 1;
            } catch (InsufficientStockException expected) { return 0; }
        });
        assertEquals(50, outcomes.stream().mapToInt(Integer::intValue).sum());
        assertEquals(0, service.available("sku"));
        assertEquals(1, alerts.size());
        clock.advance(Duration.ofDays(1));
        assertEquals(0, service.available("sku"));
    }

    @Test
    void concurrentDuplicateOrdersOnlyReserveOnce() throws Exception {
        stock(ProductCategory.STANDARD, 10);
        var results = concurrently(100, index -> service.reserve("same", "sku", 5));
        assertTrue(results.stream().allMatch(results.getFirst()::equals));
        assertEquals(5, service.available("sku"));
        assertEquals(List.of(5), alerts);
    }

    private <T> List<T> concurrently(int count, java.util.function.IntFunction<T> action) throws Exception {
        try (var executor = Executors.newFixedThreadPool(12)) {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                int index = i;
                futures.add(executor.submit(() -> { start.await(); return action.apply(index); }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (var future : futures) results.add(future.get(5, TimeUnit.SECONDS));
            return results;
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");
        void advance(Duration duration) { now = now.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        @Override public Instant instant() { return now; }
    }
}
