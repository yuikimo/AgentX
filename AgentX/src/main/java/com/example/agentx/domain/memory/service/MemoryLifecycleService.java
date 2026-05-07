package com.example.agentx.domain.memory.service;

import static com.example.agentx.domain.memory.service.MemoryServiceSupport.parseMemoryType;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import com.example.agentx.domain.memory.config.MemoryLifecycleProperties;
import com.example.agentx.domain.memory.model.MemoryItemEntity;
import com.example.agentx.domain.memory.model.MemoryType;
import com.example.agentx.domain.memory.repository.MemoryItemRepository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

@Service
public class MemoryLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(MemoryLifecycleService.class);

    private static final int ACTIVE = 1;

    private final MemoryItemRepository memoryItemRepository;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final MemoryWriter memoryWriter;
    private final MemoryLifecycleProperties memoryLifecycleProperties;

    public MemoryLifecycleService(MemoryItemRepository memoryItemRepository, JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager, MemoryWriter memoryWriter,
            MemoryLifecycleProperties memoryLifecycleProperties) {
        this.memoryItemRepository = memoryItemRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.memoryWriter = memoryWriter;
        this.memoryLifecycleProperties = memoryLifecycleProperties;
    }

    @Scheduled(fixedDelayString = "${memory.lifecycle.cleanup-fixed-delay-ms:3600000}",
            initialDelayString = "${memory.lifecycle.cleanup-initial-delay-ms:300000}")
    public void cleanupMemoryLifecycleScheduled() {
        try {
            LifecycleCleanupResult result = memoryLifecycleProperties.isClusterLockEnabled()
                    ? runLifecycleCleanupWithClusterLock()
                    : runLifecycleCleanup();
            logLifecycleCleanupResult(result);
        } catch (Exception e) {
            log.warn("记忆生命周期定时清理失败 err={}", e.getMessage(), e);
        }
    }

    public int cleanupExpiredEpisodicMemories() {
        int ttlDays = Math.max(1, memoryLifecycleProperties.getEpisodicTtlDays());
        int batchSize = normalizeBatchLimit(memoryLifecycleProperties.getCleanupBatchSize());
        int maxItems = normalizeRunLimit(memoryLifecycleProperties.getCleanupMaxItemsPerRun());
        LocalDateTime cutoff = LocalDateTime.now().minusDays(ttlDays);
        int archived = 0;
        while (archived < maxItems) {
            int limit = Math.min(batchSize, maxItems - archived);
            List<MemoryItemEntity> expiredItems = memoryItemRepository.selectList(Wrappers.<MemoryItemEntity>lambdaQuery()
                    .eq(MemoryItemEntity::getStatus, ACTIVE)
                    .eq(MemoryItemEntity::getType, MemoryType.EPISODIC.name())
                    .lt(MemoryItemEntity::getUpdatedAt, cutoff).orderByAsc(MemoryItemEntity::getUpdatedAt)
                    .orderByAsc(MemoryItemEntity::getId).last("LIMIT " + limit));
            if (CollectionUtils.isEmpty(expiredItems)) {
                break;
            }
            memoryWriter.archiveMemories(expiredItems, "episodic-ttl", false);
            archived += expiredItems.size();
            if (expiredItems.size() < limit) {
                break;
            }
        }
        return archived;
    }

    public int enforceAllUserMemoryLimits() {
        if (memoryLifecycleProperties.getMaxActivePerUser() <= 0) {
            return 0;
        }
        int userBatchSize = normalizeBatchLimit(memoryLifecycleProperties.getCleanupUserBatchSize());
        int maxUsers = normalizeRunLimit(memoryLifecycleProperties.getCleanupMaxUsersPerRun());
        int maxItems = normalizeRunLimit(memoryLifecycleProperties.getCleanupMaxItemsPerRun());
        int archived = 0;
        int scannedUsers = 0;
        String lastUserId = null;
        while (scannedUsers < maxUsers && archived < maxItems) {
            int limit = Math.min(userBatchSize, maxUsers - scannedUsers);
            LambdaQueryWrapper<MemoryItemEntity> query = Wrappers.<MemoryItemEntity>lambdaQuery()
                    .select(MemoryItemEntity::getUserId).eq(MemoryItemEntity::getStatus, ACTIVE)
                    .groupBy(MemoryItemEntity::getUserId).orderByAsc(MemoryItemEntity::getUserId)
                    .last("LIMIT " + limit);
            if (StringUtils.hasText(lastUserId)) {
                query.gt(MemoryItemEntity::getUserId, lastUserId);
            }
            List<Object> userIdObjects = memoryItemRepository.selectObjs(query);
            if (CollectionUtils.isEmpty(userIdObjects)) {
                break;
            }
            for (Object userIdObject : userIdObjects) {
                String userId = userIdObject == null ? null : String.valueOf(userIdObject);
                if (StringUtils.hasText(userId)) {
                    lastUserId = userId;
                }
                scannedUsers++;
                archived += enforceUserMemoryLimit(userId, maxItems - archived);
                if (scannedUsers >= maxUsers || archived >= maxItems) {
                    break;
                }
            }
            if (userIdObjects.size() < limit) {
                break;
            }
        }
        return archived;
    }

    public int enforceUserMemoryLimit(String userId) {
        return enforceUserMemoryLimit(userId, normalizeRunLimit(memoryLifecycleProperties.getCleanupMaxItemsPerRun()));
    }

    private LifecycleCleanupResult runLifecycleCleanupWithClusterLock() {
        return transactionTemplate.execute(status -> {
            Boolean locked = jdbcTemplate.queryForObject("SELECT pg_try_advisory_xact_lock(?)", Boolean.class,
                    memoryLifecycleProperties.getClusterLockKey());
            if (!Boolean.TRUE.equals(locked)) {
                log.debug("记忆生命周期清理跳过：未获取集群锁 key={}",
                        memoryLifecycleProperties.getClusterLockKey());
                return LifecycleCleanupResult.skipped();
            }
            return runLifecycleCleanup();
        });
    }

    private LifecycleCleanupResult runLifecycleCleanup() {
        int expired = cleanupExpiredEpisodicMemories();
        int overflow = enforceAllUserMemoryLimits();
        return new LifecycleCleanupResult(false, expired, overflow);
    }

    private void logLifecycleCleanupResult(LifecycleCleanupResult result) {
        if (result == null || result.lockSkipped()) {
            return;
        }
        if (result.expiredArchived() > 0 || result.overflowArchived() > 0) {
            log.info("记忆生命周期清理完成，过期归档={}, 容量淘汰={}", result.expiredArchived(),
                    result.overflowArchived());
        }
    }

    private int enforceUserMemoryLimit(String userId, int maxArchiveItems) {
        if (!StringUtils.hasText(userId) || memoryLifecycleProperties.getMaxActivePerUser() <= 0) {
            return 0;
        }
        int maxItems = normalizeRunLimit(maxArchiveItems);
        Long activeCount = memoryItemRepository.selectCount(Wrappers.<MemoryItemEntity>lambdaQuery()
                .eq(MemoryItemEntity::getUserId, userId).eq(MemoryItemEntity::getStatus, ACTIVE));
        long overflow = activeCount == null ? 0 : activeCount - memoryLifecycleProperties.getMaxActivePerUser();
        if (overflow <= 0) {
            return 0;
        }

        int archiveCount = (int) Math.min(overflow, maxItems);
        if (archiveCount <= 0) {
            return 0;
        }
        List<MemoryItemEntity> archiveCandidates = memoryItemRepository.selectList(Wrappers.<MemoryItemEntity>lambdaQuery()
                .eq(MemoryItemEntity::getUserId, userId).eq(MemoryItemEntity::getStatus, ACTIVE)
                .last("ORDER BY CASE type WHEN 'EPISODIC' THEN 1 WHEN 'TASK' THEN 2 WHEN 'FACT' THEN 3 "
                        + "WHEN 'PROFILE' THEN 4 ELSE 5 END ASC, COALESCE(importance, 0.5) ASC, "
                        + "COALESCE(hit_count, 0) ASC, last_hit_at ASC NULLS FIRST, "
                        + "updated_at ASC NULLS FIRST, id ASC LIMIT " + archiveCount));
        if (CollectionUtils.isEmpty(archiveCandidates)) {
            return 0;
        }
        memoryWriter.archiveMemories(new ArrayList<>(archiveCandidates), "capacity-limit", false);
        return archiveCandidates.size();
    }

    private Comparator<MemoryItemEntity> memoryArchiveComparator() {
        return Comparator.comparingInt(this::memoryTypeRetentionPriority)
                .thenComparing(item -> item.getImportance() == null ? 0.5f : item.getImportance())
                .thenComparing(item -> item.getHitCount() == null ? 0 : item.getHitCount())
                .thenComparing(MemoryItemEntity::getLastHitAt, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(MemoryItemEntity::getUpdatedAt, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(MemoryItemEntity::getId, Comparator.nullsLast(String::compareTo));
    }

    private int memoryTypeRetentionPriority(MemoryItemEntity item) {
        MemoryType type = parseMemoryType(item != null ? item.getType() : null);
        return switch (type) {
            case PROFILE -> 4;
            case FACT -> 3;
            case TASK -> 2;
            case EPISODIC -> 1;
        };
    }

    private int normalizeBatchLimit(int configuredLimit) {
        return Math.max(1, Math.min(configuredLimit, 1000));
    }

    private int normalizeRunLimit(int configuredLimit) {
        return Math.max(1, Math.min(configuredLimit, 100000));
    }

    private record LifecycleCleanupResult(boolean lockSkipped, int expiredArchived, int overflowArchived) {
        private static LifecycleCleanupResult skipped() {
            return new LifecycleCleanupResult(true, 0, 0);
        }
    }
}
