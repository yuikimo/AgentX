package com.example.agentx.domain.agent.repository;

import org.apache.ibatis.annotations.Mapper;

/**
 * Agent仓库接口
 */
@Mapper
public interface AgentRepository extends MyBatisPlusExtRepository<AgentEntity> {
}
