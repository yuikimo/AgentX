package com.example.agentx.domain.agent.repository;

import org.apache.ibatis.annotations.Mapper;
import com.example.agentx.domain.agent.model.AgentEntity;
import com.example.agentx.infrastructure.repository.MyBatisPlusExtRepository;

/**
 * Agent仓库接口
 */
@Mapper
public interface AgentRepository extends MyBatisPlusExtRepository<AgentEntity> {
}