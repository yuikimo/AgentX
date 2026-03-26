package com.example.agentx.domain.rag.straegy;

import com.example.agentx.domain.rag.message.RagDocSyncOcrMessage;

public interface RagDocSyncOcrStrategy {

    /**
     * 处理
     *
     * @param ragDocSyncOcrMessage mq消息
     * @param strategy             策略
     */
    void handle(RagDocSyncOcrMessage ragDocSyncOcrMessage, String strategy) throws Exception;
}

