package com.example.agentx.infrastructure.llm;

import com.example.agentx.infrastructure.llm.config.ProviderConfig;
import com.example.agentx.infrastructure.llm.factory.LLMProviderFactory;
import com.example.agentx.infrastructure.llm.protocol.enums.ProviderProtocol;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;

public class LLMProviderService {

    public static ChatLanguageModel getNormal(ProviderProtocol protocol, ProviderConfig providerConfig){
        return LLMProviderFactory.getLLMProvider(protocol, providerConfig);
    }

    public static StreamingChatLanguageModel getStream(ProviderProtocol protocol, ProviderConfig providerConfig){
        return LLMProviderFactory.getLLMProviderByStream(protocol, providerConfig);
    }
}
