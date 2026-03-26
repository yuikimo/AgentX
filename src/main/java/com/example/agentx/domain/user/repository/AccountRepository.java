package com.example.agentx.domain.user.repository;

import com.example.agentx.domain.user.model.AccountEntity;
import com.example.agentx.infrastructure.repository.MyBatisPlusExtRepository;
import org.apache.ibatis.annotations.Mapper;

/**
 * 账户仓储接口
 */
@Mapper
public interface AccountRepository extends MyBatisPlusExtRepository<AccountEntity> {
}