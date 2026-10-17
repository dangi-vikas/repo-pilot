package com.example.orders;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** Spring Data repository for {@link Order} (stored as {@link OrderEntity}). */
public interface OrderRepository extends JpaRepository<OrderEntity, String> {

    List<OrderEntity> findByCustomerId(String customerId);

    List<OrderEntity> findByStatus(OrderStatus status);
}
