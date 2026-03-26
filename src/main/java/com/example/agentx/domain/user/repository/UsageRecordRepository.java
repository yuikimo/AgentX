package com.example.agentx.domain.user.repository;

import com.example.agentx.domain.user.model.UsageRecordEntity;
import com.example.agentx.infrastructure.repository.MyBatisPlusExtRepository;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用量记录仓储接口
 */
@Mapper
public interface UsageRecordRepository extends MyBatisPlusExtRepository<UsageRecordEntity> {
}