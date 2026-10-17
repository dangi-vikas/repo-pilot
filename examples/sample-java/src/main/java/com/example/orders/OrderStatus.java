package com.example.orders;

/** Lifecycle states of an {@link Order}. */
public enum OrderStatus {
    NEW,
    RESERVED,
    PAID,
    SHIPPED,
    CANCELLED
}
