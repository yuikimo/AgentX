package com.example.agentx.application.conversation.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;
import com.example.agentx.application.conversation.assembler.MessageAssembler;
import com.example.agentx.application.conversation.dto.MessageDTO;
import com.example.agentx.domain.conversation.model.MessageEntity;
import com.example.agentx.domain.conversation.model.SessionEntity;
import com.example.agentx.domain.conversation.service.ConversationDomainService;
import com.example.agentx.domain.conversation.service.SessionDomainService;
import com.example.agentx.infrastructure.exception.BusinessException;
import com.example.agentx.interfaces.dto.conversation.QueryConversationMessageRequest;

import java.util.List;

@Service
public class ConversationMessageQueryAppService {
    private final ConversationDomainService conversationDomainService;
    private final SessionDomainService sessionDomainService;

    public ConversationMessageQueryAppService(ConversationDomainService conversationDomainService,
            SessionDomainService sessionDomainService) {
        this.conversationDomainService = conversationDomainService;
        this.sessionDomainService = sessionDomainService;
    }

    public List<MessageDTO> getConversationMessages(String sessionId, String userId) {
        SessionEntity sessionEntity = sessionDomainService.find(sessionId, userId);
        if (sessionEntity == null) {
            throw new BusinessException("会话不存在");
        }
        List<MessageEntity> conversationMessages = conversationDomainService.getConversationMessages(sessionId);
        return MessageAssembler.toDTOs(conversationMessages);
    }

    public Page<MessageDTO> getConversationMessagesPage(String sessionId, String userId,
            QueryConversationMessageRequest request) {
        SessionEntity sessionEntity = sessionDomainService.find(sessionId, userId);
        if (sessionEntity == null) {
            throw new BusinessException("会话不存在");
        }

        int pageNo = request.getPage() != null ? Math.max(1, request.getPage()) : 1;
        int pageSize = request.getPageSize() != null ? Math.max(1, request.getPageSize()) : 30;

        Page<MessageEntity> page = conversationDomainService.pageConversationMessages(sessionId, pageNo, pageSize);
        Page<MessageDTO> dtoPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        dtoPage.setRecords(MessageAssembler.toDTOs(page.getRecords()));
        return dtoPage;
    }
}
