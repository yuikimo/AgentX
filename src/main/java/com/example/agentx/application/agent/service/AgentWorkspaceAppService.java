package com.example.agentx.application.agent.service;

import com.example.agentx.application.agent.dto.AgentDTO;
import com.example.agentx.domain.agent.service.AgentDomainService;
import com.example.agentx.domain.agent.service.AgentWorkspaceDomainService;
import com.example.agentx.domain.conversation.service.ConversationDomainService;
import com.example.agentx.domain.conversation.service.SessionDomainService;
import com.example.agentx.infrastructure.exception.BusinessException;
import com.example.agentx.interfaces.dto.agent.SearchAgentsRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Agent应用服务，用于适配领域层的Agent服务
 * 职责：
 * 1. 接收和验证来自接口层的请求
 * 2. 将请求转换为领域对象或参数
 * 3. 调用领域服务执行业务逻辑
 * 4. 转换和返回结果给接口层
 */
@Service
public class AgentWorkspaceAppService {

    private final AgentWorkspaceDomainService agentWorkspaceDomainService;

    private final AgentDomainService agentServiceDomainService;

    private final SessionDomainService sessionDomainService;

    private final ConversationDomainService conversationDomainService;

    public AgentWorkspaceAppService(AgentWorkspaceDomainService agentWorkspaceDomainService,
                                    AgentDomainService agentServiceDomainService,
                                    SessionDomainService sessionDomainService,
                                    ConversationDomainService conversationDomainService) {
        this.agentWorkspaceDomainService = agentWorkspaceDomainService;
        this.agentServiceDomainService = agentServiceDomainService;
        this.sessionDomainService = sessionDomainService;
        this.conversationDomainService = conversationDomainService;
    }

    /**
     * 获取工作区下的助理
     *
     * @param userId 用户id
     * @return
     */
    public List<AgentDTO> getAgents(String userId) {
        // 1.获取当前用户的所有助理
        List<AgentDTO> userAgents = agentServiceDomainService.getUserAgents(userId, new SearchAgentsRequest());

        // 2.获取已添加到工作区的助理
        List<AgentDTO> workspaceAgents = agentWorkspaceDomainService.getWorksapceAgents(userId);

        // 合并两个列表
        userAgents.addAll(workspaceAgents);
        return userAgents;
    }

    @Transactional
    public void deleteAgent(String agentId, String userId) {
        boolean deleteAgent = agentWorkspaceDomainService.deleteAgent(agentId, userId);
        if (!deleteAgent) {
            throw new BusinessException("删除助理失败");
        }

        // 查出会话列表，收集SessionIds
        List<String> sessionIds = sessionDomainService.getSessionsByAgentId(agentId)
                .stream()
                .map(SessionDTO::getId)
                .collect(Collectors.toList());

        if (sessionIds.isEmpty()) {
            return;
        }

        // 删除Agent下的会话
        sessionDomainService.deleteSessions(sessionIds);
        // 删除会话下的所有消息
        conversationDomainService.deleteConversationMessages(sessionIds);
    }

}
