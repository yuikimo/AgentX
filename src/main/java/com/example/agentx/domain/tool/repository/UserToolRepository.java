package com.example.agentx.domain.tool.repository;

import com.example.agentx.domain.tool.model.UserToolEntity;
import com.example.agentx.infrastructure.repository.MyBatisPlusExtRepository;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserToolRepository extends MyBatisPlusExtRepository<UserToolEntity> {
}
