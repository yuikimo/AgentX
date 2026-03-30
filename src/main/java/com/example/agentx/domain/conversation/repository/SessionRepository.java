package com.example.agentx.domain.conversation.repository;

import org.apache.ibatis.annotations.Mapper;
import com.example.agentx.domain.conversation.model.SessionEntity;
import com.example.agentx.infrastructure.repository.MyBatisPlusExtRepository;

/**
 * 会话仓库接口
 */
@Mapper
public interface SessionRepository extends MyBatisPlusExtRepository<SessionEntity> {
}