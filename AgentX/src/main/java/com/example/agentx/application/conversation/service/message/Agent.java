package com.example.agentx.application.conversation.service.message;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.service.TokenStream;

public interface Agent {
    TokenStream chat(UserMessage message);
}
