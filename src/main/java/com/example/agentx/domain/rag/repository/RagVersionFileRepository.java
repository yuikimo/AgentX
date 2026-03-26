package com.example.agentx.domain.rag.repository;

import com.example.agentx.domain.rag.model.RagVersionFileEntity;
import com.example.agentx.infrastructure.repository.MyBatisPlusExtRepository;
import org.apache.ibatis.annotations.Mapper;

/**
 * RAG版本文件仓储接口
 */
@Mapper
public interface RagVersionFileRepository extends MyBatisPlusExtRepository<RagVersionFileEntity> {

}