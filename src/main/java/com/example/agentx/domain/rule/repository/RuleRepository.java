package com.example.agentx.domain.rule.repository;

import org.apache.ibatis.annotations.Mapper;
import com.example.agentx.domain.rule.model.RuleEntity;
import com.example.agentx.infrastructure.repository.MyBatisPlusExtRepository;

/**
 * 规则仓储接口
 */
@Mapper
public interface RuleRepository extends MyBatisPlusExtRepository<RuleEntity> {
}