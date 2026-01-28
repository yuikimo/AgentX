package com.example.agentx.infrastructure.llm;

import com.example.agentx.infrastructure.llm.factory.LLMProviderFactory;
import com.example.agentx.infrastructure.llm.protocol.enums.ProviderProtocol;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import org.springframework.stereotype.Service;

@Service
public class LLMProviderService {


    public ChatLanguageModel getNormal(ProviderProtocol protocol, ProviderConfig providerConfig){
        return LLMProviderFactory.getLLMProvider(protocol, providerConfig);
    }


    public StreamingChatLanguageModel getStream(ProviderProtocol protocol, ProviderConfig providerConfig){
        return LLMProviderFactory.getLLMProviderByStream(protocol, providerConfig);
    }
}
