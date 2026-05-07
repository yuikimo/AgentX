package com.example.agentx.application.task.service;

import org.springframework.stereotype.Service;
import com.example.agentx.application.task.assembler.TaskAssembler;
import com.example.agentx.application.task.dto.TaskDTO;
import com.example.agentx.domain.task.model.TaskAggregate;
import com.example.agentx.domain.task.model.TaskEntity;
import com.example.agentx.domain.task.service.TaskDomainService;

import java.util.ArrayList;
import java.util.List;

/** 任务应用服务 */
@Service
public class TaskAppService {

    private final TaskDomainService taskDomainService;

    public TaskAppService(TaskDomainService taskDomainService) {
        this.taskDomainService = taskDomainService;
    }

    /** 获取当前会话的最新任务
     *
     * @param sessionId 会话ID
     * @return 任务DTO列表 */
    public TaskAggregate getCurrentSessionTask(String sessionId, String userId) {
        return taskDomainService.getCurrentSessionTask(sessionId, userId);

    }
}