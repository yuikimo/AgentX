package com.example.agentx.domain.memory.model;

import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/** 记忆召回过滤条件 */
public class MemorySearchFilter {

    private String scopeAgentId;
    private List<String> tags = new ArrayList<>();
    private boolean includeGlobalScope = true;

    public static MemorySearchFilter empty() {
        return new MemorySearchFilter();
    }

    public static MemorySearchFilter forAgent(String scopeAgentId) {
        MemorySearchFilter filter = new MemorySearchFilter();
        filter.setScopeAgentId(scopeAgentId);
        return filter;
    }

    public boolean hasScopeAgentId() {
        return StringUtils.hasText(scopeAgentId);
    }

    public boolean hasTags() {
        return !CollectionUtils.isEmpty(tags);
    }

    public String getScopeAgentId() {
        return scopeAgentId;
    }

    public void setScopeAgentId(String scopeAgentId) {
        this.scopeAgentId = scopeAgentId;
    }

    public List<String> getTags() {
        return tags;
    }

    public void setTags(List<String> tags) {
        this.tags = tags == null ? new ArrayList<>() : new ArrayList<>(tags);
    }

    public boolean isIncludeGlobalScope() {
        return includeGlobalScope;
    }

    public void setIncludeGlobalScope(boolean includeGlobalScope) {
        this.includeGlobalScope = includeGlobalScope;
    }
}
