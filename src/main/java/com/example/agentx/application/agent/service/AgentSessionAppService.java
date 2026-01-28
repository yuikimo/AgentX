package com.example.agentx.application.agent.service;

import com.example.agentx.application.agent.dto.AgentDTO;
import com.example.agentx.domain.agent.service.AgentDomainService;
import com.example.agentx.domain.agent.service.AgentWorkspaceDomainService;
import com.example.agentx.domain.conversation.service.ConversationDomainService;
import com.example.agentx.domain.conversation.service.SessionDomainService;
import com.example.agentx.infrastructure.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;

@Service
public class AgentSessionAppService {

    private final AgentWorkspaceDomainService agentWorkspaceDomainService;

    private final AgentDomainService agentServiceDomainService;

    private final SessionDomainService sessionDomainService;

    private final ConversationDomainService conversationDomainService;

    public AgentSessionAppService(AgentWorkspaceDomainService agentWorkspaceDomainService,
                                  AgentDomainService agentServiceDomainService,
                                  SessionDomainService sessionDomainService,
                                  ConversationDomainService conversationDomainService) {
        this.agentWorkspaceDomainService = agentWorkspaceDomainService;
        this.agentServiceDomainService = agentServiceDomainService;
        this.sessionDomainService = sessionDomainService;
        this.conversationDomainService = conversationDomainService;
    }

    /**
     * 获取助理下的会话列表
     *
     * @param userId  用户id
     * @param agentId 助理id
     * @return 会话列表
     */
    public List<SessionDTO> getAgentSessionList(String userId, String agentId) {

        // 校验该 agent 是否被添加了工作区，判断条件：是否是自己的助理 or 在工作区中
        boolean b = agentServiceDomainService.checkAgentExist(agentId, userId);
        boolean b1 = agentWorkspaceDomainService.checkAgentWorkspaceExist(agentId, userId);

        if (!b && !b1) {
            throw new BusinessException("助理不存在");
        }

        // 获取对应的会话列表
        List<SessionDTO> sessions = sessionDomainService.getSessionsByAgentId(agentId);
        if (sessions.isEmpty()) {
            // 如果会话列表为空，则新创建一个并且返回
            SessionDTO session = sessionDomainService.createSession(agentId, userId);
            return Collections.singletonList(session);
        }

        return sessions;
    }

    /**
     * 创建会话
     *
     * @param userId  用户id
     * @param agentId 助理id
     * @return 会话
     */
    public SessionDTO createSession(String userId, String agentId) {
        SessionDTO session = sessionDomainService.createSession(agentId, userId);
        AgentDTO agentDTO = agentServiceDomainService.getAgentWithPermissionCheck(agentId, userId);
        String welcomeMessage = agentDTO.getWelcomeMessage();
        conversationDomainService.saveAssistantMessage(session.getId(), welcomeMessage, "", "", 0);
        return session;
    }


    /**
     * 更新会话
     *
     * @param id     会话id
     * @param userId 用户id
     * @param title  标题
     */
    public void updateSession(String id, String userId, String title) {
        sessionDomainService.updateSession(id, userId, title);
    }

    /**
     * 删除会话
     *
     * @param id 会话id
     */
    @Transactional
    public void deleteSession(String id, String userId) {
        boolean deleteSession = sessionDomainService.deleteSession(id, userId);
        if (!deleteSession) {
            throw new BusinessException("删除会话失败");
        }
        // 删除会话下的消息
        conversationDomainService.deleteConversationMessages(id);
    }
}
