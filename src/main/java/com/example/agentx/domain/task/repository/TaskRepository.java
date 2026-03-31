package com.example.agentx.domain.task.repository;

import org.apache.ibatis.annotations.Mapper;
import com.example.agentx.domain.task.model.TaskEntity;
import com.example.agentx.infrastructure.repository.MyBatisPlusExtRepository;

import java.util.List;

/**
 * 任务仓储接口
 */
@Mapper
public interface TaskRepository extends MyBatisPlusExtRepository<TaskEntity> {

}