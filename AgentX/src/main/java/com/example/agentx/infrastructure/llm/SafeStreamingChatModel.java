package com.example.agentx.infrastructure.llm;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;

/**
 * LangChain4j 的 AiServices 在流式完成回调中假设 ChatResponse 不为空。
 * 部分 OpenAI 兼容接口异常断流或只发送 [DONE] 时可能传入 null，导致内部 NPE 并绕过业务 onError。
 */
public class SafeStreamingChatModel implements StreamingChatModel {

    private static final Logger logger = LoggerFactory.getLogger(SafeStreamingChatModel.class);

    private final StreamingChatModel delegate;

    public SafeStreamingChatModel(StreamingChatModel delegate) {
        this.delegate = delegate;
    }

    public static StreamingChatModel wrap(StreamingChatModel model) {
        if (model == null || model instanceof SafeStreamingChatModel) {
            return model;
        }
        return new SafeStreamingChatModel(model);
    }

    @Override
    public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
        delegate.chat(request, new NullSafeStreamingChatResponseHandler(handler));
    }

    @Override
    public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
        delegate.doChat(request, new NullSafeStreamingChatResponseHandler(handler));
    }

    @Override
    public ChatRequestParameters defaultRequestParameters() {
        return delegate.defaultRequestParameters();
    }

    @Override
    public List<ChatModelListener> listeners() {
        return delegate.listeners();
    }

    @Override
    public ModelProvider provider() {
        return delegate.provider();
    }

    @Override
    public Set<Capability> supportedCapabilities() {
        return delegate.supportedCapabilities();
    }

    private static final class NullSafeStreamingChatResponseHandler implements StreamingChatResponseHandler {

        private final StreamingChatResponseHandler delegate;
        private final StringBuilder partialText = new StringBuilder();

        private NullSafeStreamingChatResponseHandler(StreamingChatResponseHandler delegate) {
            this.delegate = delegate;
        }

        @Override
        public void onPartialResponse(String partialResponse) {
            if (partialResponse != null) {
                partialText.append(partialResponse);
            }
            delegate.onPartialResponse(partialResponse);
        }

        @Override
        public void onPartialReasoning(String partialReasoning) {
            delegate.onPartialReasoning(partialReasoning);
        }

        @Override
        public void onCompleteResponse(ChatResponse completeResponse) {
            delegate.onCompleteResponse(resolveCompleteResponse(completeResponse));
        }

        @Override
        public void onCompleteReasoning(String completeReasoning) {
            delegate.onCompleteReasoning(completeReasoning);
        }

        @Override
        public void onRawData(Object rawData) {
            delegate.onRawData(rawData);
        }

        @Override
        public void onError(Throwable error) {
            delegate.onError(error);
        }

        private ChatResponse resolveCompleteResponse(ChatResponse completeResponse) {
            if (completeResponse != null && completeResponse.aiMessage() != null
                    && completeResponse.metadata() != null) {
                return completeResponse;
            }
            String fallbackText = partialText.toString();
            logger.warn("流式模型返回空完成响应，已使用已接收片段兜底: partialLength={}", fallbackText.length());

            ChatResponseMetadata metadata = completeResponse != null && completeResponse.metadata() != null
                    ? completeResponse.metadata()
                    : ChatResponseMetadata.builder().tokenUsage(new TokenUsage()).build();
            AiMessage aiMessage = completeResponse != null && completeResponse.aiMessage() != null
                    ? completeResponse.aiMessage()
                    : AiMessage.from(fallbackText);
            return ChatResponse.builder().aiMessage(aiMessage).metadata(metadata).build();
        }
    }
}
