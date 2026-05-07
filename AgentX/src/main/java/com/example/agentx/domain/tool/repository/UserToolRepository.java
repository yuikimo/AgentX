package com.example.agentx.domain.tool.repository;

import org.apache.ibatis.annotations.Mapper;
import com.example.agentx.domain.tool.model.ToolVersionEntity;
import com.example.agentx.domain.tool.model.UserToolEntity;
import com.example.agentx.infrastructure.repository.MyBatisPlusExtRepository;

@Mapper
public interface UserToolRepository extends MyBatisPlusExtRepository<UserToolEntity> {
}
