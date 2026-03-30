package com.example.agentx.domain.auth.repository;

import org.apache.ibatis.annotations.Mapper;
import com.example.agentx.domain.auth.model.AuthSettingEntity;
import com.example.agentx.infrastructure.repository.MyBatisPlusExtRepository;

/**
 * 认证配置Repository接口
 */
@Mapper
public interface AuthSettingRepository extends MyBatisPlusExtRepository<AuthSettingEntity> {
}