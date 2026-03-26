package com.example.agentx.domain.order.repository;

import com.example.agentx.domain.order.model.OrderEntity;
import com.example.agentx.infrastructure.repository.MyBatisPlusExtRepository;
import org.apache.ibatis.annotations.Mapper;

/**
 * 订单仓储接口
 */
@Mapper
public interface OrderRepository extends MyBatisPlusExtRepository<OrderEntity> {
}