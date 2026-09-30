package com.store.inventory.application.usecase;

import com.store.inventory.api.InsufficientStockException;
import com.store.inventory.api.OrderLimitExceededException;
import com.store.inventory.api.ProductCategory;
import com.store.inventory.api.Reservation;
import com.store.inventory.application.port.in.InventoryUseCase;
import com.store.inventory.application.port.out.InventoryPort;
import com.store.inventory.application.port.out.StockAlertPort;
import com.store.inventory.domain.InventoryState;
import com.store.inventory.domain.LowStock;
import com.store.inventory.domain.OrderReservation;
import com.store.inventory.domain.ReservationPolicy;
import com.store.inventory.domain.ReservationStatus;
import com.store.inventory.domain.StockProduct;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Business rules and orchestration for the inventory use cases.
 */
public final class InventoryUseCases implements InventoryUseCase {
    private static final int LOW_STOCK_THRESHOLD = 5;
    private static final System.Logger LOG = System.getLogger(InventoryUseCases.class.getName());
    private final Clock clock;
    private final StockAlertPort alerts;
    private final InventoryPort inventoryPort;
    private final Map<ProductCategory, ReservationPolicy> policies;

    private record ReservationResult(Reservation reservation, LowStock alert) {
    }

    public InventoryUseCases(Clock clock, StockAlertPort alerts, InventoryPort inventoryPort, Map<ProductCategory, ReservationPolicy> policies) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.alerts = Objects.requireNonNull(alerts, "alerts");
        this.inventoryPort = Objects.requireNonNull(inventoryPort, "inventoryPort");
        this.policies = Map.copyOf(policies);
        for (ProductCategory category : ProductCategory.values()) {
            var policy = this.policies.get(category);
            if (policy == null || policy.lifetime() == null || policy.lifetime().isZero()
                    || policy.lifetime().isNegative() || policy.maximumQuantity() <= 0) {
                throw new IllegalArgumentException("Missing or invalid policy: " + category);
            }
        }
    }

    @Override
    public void registerProduct(String sku, ProductCategory category) {
        identifier(sku, "sku");
        Objects.requireNonNull(category, "category");
        inventoryPort.execute(state -> {
            var existing = state.getProducts().get(sku);
            if (existing != null && existing.category() != category) {
                throw new IllegalArgumentException("Cannot change category for " + sku);
            }
            state.getProducts().putIfAbsent(sku, new StockProduct(category, 0, 0, false));
            return null;
        });
    }

    @Override
    public void addStock(String sku, int quantity) {
        identifier(sku, "sku");
        positive(quantity);
        LowStock alert = inventoryPort.execute(state -> {
            expire(state, clock.instant());
            var product = state.getProducts().get(sku);
            if (product == null) throw new IllegalArgumentException("Unknown product: " + sku);
            if ((long) product.onHand() + quantity > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Stock exceeds supported capacity");
            }
            var updated = new StockProduct(product.category(), product.available() + quantity,
                    product.onHand() + quantity, false);
            return updateStock(state, sku, updated);
        });
        notifyLowStock(alert);
    }

    @Override
    public Reservation reserve(String orderId, String sku, int quantity) {
        identifier(orderId, "orderId");
        identifier(sku, "sku");
        positive(quantity);
        ReservationResult result = inventoryPort.execute(state -> {
            Instant now = clock.instant();
            expire(state, now);
            var existing = state.getOrders().get(orderId);
            if (existing != null) {
                if (!existing.details().sku().equals(sku) || existing.details().quantity() != quantity) {
                    throw new IllegalArgumentException("Order ID already used with different parameters: " + orderId);
                }
                if (existing.status() == ReservationStatus.EXPIRED) {
                    throw new IllegalStateException("Order expired: " + orderId);
                }
                return new ReservationResult(existing.details(), null);
            }
            var product = state.getProducts().get(sku);
            if (product == null) throw new InsufficientStockException(sku, quantity, 0);
            var policy = policies.get(product.category());
            if (quantity > policy.maximumQuantity()) {
                throw new OrderLimitExceededException(sku, quantity, policy.maximumQuantity());
            }
            if (quantity > product.available()) {
                throw new InsufficientStockException(sku, quantity, product.available());
            }
            var reservation = new Reservation(orderId, sku, quantity, now.plus(policy.lifetime()));
            var updated = new StockProduct(product.category(), product.available() - quantity,
                    product.onHand(), product.alerted());
            LowStock alert = updateStock(state, sku, updated);
            state.getOrders().put(orderId, new OrderReservation(reservation, ReservationStatus.ACTIVE));
            return new ReservationResult(reservation, alert);
        });
        notifyLowStock(result.alert());
        return result.reservation();
    }

    @Override
    public void confirm(String orderId) {
        identifier(orderId, "orderId");
        inventoryPort.execute(state -> {
            expire(state, clock.instant());
            var order = state.getOrders().get(orderId);
            if (order == null || order.status() == ReservationStatus.EXPIRED) {
                throw new IllegalStateException("Order has no active reservation: " + orderId);
            }
            if (order.status() == ReservationStatus.CONFIRMED) return null;
            state.getProducts().computeIfPresent(order.details().sku(), (k, product) -> new StockProduct(product.category(),
                    product.available(), product.onHand() - order.details().quantity(), product.alerted()));
            state.getOrders().put(orderId, new OrderReservation(order.details(), ReservationStatus.CONFIRMED));
            return null;
        });
    }

    @Override
    public int available(String sku) {
        identifier(sku, "sku");
        return inventoryPort.execute(state -> {
            expire(state, clock.instant());
            var product = state.getProducts().get(sku);
            return product == null ? 0 : product.available();
        });
    }

    private void expire(InventoryState state, Instant now) {
        for (var entry : state.getOrders().entrySet()) {
            var order = entry.getValue();
            if (order.status() != ReservationStatus.ACTIVE || now.isBefore(order.details().expiresAt())) continue;
            state.getProducts().computeIfPresent(order.details().sku(), (k, product) -> new StockProduct(product.category(),
                    product.available() + order.details().quantity(), product.onHand(), product.alerted()));
            entry.setValue(new OrderReservation(order.details(), ReservationStatus.EXPIRED));
        }
    }

    private LowStock updateStock(InventoryState state, String sku, StockProduct product) {
        boolean notify = !product.alerted() && product.available() <= LOW_STOCK_THRESHOLD;
        state.getProducts().put(sku, new StockProduct(product.category(), product.available(),
                product.onHand(), product.alerted() || notify));
        return notify ? new LowStock(sku, product.available()) : null;
    }

    private void notifyLowStock(LowStock alert) {
        if (alert == null) return;
        // No external I/O under the inventory lock; failure does not undo the reservation.
        try {
            alerts.onLowStock(alert.sku(), alert.availableUnits());
        } catch (RuntimeException failure) {
            LOG.log(System.Logger.Level.WARNING, "Low stock alert delivery failed for " + alert.sku(), failure);
        }
    }

    private static void identifier(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
    }

    private static void positive(int quantity) {
        if (quantity <= 0) throw new IllegalArgumentException("Quantity must be positive");
    }
}
