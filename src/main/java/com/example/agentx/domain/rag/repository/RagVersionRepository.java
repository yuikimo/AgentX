package com.example.agentx.domain.rag.repository;

import com.example.agentx.domain.rag.model.RagVersionEntity;
import com.example.agentx.infrastructure.repository.MyBatisPlusExtRepository;
import org.apache.ibatis.annotations.Mapper;

/**
 * RAG版本仓储接口
 */
@Mapper
public interface RagVersionRepository extends MyBatisPlusExtRepository<RagVersionEntity> {

}