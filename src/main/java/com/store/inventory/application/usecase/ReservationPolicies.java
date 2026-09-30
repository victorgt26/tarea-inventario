package com.store.inventory.application.usecase;

import com.store.inventory.api.ProductCategory;
import com.store.inventory.domain.ReservationPolicy;

import java.time.Duration;
import java.util.Map;

/**
 * Business policy configuration, kept outside data-only domain models.
 */
public final class ReservationPolicies {
    private ReservationPolicies() {
    }

    public static Map<ProductCategory, ReservationPolicy> defaults() {
        return Map.of(
                ProductCategory.STANDARD, new ReservationPolicy(Duration.ofMinutes(15), Integer.MAX_VALUE),
                ProductCategory.PRE_ORDER, new ReservationPolicy(Duration.ofHours(24), Integer.MAX_VALUE),
                ProductCategory.FLASH_SALE, new ReservationPolicy(Duration.ofMinutes(5), 2));
    }
}
