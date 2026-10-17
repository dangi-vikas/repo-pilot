package com.example.orders;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/** Publishes order lifecycle events. */
@Component
public class OrderEventPublisher {

    private static final String PAID_TOPIC = "order.paid";

    private final KafkaTemplate<String, Order> kafkaTemplate;

    public OrderEventPublisher(KafkaTemplate<String, Order> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void orderPaid(Order order) {
        kafkaTemplate.send(PAID_TOPIC, order.id(), order);
    }
}
