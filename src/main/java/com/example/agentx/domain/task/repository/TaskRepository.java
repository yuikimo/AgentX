package com.example.agentx.domain.task.repository;

import com.example.agentx.domain.task.model.TaskEntity;
import com.example.agentx.infrastructure.repository.MyBatisPlusExtRepository;
import org.apache.ibatis.annotations.Mapper;

/**
 * 任务仓储接口
 */
@Mapper
public interface TaskRepository extends MyBatisPlusExtRepository<TaskEntity> {

} 