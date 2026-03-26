package com.example.agentx.domain.trace.repository;

import com.example.agentx.domain.trace.model.AgentExecutionDetailEntity;
import com.example.agentx.infrastructure.repository.MyBatisPlusExtRepository;
import org.apache.ibatis.annotations.Mapper;

/**
 * Agent执行链路详细记录仓库接口
 */
@Mapper
public interface AgentExecutionDetailRepository extends MyBatisPlusExtRepository<AgentExecutionDetailEntity> {
}