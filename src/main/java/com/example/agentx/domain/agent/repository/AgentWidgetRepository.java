package com.example.agentx.domain.agent.repository;

import org.apache.ibatis.annotations.Mapper;
import com.example.agentx.domain.agent.model.AgentWidgetEntity;
import com.example.agentx.infrastructure.repository.MyBatisPlusExtRepository;

/**
 * Agent小组件配置仓储接口
 */
@Mapper
public interface AgentWidgetRepository extends MyBatisPlusExtRepository<AgentWidgetEntity> {

}