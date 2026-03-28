package com.example.agentx.domain.rag.repository;

import org.apache.ibatis.annotations.Mapper;
import com.example.agentx.domain.rag.model.RagVersionEntity;
import com.example.agentx.infrastructure.repository.MyBatisPlusExtRepository;

/**
 * RAG版本仓储接口
 */
@Mapper
public interface RagVersionRepository extends MyBatisPlusExtRepository<RagVersionEntity> {

}