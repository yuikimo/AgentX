package com.example.agentx.domain.rag.repository;

import org.apache.ibatis.annotations.Mapper;
import com.example.agentx.domain.rag.model.RagVersionDocumentEntity;
import com.example.agentx.infrastructure.repository.MyBatisPlusExtRepository;

/** RAG版本文档单元仓储接口
 * @author xhy
 * @date 2025-07-16 <br/>
 */
@Mapper
public interface RagVersionDocumentRepository extends MyBatisPlusExtRepository<RagVersionDocumentEntity> {

}