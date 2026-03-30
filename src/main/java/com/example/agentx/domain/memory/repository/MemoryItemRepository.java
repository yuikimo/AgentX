package com.example.agentx.domain.memory.repository;

import org.apache.ibatis.annotations.Mapper;
import com.example.agentx.domain.memory.model.MemoryItemEntity;
import com.example.agentx.infrastructure.repository.MyBatisPlusExtRepository;

/**
 * memory_items 表数据访问（基于 MyBatis-Plus 提供通用 CRUD）
 */
@Mapper
public interface MemoryItemRepository extends MyBatisPlusExtRepository<MemoryItemEntity> {
}
