package com.example.agentx.domain.scheduledtask.repository;

import org.apache.ibatis.annotations.Mapper;
import com.example.agentx.domain.scheduledtask.model.ScheduledTaskEntity;
import com.example.agentx.infrastructure.repository.MyBatisPlusExtRepository;

/** 定时任务仓储接口 */
@Mapper
public interface ScheduledTaskRepository extends MyBatisPlusExtRepository<ScheduledTaskEntity> {
}