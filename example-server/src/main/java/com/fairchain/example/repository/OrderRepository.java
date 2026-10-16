package com.fairchain.example.repository;

import com.fairchain.example.entity.Order;
import org.springframework.data.jpa.repository.JpaRepository;

/** 주문 저장소 (보완) */
public interface OrderRepository extends JpaRepository<Order, Long> {
}
