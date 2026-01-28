package com.example.agentx.interfaces.api.portal.agent;

import com.example.agentx.application.agent.service.AgentWorkspaceAppService;
import com.example.agentx.domain.agent.dto.AgentDTO;
import com.example.agentx.interfaces.api.common.Result;
import com.example.agentx.interfaces.auth.UserContext;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Agent会话关联接口
 */
@RestController
@RequestMapping("/agent/workspace")
public class PortalWorkspaceController {

    private final AgentWorkspaceAppService agentWorkspaceAppService;

    public PortalWorkspaceController(AgentWorkspaceAppService agentWorkspaceAppService) {
        this.agentWorkspaceAppService = agentWorkspaceAppService;
    }

    /**
     * 获取工作区下的助理
     *
     * @return
     */
    @GetMapping("/agents")
    public Result<List<AgentDTO>> getAgents() {
        String userId = UserContext.getCurrentUserId();
        return Result.success(agentWorkspaceAppService.getAgents(userId));
    }


    /**
     * 删除工作区中的助理
     *
     * @param id 助理id
     */
    @DeleteMapping("/agents/{id}")
    public Result<Void> deleteAgent(@PathVariable String id) {
        String userId = UserContext.getCurrentUserId();
        agentWorkspaceAppService.deleteAgent(id, userId);
        return Result.success();
    }

}
