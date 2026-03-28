package com.example.agentx.domain.rag.repository;

import org.apache.ibatis.annotations.Mapper;
import com.example.agentx.domain.rag.model.RagVersionFileEntity;
import com.example.agentx.infrastructure.repository.MyBatisPlusExtRepository;

/**
 * RAG版本文件仓储接口
 */
@Mapper
public interface RagVersionFileRepository extends MyBatisPlusExtRepository<RagVersionFileEntity> {

}