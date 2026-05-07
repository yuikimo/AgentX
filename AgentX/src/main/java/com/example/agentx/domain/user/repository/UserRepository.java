package com.example.agentx.domain.user.repository;

import org.apache.ibatis.annotations.Mapper;
import com.example.agentx.domain.user.model.UserEntity;
import com.example.agentx.infrastructure.repository.MyBatisPlusExtRepository;

/** 模型仓储接口 */
@Mapper
public interface UserRepository extends MyBatisPlusExtRepository<UserEntity> {

}