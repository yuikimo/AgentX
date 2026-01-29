package com.example.agentx.domain.conversation.handler;

/**
 * 消息处理器接口
 */
public interface MessageHandler {

    /**
     * 处理对话
     *
     * @param environment 对话环境
     * @param transport   消息传输实现
     * @param <T>         连接类型
     * @return 连接对象
     */
    <T> T handleChat(ChatEnvironment environment, MessageTransport<T> transport);
}
