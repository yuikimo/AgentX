package com.example.agentx.interfaces.api;

import com.example.agentx.application.chat.service.ChatService;
import com.example.agentx.application.conversation.dto.ChatRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/chat")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping("/stream")
    public SseEmitter streamChat(@RequestBody ChatRequest request) {
        return chatService.streamChat(request.getMessage());
    }
}

