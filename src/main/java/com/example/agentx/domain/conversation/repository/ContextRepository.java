package com.example.agentx.domain.conversation.repository;

import com.example.agentx.domain.conversation.model.ContextEntity;
import com.example.agentx.infrastructure.repository.MyBatisPlusExtRepository;
import org.apache.ibatis.annotations.Mapper;

/**
 * 上下文仓库接口
 */
@Mapper
public interface ContextRepository extends MyBatisPlusExtRepository<ContextEntity> {
}
