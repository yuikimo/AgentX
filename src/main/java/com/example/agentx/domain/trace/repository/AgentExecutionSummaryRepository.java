package com.example.agentx.domain.trace.repository;

import org.apache.ibatis.annotations.Mapper;
import com.example.agentx.domain.trace.model.AgentExecutionSummaryEntity;
import com.example.agentx.infrastructure.repository.MyBatisPlusExtRepository;

/** Agent执行链路汇总仓库接口 */
@Mapper
public interface AgentExecutionSummaryRepository extends MyBatisPlusExtRepository<AgentExecutionSummaryEntity> {
}