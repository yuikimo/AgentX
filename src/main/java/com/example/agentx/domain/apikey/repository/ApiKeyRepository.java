package com.example.agentx.domain.apikey.repository;

import org.apache.ibatis.annotations.Mapper;
import com.example.agentx.domain.apikey.model.ApiKeyEntity;
import com.example.agentx.infrastructure.repository.MyBatisPlusExtRepository;

/**
 * API密钥仓储接口
 */
@Mapper
public interface ApiKeyRepository extends MyBatisPlusExtRepository<ApiKeyEntity> {
}