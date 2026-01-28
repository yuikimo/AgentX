package com.example.agentx.domain.llm.repository;

import com.example.agentx.domain.llm.model.ProviderEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 服务提供商仓储接口
 */
@Mapper
public interface ProviderRepository extends MyBatisPlusExtRepository<ProviderEntity> {

}
