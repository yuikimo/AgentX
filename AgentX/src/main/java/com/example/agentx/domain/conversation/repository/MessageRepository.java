package com.example.agentx.domain.conversation.repository;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import com.example.agentx.domain.conversation.model.MessageEntity;
import com.example.agentx.infrastructure.repository.MyBatisPlusExtRepository;

import java.util.List;

/** 消息仓库接口 */
@Mapper
public interface MessageRepository extends MyBatisPlusExtRepository<MessageEntity> {

    @Update({
            "<script>",
            "UPDATE messages",
            "SET body_token_count = CASE id",
            "<foreach collection='messages' item='message'>",
            "WHEN #{message.id} THEN #{message.bodyTokenCount}",
            "</foreach>",
            "END,",
            "token_count = CASE id",
            "<foreach collection='messages' item='message'>",
            "WHEN #{message.id} THEN #{message.tokenCount}",
            "</foreach>",
            "END",
            "WHERE id IN",
            "<foreach collection='messages' item='message' open='(' separator=',' close=')'>",
            "#{message.id}",
            "</foreach>",
            "</script>"
    })
    int batchUpdateTokenCounts(@Param("messages") List<MessageEntity> messages);
}
