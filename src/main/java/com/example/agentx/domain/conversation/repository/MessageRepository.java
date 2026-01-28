package com.example.agentx.domain.conversation.repository;

import com.example.agentx.domain.conversation.model.MessageEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 消息仓库接口
 */
@Mapper
public interface MessageRepository extends MyBatisPlusExtRepository<MessageEntity> {
}
