package com.example.orders;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Consumes order events. */
@Component
public class OrderEventListener {

    private final OrderService orderService;

    public OrderEventListener(OrderService orderService) {
        this.orderService = orderService;
    }

    @KafkaListener(topics = "order.paid", groupId = "order-fulfilment")
    public void onOrderPaid(String orderId) {
        orderService.findOrder(orderId);
    }
}
