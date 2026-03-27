package com.example.agentx.application.conversation.service.message.agent;

import dev.langchain4j.data.message.AiMessage;

public interface Agent {
    AiMessage chat(String prompt);
}
