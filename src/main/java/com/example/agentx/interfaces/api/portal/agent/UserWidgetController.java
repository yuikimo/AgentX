package com.example.agentx.interfaces.api.portal.agent;

import com.example.agentx.application.agent.dto.AgentWidgetDTO;
import com.example.agentx.application.agent.service.AgentWidgetAppService;
import com.example.agentx.infrastructure.auth.UserContext;
import com.example.agentx.interfaces.api.common.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户小组件配置控制器
 */
@RestController
@RequestMapping("/user/widgets")
class UserWidgetController {

    private final AgentWidgetAppService agentWidgetAppService;

    public UserWidgetController(AgentWidgetAppService agentWidgetAppService) {
        this.agentWidgetAppService = agentWidgetAppService;
    }

    /**
     * 获取用户的所有小组件配置
     *
     * @return 小组件配置列表
     */
    @GetMapping
    public Result<List<AgentWidgetDTO>> getUserWidgets() {
        String userId = UserContext.getCurrentUserId();
        List<AgentWidgetDTO> widgets = agentWidgetAppService.getWidgetsByUser(userId);
        return Result.success(widgets);
    }
}
