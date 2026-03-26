package com.example.agentx.domain.rag.repository;

import com.example.agentx.domain.rag.model.UserRagFileEntity;
import com.example.agentx.infrastructure.repository.MyBatisPlusExtRepository;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户RAG文件快照仓储接口
 */
@Mapper
public interface UserRagFileRepository extends MyBatisPlusExtRepository<UserRagFileEntity> {

}