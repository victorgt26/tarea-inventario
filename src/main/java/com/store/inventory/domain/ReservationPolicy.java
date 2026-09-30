package com.store.inventory.domain;

import java.time.Duration;

public record ReservationPolicy(Duration lifetime, int maximumQuantity) {
}
