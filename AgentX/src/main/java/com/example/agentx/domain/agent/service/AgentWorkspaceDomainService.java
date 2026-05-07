package com.example.agentx.domain.agent.service;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.time.Duration;
import java.util.stream.Collectors;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import org.springframework.stereotype.Service;
import com.example.agentx.domain.agent.model.AgentEntity;
import com.example.agentx.domain.agent.model.AgentWorkspaceEntity;
import com.example.agentx.domain.agent.repository.AgentRepository;
import com.example.agentx.domain.agent.repository.AgentWorkspaceRepository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.agentx.infrastructure.exception.BusinessException;

@Service
public class AgentWorkspaceDomainService {

    private final AgentWorkspaceRepository agentWorkspaceRepository;

    private final AgentRepository agentRepository;
    private final Cache<String, AgentWorkspaceEntity> workspaceCache = CacheBuilder.newBuilder().maximumSize(4096)
            .expireAfterWrite(Duration.ofMinutes(5)).build();

    public AgentWorkspaceDomainService(AgentWorkspaceRepository agentWorkspaceRepository,
            AgentDomainService agentServiceDomainService, AgentRepository agentRepository) {
        this.agentWorkspaceRepository = agentWorkspaceRepository;
        this.agentRepository = agentRepository;
    }

    public List<AgentEntity> getWorkspaceAgents(String userId) {

        LambdaQueryWrapper<AgentWorkspaceEntity> wrapper = Wrappers.<AgentWorkspaceEntity>lambdaQuery()
                .eq(AgentWorkspaceEntity::getUserId, userId).select(AgentWorkspaceEntity::getAgentId);

        List<String> agentIds = agentWorkspaceRepository.selectList(wrapper).stream()
                .map(AgentWorkspaceEntity::getAgentId).collect(Collectors.toList());

        if (agentIds.isEmpty()) {
            return Collections.emptyList();
        }
        return agentRepository.selectByIds(agentIds);

    }

    public boolean exist(String agentId, String userId) {
        Wrapper<AgentWorkspaceEntity> wrapper = Wrappers.<AgentWorkspaceEntity>lambdaQuery()
                .eq(AgentWorkspaceEntity::getAgentId, agentId).eq(AgentWorkspaceEntity::getUserId, userId);

        return agentWorkspaceRepository.selectCount(wrapper) > 0;
    }

    public boolean deleteAgent(String agentId, String userId) {
        workspaceCache.invalidate(buildWorkspaceCacheKey(agentId, userId));
        return agentWorkspaceRepository.delete(Wrappers.<AgentWorkspaceEntity>lambdaQuery()
                .eq(AgentWorkspaceEntity::getAgentId, agentId).eq(AgentWorkspaceEntity::getUserId, userId)) > 0;
    }

    public AgentWorkspaceEntity getWorkspace(String agentId, String userId) {
        String cacheKey = buildWorkspaceCacheKey(agentId, userId);
        AgentWorkspaceEntity cached = workspaceCache.getIfPresent(cacheKey);
        if (cached != null) {
            return cached;
        }
        Wrapper<AgentWorkspaceEntity> wrapper = Wrappers.<AgentWorkspaceEntity>lambdaQuery()
                .eq(AgentWorkspaceEntity::getAgentId, agentId).eq(AgentWorkspaceEntity::getUserId, userId);
        AgentWorkspaceEntity agentWorkspaceEntity = agentWorkspaceRepository.selectOne(wrapper);
        if (agentWorkspaceEntity == null) {
            throw new BusinessException("助理不存在");
        }
        workspaceCache.put(cacheKey, agentWorkspaceEntity);
        return agentWorkspaceEntity;
    }

    public AgentWorkspaceEntity findWorkspace(String agentId, String userId) {
        Wrapper<AgentWorkspaceEntity> wrapper = Wrappers.<AgentWorkspaceEntity>lambdaQuery()
                .eq(AgentWorkspaceEntity::getAgentId, agentId).eq(AgentWorkspaceEntity::getUserId, userId);
        return agentWorkspaceRepository.selectOne(wrapper);
    }

    public void save(AgentWorkspaceEntity workspace) {

        agentWorkspaceRepository.checkInsert(workspace);
        workspaceCache.put(buildWorkspaceCacheKey(workspace.getAgentId(), workspace.getUserId()), workspace);
    }

    public void update(AgentWorkspaceEntity workspace) {
        LambdaUpdateWrapper<AgentWorkspaceEntity> wrapper = Wrappers.<AgentWorkspaceEntity>lambdaUpdate()
                .eq(AgentWorkspaceEntity::getAgentId, workspace.getAgentId())
                .eq(AgentWorkspaceEntity::getUserId, workspace.getUserId());
        agentWorkspaceRepository.checkedUpdate(workspace, wrapper);
        workspaceCache.invalidate(buildWorkspaceCacheKey(workspace.getAgentId(), workspace.getUserId()));
    }

    public List<AgentWorkspaceEntity> listAgents(List<String> agentIds, String userId) {
        LambdaQueryWrapper<AgentWorkspaceEntity> wrapper = Wrappers.<AgentWorkspaceEntity>lambdaQuery()
                .eq(AgentWorkspaceEntity::getUserId, userId).in(AgentWorkspaceEntity::getAgentId, agentIds);
        return agentWorkspaceRepository.selectList(wrapper);
    }

    public List<AgentWorkspaceEntity> listByUserId(String userId) {
        LambdaQueryWrapper<AgentWorkspaceEntity> wrapper = Wrappers.<AgentWorkspaceEntity>lambdaQuery()
                .eq(AgentWorkspaceEntity::getUserId, userId);
        return agentWorkspaceRepository.selectList(wrapper);
    }

    public List<AgentWorkspaceEntity> listAll() {
        return agentWorkspaceRepository.selectList(Wrappers.<AgentWorkspaceEntity>lambdaQuery());
    }

    private String buildWorkspaceCacheKey(String agentId, String userId) {
        return agentId + "|" + userId;
    }
}
