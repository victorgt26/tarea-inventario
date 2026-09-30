package com.store.inventory.domain;

import com.store.inventory.api.Reservation;

/**
 * Reservation data plus lifecycle state; the API value is reused, not duplicated.
 */
public record OrderReservation(Reservation details, ReservationStatus status) {
}
