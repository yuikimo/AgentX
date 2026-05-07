package com.example.agentx.domain.user.repository;

import org.apache.ibatis.annotations.Mapper;
import com.example.agentx.domain.user.model.AccountEntity;
import com.example.agentx.infrastructure.repository.MyBatisPlusExtRepository;

/** 账户仓储接口 */
@Mapper
public interface AccountRepository extends MyBatisPlusExtRepository<AccountEntity> {
}