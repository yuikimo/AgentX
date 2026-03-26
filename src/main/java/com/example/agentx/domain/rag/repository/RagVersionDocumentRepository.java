package com.example.agentx.domain.rag.repository;

import com.example.agentx.domain.rag.model.RagVersionDocumentEntity;
import com.example.agentx.infrastructure.repository.MyBatisPlusExtRepository;
import org.apache.ibatis.annotations.Mapper;

/**
 * RAG版本文档单元仓储接口
 */
@Mapper
public interface RagVersionDocumentRepository extends MyBatisPlusExtRepository<RagVersionDocumentEntity> {

}