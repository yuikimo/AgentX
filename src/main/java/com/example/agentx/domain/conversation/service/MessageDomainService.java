package com.example.agentx.domain.conversation.service;

import com.example.agentx.domain.conversation.model.MessageEntity;
import com.example.agentx.domain.conversation.repository.MessageRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class MessageDomainService {

    private final MessageRepository messageRepository;

    public MessageDomainService(MessageRepository messageRepository) {
        this.messageRepository = messageRepository;
    }

    public List<MessageEntity> listByIds(List<String> ids) {
        return messageRepository.selectByIds(ids);
    }
}
