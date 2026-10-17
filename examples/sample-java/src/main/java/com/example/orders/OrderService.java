package com.example.orders;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Order use cases. */
@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final OrderEventPublisher publisher;
    private final Map<String, Order> cache = new ConcurrentHashMap<>();

    public OrderService(OrderRepository orderRepository, OrderEventPublisher publisher) {
        this.orderRepository = orderRepository;
        this.publisher = publisher;
    }

    public List<Order> listOrders() {
        List<Order> orders = new ArrayList<>();
        for (OrderEntity entity : orderRepository.findAll()) {
            Order order = toOrder(entity);
            if (order.status() == OrderStatus.CANCELLED) {
                continue;
            }
            orders.add(order);
        }
        return orders;
    }

    public Order findOrder(String id) {
        return cache.computeIfAbsent(id, this::loadFromStore);
    }

    @Transactional
    public Order pay(String id) {
        Order order = findOrder(id);
        if (order.status() != OrderStatus.NEW && order.status() != OrderStatus.RESERVED) {
            throw new IllegalStateException("Order " + id + " cannot be paid in state " + order.status());
        }
        Order paid = order.withStatus(OrderStatus.PAID);
        cache.put(id, paid);
        publisher.orderPaid(paid);
        return paid;
    }

    private Order loadFromStore(String id) {
        return orderRepository.findById(id)
                .map(entity -> toOrder(entity).withStatus(OrderStatus.RESERVED))
                .orElseThrow(() -> new IllegalArgumentException("Unknown order " + id));
    }

    private static Order toOrder(OrderEntity entity) {
        return new Order(entity.getId(), entity.getCustomerId(), entity.getTotal(),
                entity.getStatus(), entity.getPlacedAt());
    }
}
