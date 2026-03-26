package com.example.agentx.domain.rag.repository;

import com.example.agentx.domain.rag.model.UserRagEntity;
import com.example.agentx.infrastructure.repository.MyBatisPlusExtRepository;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户安装的RAG仓储接口
 */
@Mapper
public interface UserRagRepository extends MyBatisPlusExtRepository<UserRagEntity> {

}