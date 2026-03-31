package com.example.agentx.domain.product.repository;

import org.apache.ibatis.annotations.Mapper;
import com.example.agentx.domain.product.model.ProductEntity;
import com.example.agentx.infrastructure.repository.MyBatisPlusExtRepository;

/**
 * 商品仓储接口
 */
@Mapper
public interface ProductRepository extends MyBatisPlusExtRepository<ProductEntity> {
}