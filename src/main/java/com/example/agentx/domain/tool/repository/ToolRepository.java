package com.example.agentx.domain.tool.repository;

import com.example.agentx.domain.tool.model.ToolEntity;
import com.example.agentx.infrastructure.repository.MyBatisPlusExtRepository;
import org.apache.ibatis.annotations.Mapper;

/** 工具仓储接口 */
@Mapper
public interface ToolRepository extends MyBatisPlusExtRepository<ToolEntity> {
}