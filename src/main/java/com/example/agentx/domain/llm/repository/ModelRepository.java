package com.example.agentx.domain.llm.repository;

import com.example.agentx.domain.llm.model.ModelEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 模型仓储接口
 */
@Mapper
public interface ModelRepository extends MyBatisPlusExtRepository<ModelEntity> {


}
