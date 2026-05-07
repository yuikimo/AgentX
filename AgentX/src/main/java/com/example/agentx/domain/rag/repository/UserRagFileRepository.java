package com.example.agentx.domain.rag.repository;

import org.apache.ibatis.annotations.Mapper;
import com.example.agentx.domain.rag.model.UserRagFileEntity;
import com.example.agentx.infrastructure.repository.MyBatisPlusExtRepository;

/** 用户RAG文件快照仓储接口
 * @author xhy
 * @date 2025-07-22 <br/>
 */
@Mapper
public interface UserRagFileRepository extends MyBatisPlusExtRepository<UserRagFileEntity> {

}