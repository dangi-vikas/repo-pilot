package com.example.orders;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * An order placed by a customer.
 *
 * <p>Immutable value carrier: every field is set at construction and the type is
 * shared across the API, service and persistence layers.
 */
public record Order(
        String id,
        String customerId,
        BigDecimal total,
        OrderStatus status,
        Instant placedAt) {

    public Order withStatus(OrderStatus next) {
        return new Order(id, customerId, total, next, placedAt);
    }

    public boolean isSettled() {
        return status == OrderStatus.PAID || status == OrderStatus.SHIPPED;
    }
}
