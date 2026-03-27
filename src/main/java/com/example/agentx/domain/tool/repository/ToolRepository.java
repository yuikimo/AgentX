package com.example.agentx.domain.tool.repository;

import org.apache.ibatis.annotations.Mapper;
import com.example.agentx.domain.tool.model.ToolEntity;
import com.example.agentx.infrastructure.repository.MyBatisPlusExtRepository;

/**
 * 工具仓储接口
 */
@Mapper
public interface ToolRepository extends MyBatisPlusExtRepository<ToolEntity> {
}