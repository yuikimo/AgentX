package com.example.agentx.domain.rule.repository;

import com.example.agentx.domain.rule.model.RuleEntity;
import com.example.agentx.infrastructure.repository.MyBatisPlusExtRepository;
import org.apache.ibatis.annotations.Mapper;

/**
 * 规则仓储接口
 */
@Mapper
public interface RuleRepository extends MyBatisPlusExtRepository<RuleEntity> {
}