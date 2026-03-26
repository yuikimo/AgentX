package com.example.agentx.domain.trace.repository;

import com.example.agentx.domain.trace.model.AgentExecutionSummaryEntity;
import com.example.agentx.infrastructure.repository.MyBatisPlusExtRepository;
import org.apache.ibatis.annotations.Mapper;

/**
 * Agent执行链路汇总仓库接口
 */
@Mapper
public interface AgentExecutionSummaryRepository extends MyBatisPlusExtRepository<AgentExecutionSummaryEntity> {
}