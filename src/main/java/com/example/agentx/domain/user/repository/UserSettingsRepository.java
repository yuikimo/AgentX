package com.example.agentx.domain.user.repository;

import org.apache.ibatis.annotations.Mapper;
import com.example.agentx.domain.user.model.UserSettingsEntity;
import com.example.agentx.infrastructure.repository.MyBatisPlusExtRepository;

/** 用户设置仓储接口 */
@Mapper
public interface UserSettingsRepository extends MyBatisPlusExtRepository<UserSettingsEntity> {

}