package com.example.agentx.infrastructure.llm;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import com.example.agentx.infrastructure.llm.config.ProviderConfig;
import com.example.agentx.infrastructure.llm.factory.LLMProviderFactory;
import com.example.agentx.infrastructure.llm.protocol.enums.ProviderProtocol;

public class LLMProviderService {

    public static ChatModel getStrand(ProviderProtocol protocol, ProviderConfig providerConfig) {
        return LLMProviderFactory.getLLMProvider(protocol, providerConfig);
    }

    public static StreamingChatModel getStream(ProviderProtocol protocol, ProviderConfig providerConfig) {
        return LLMProviderFactory.getLLMProviderByStream(protocol, providerConfig);
    }
}
