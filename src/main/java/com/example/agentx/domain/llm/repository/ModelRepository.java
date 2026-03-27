package com.example.agentx.domain.llm.repository;

import org.apache.ibatis.annotations.Mapper;
import com.example.agentx.domain.llm.model.ModelEntity;
import com.example.agentx.infrastructure.repository.MyBatisPlusExtRepository;

/**
 * 模型仓储接口
 */
@Mapper
public interface ModelRepository extends MyBatisPlusExtRepository<ModelEntity> {

}