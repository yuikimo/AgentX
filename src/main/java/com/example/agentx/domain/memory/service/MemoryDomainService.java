package com.example.agentx.domain.memory.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;
import com.example.agentx.domain.memory.model.CandidateMemory;
import com.example.agentx.domain.memory.model.MemoryItemEntity;
import com.example.agentx.domain.memory.model.MemoryResult;
import com.example.agentx.domain.memory.model.MemorySearchFilter;

import java.util.List;

/**
 * 记忆领域门面。
 * 将写入、召回、生命周期职责拆分到独立服务，当前类仅保留稳定对外接口。
 */
@Service
public class MemoryDomainService {

    private final MemoryWriter memoryWriter;
    private final MemoryRecaller memoryRecaller;
    private final MemoryLifecycleService memoryLifecycleService;

    public MemoryDomainService(MemoryWriter memoryWriter, MemoryRecaller memoryRecaller,
            MemoryLifecycleService memoryLifecycleService) {
        this.memoryWriter = memoryWriter;
        this.memoryRecaller = memoryRecaller;
        this.memoryLifecycleService = memoryLifecycleService;
    }

    public List<String> saveMemories(String userId, String sessionId, List<CandidateMemory> candidates) {
        return saveMemories(userId, sessionId, null, candidates);
    }

    public List<String> saveMemories(String userId, String sessionId, String scopeAgentId,
            List<CandidateMemory> candidates) {
        List<String> itemIds = memoryWriter.saveMemories(userId, sessionId, scopeAgentId, candidates);
        if (!itemIds.isEmpty()) {
            memoryLifecycleService.enforceUserMemoryLimit(userId);
        }
        return itemIds;
    }

    public List<String> saveExtractionCandidates(String userId, String sessionId, String scopeAgentId,
            List<CandidateMemory> acceptedCandidates, List<CandidateMemory> pendingCandidates) {
        List<String> itemIds = memoryWriter.saveExtractionCandidates(userId, sessionId, scopeAgentId, acceptedCandidates,
                pendingCandidates);
        if (!itemIds.isEmpty()) {
            memoryLifecycleService.enforceUserMemoryLimit(userId);
        }
        return itemIds;
    }

    public List<MemoryResult> searchRelevant(String userId, String query, int topK) {
        return memoryRecaller.searchRelevant(userId, query, topK);
    }

    public List<MemoryResult> searchRelevant(String userId, String query, int topK, MemorySearchFilter filter) {
        return memoryRecaller.searchRelevant(userId, query, topK, filter);
    }

    public Page<MemoryItemEntity> pageMemories(String userId, String type, String keyword, int page, int pageSize) {
        return memoryRecaller.pageMemories(userId, type, keyword, page, pageSize);
    }

    public List<MemoryItemEntity> listMemories(String userId, String type, Integer limit) {
        return memoryRecaller.listMemories(userId, type, limit);
    }

    public boolean delete(String userId, String itemId) {
        return memoryWriter.delete(userId, itemId);
    }

    public int deleteBatch(String userId, List<String> itemIds) {
        return memoryWriter.deleteBatch(userId, itemIds);
    }

    public void cleanupMemoryLifecycleScheduled() {
        memoryLifecycleService.cleanupMemoryLifecycleScheduled();
    }

    public int cleanupExpiredEpisodicMemories() {
        return memoryLifecycleService.cleanupExpiredEpisodicMemories();
    }

    public int enforceAllUserMemoryLimits() {
        return memoryLifecycleService.enforceAllUserMemoryLimits();
    }

    public int enforceUserMemoryLimit(String userId) {
        return memoryLifecycleService.enforceUserMemoryLimit(userId);
    }

    public long getRecallCacheVersion(String userId) {
        return memoryRecaller.getRecallCacheVersion(userId);
    }
}
