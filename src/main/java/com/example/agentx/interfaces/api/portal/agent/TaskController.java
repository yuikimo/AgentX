package com.example.agentx.interfaces.api.portal.agent;

import com.example.agentx.application.task.service.TaskAppService;
import com.example.agentx.domain.task.model.TaskAggregate;
import com.example.agentx.infrastructure.auth.UserContext;
import com.example.agentx.interfaces.api.common.Result;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * agent任务管理
 */
@RestController
@RequestMapping("/tasks")
public class TaskController {

    private final TaskAppService taskAppService;

    @Autowired
    public TaskController(TaskAppService taskAppService) {
        this.taskAppService = taskAppService;
    }

    /**
     * 获取当前会话的任务
     *
     * @param sessionId 会话id
     */
    @GetMapping("/session/{sessionId}/latest")
    public Result<TaskAggregate> getSessionTasks(@PathVariable String sessionId) {
        String userId = UserContext.getCurrentUserId();
        return Result.success(taskAppService.getCurrentSessionTask(sessionId, userId));
    }
}