package com.example.agentx.domain.order.repository;

import org.apache.ibatis.annotations.Mapper;
import com.example.agentx.domain.order.model.OrderEntity;
import com.example.agentx.infrastructure.repository.MyBatisPlusExtRepository;

/** 订单仓储接口 */
@Mapper
public interface OrderRepository extends MyBatisPlusExtRepository<OrderEntity> {
}