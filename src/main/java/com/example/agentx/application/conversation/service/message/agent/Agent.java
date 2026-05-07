package com.example.agentx.application.conversation.service.message.agent;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

public interface Agent {
    @SystemMessage("{{systemPrompt}}")
    @UserMessage("{{userPrompt}}")
    AiMessage chat(@V("systemPrompt") String systemPrompt, @V("userPrompt") String userPrompt);
}
