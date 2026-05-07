package com.example.agentx.domain.memory.service;

import static com.example.agentx.domain.memory.constant.MemoryMetadataConstant.IMPORTANCE;
import static com.example.agentx.domain.memory.constant.MemoryMetadataConstant.ITEM_ID;
import static com.example.agentx.domain.memory.constant.MemoryMetadataConstant.MEMORY_TYPE;
import static com.example.agentx.domain.memory.constant.MemoryMetadataConstant.SCOPE_AGENT_ID;
import static com.example.agentx.domain.memory.constant.MemoryMetadataConstant.SOURCE_SESSION_ID;
import static com.example.agentx.domain.memory.constant.MemoryMetadataConstant.STATUS;
import static com.example.agentx.domain.memory.constant.MemoryMetadataConstant.TAGS;
import static com.example.agentx.domain.memory.constant.MemoryMetadataConstant.UPDATED_AT_EPOCH_MS;
import static com.example.agentx.domain.memory.constant.MemoryMetadataConstant.USER_ID;
import static com.example.agentx.domain.memory.service.MemoryServiceSupport.max;
import static com.example.agentx.domain.memory.service.MemoryServiceSupport.mergeData;
import static com.example.agentx.domain.memory.service.MemoryServiceSupport.mergeTags;
import static com.example.agentx.domain.memory.service.MemoryServiceSupport.normalizeText;
import static com.example.agentx.domain.memory.service.MemoryServiceSupport.parseMemoryType;
import static com.example.agentx.domain.memory.service.MemoryServiceSupport.pickRichText;
import static com.example.agentx.domain.memory.service.MemoryServiceSupport.safeImportance;
import static com.example.agentx.domain.memory.service.MemoryServiceSupport.safeList;
import static com.example.agentx.domain.memory.service.MemoryServiceSupport.sha256;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import dev.langchain4j.store.embedding.filter.comparison.IsIn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import com.example.agentx.domain.memory.config.MemoryExtractProperties;
import com.example.agentx.domain.memory.config.MemoryRecallProperties;
import com.example.agentx.domain.memory.model.CandidateMemory;
import com.example.agentx.domain.memory.model.MemoryItemEntity;
import com.example.agentx.domain.memory.model.MemoryPendingCandidateEntity;
import com.example.agentx.domain.memory.model.MemoryType;
import com.example.agentx.domain.memory.repository.MemoryItemRepository;
import com.example.agentx.domain.memory.repository.MemoryPendingCandidateRepository;
import com.example.agentx.infrastructure.exception.BusinessException;
import com.example.agentx.infrastructure.memory.config.MemoryEmbeddingProperties;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class MemoryWriter {

    private static final Logger log = LoggerFactory.getLogger(MemoryWriter.class);

    private static final int ACTIVE = 1;
    private static final int ARCHIVED = 0;
    private static final int PENDING_STATUS_ACTIVE = 1;
    private static final int PENDING_STATUS_PROMOTED = 2;
    private static final int ARCHIVE_BATCH_SIZE = 200;
    private static final List<ConflictTermGroup> CONFLICT_TERM_GROUPS = List.of(
            new ConflictTermGroup("language", List.of("简体中文", "中文", "chinese"), List.of("英文", "英语", "english")),
            new ConflictTermGroup("shell", List.of("bash", "shell"), List.of("powershell", "pwsh"),
                    List.of("cmd", "command prompt")),
            new ConflictTermGroup("detail", List.of("简洁", "简短", "短一点", "concise", "brief"),
                    List.of("详细", "展开", "完整", "detailed", "thorough")));

    private final MemoryItemRepository memoryItemRepository;
    private final MemoryPendingCandidateRepository memoryPendingCandidateRepository;
    private final MemoryEmbeddingModelProvider embeddingModelProvider;
    private final EmbeddingStore<TextSegment> memoryEmbeddingStore;
    private final MemoryExtractProperties memoryExtractProperties;
    private final MemoryRecallProperties memoryRecallProperties;
    private final MemoryRecaller memoryRecaller;
    private final MemoryEmbeddingProperties memoryEmbeddingProperties;

    public MemoryWriter(MemoryItemRepository memoryItemRepository,
            MemoryPendingCandidateRepository memoryPendingCandidateRepository,
            MemoryEmbeddingModelProvider embeddingModelProvider,
            @Qualifier("memoryEmbeddingStore") EmbeddingStore<TextSegment> memoryEmbeddingStore,
            MemoryExtractProperties memoryExtractProperties, MemoryRecallProperties memoryRecallProperties,
            MemoryRecaller memoryRecaller, MemoryEmbeddingProperties memoryEmbeddingProperties) {
        this.memoryItemRepository = memoryItemRepository;
        this.memoryPendingCandidateRepository = memoryPendingCandidateRepository;
        this.embeddingModelProvider = embeddingModelProvider;
        this.memoryEmbeddingStore = memoryEmbeddingStore;
        this.memoryExtractProperties = memoryExtractProperties;
        this.memoryRecallProperties = memoryRecallProperties;
        this.memoryRecaller = memoryRecaller;
        this.memoryEmbeddingProperties = memoryEmbeddingProperties;
    }

    @Transactional(rollbackFor = Exception.class)
    public List<String> saveMemories(String userId, String sessionId, String scopeAgentId, List<CandidateMemory> candidates) {
        return saveMemoriesInternal(userId, sessionId, scopeAgentId, candidates);
    }

    @Transactional(rollbackFor = Exception.class)
    public List<String> saveExtractionCandidates(String userId, String sessionId, String scopeAgentId,
            List<CandidateMemory> acceptedCandidates, List<CandidateMemory> pendingCandidates) {
        List<String> savedItemIds = new ArrayList<>();
        savedItemIds.addAll(saveMemoriesInternal(userId, sessionId, scopeAgentId, acceptedCandidates));
        markPendingCandidatesPromoted(userId, scopeAgentId, acceptedCandidates);
        List<CandidateMemory> promotedCandidates = upsertPendingCandidates(userId, sessionId, scopeAgentId,
                pendingCandidates);
        if (!CollectionUtils.isEmpty(promotedCandidates)) {
            savedItemIds.addAll(saveMemoriesInternal(userId, sessionId, scopeAgentId, promotedCandidates));
        }
        return savedItemIds;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean delete(String userId, String itemId) {
        MemoryItemEntity item = memoryItemRepository.selectOne(Wrappers.<MemoryItemEntity>lambdaQuery()
                .eq(MemoryItemEntity::getUserId, userId).eq(MemoryItemEntity::getId, itemId)
                .eq(MemoryItemEntity::getStatus, ACTIVE));
        if (item == null) {
            return false;
        }
        archiveMemory(item, "manual-delete", true);
        return true;
    }

    @Transactional(rollbackFor = Exception.class)
    public int deleteBatch(String userId, List<String> itemIds) {
        if (!StringUtils.hasText(userId) || CollectionUtils.isEmpty(itemIds)) {
            return 0;
        }
        List<String> distinctItemIds = itemIds.stream().filter(StringUtils::hasText).distinct().toList();
        if (distinctItemIds.isEmpty()) {
            return 0;
        }
        List<MemoryItemEntity> items = memoryItemRepository.selectList(Wrappers.<MemoryItemEntity>lambdaQuery()
                .eq(MemoryItemEntity::getUserId, userId).eq(MemoryItemEntity::getStatus, ACTIVE)
                .in(MemoryItemEntity::getId, distinctItemIds));
        if (CollectionUtils.isEmpty(items)) {
            return 0;
        }
        archiveMemories(items, "manual-batch-delete", true);
        return items.size();
    }

    public void archiveMemories(List<MemoryItemEntity> items, String reason, boolean strictVectorCleanup) {
        if (CollectionUtils.isEmpty(items)) {
            return;
        }
        Set<String> mutatedUserIds = new LinkedHashSet<>();
        Map<String, List<MemoryItemEntity>> itemsByUser = items.stream().filter(Objects::nonNull)
                .filter(item -> StringUtils.hasText(item.getId()) && StringUtils.hasText(item.getUserId()))
                .collect(Collectors.groupingBy(MemoryItemEntity::getUserId, LinkedHashMap::new, Collectors.toList()));
        for (Map.Entry<String, List<MemoryItemEntity>> entry : itemsByUser.entrySet()) {
            String userId = entry.getKey();
            List<MemoryItemEntity> userItems = entry.getValue();
            for (int start = 0; start < userItems.size(); start += ARCHIVE_BATCH_SIZE) {
                int end = Math.min(start + ARCHIVE_BATCH_SIZE, userItems.size());
                if (archiveMemoryBatch(userId, userItems.subList(start, end), reason, strictVectorCleanup)) {
                    mutatedUserIds.add(userId);
                }
            }
        }
        mutatedUserIds.forEach(memoryRecaller::invalidateUserRecallCache);
    }

    private void archiveMemory(MemoryItemEntity item, String reason, boolean strictVectorCleanup) {
        if (item == null || !StringUtils.hasText(item.getId())) {
            return;
        }
        if (archiveMemoryBatch(item.getUserId(), Collections.singletonList(item), reason, strictVectorCleanup)) {
            memoryRecaller.invalidateUserRecallCache(item.getUserId());
        }
    }

    private void markPendingCandidatesPromoted(String userId, String scopeAgentId, List<CandidateMemory> candidates) {
        List<PreparedMemory> preparedMemories = prepareMemories(candidates);
        if (CollectionUtils.isEmpty(preparedMemories)) {
            return;
        }
        List<String> hashes = preparedMemories.stream().map(PreparedMemory::hash).filter(StringUtils::hasText).distinct()
                .collect(Collectors.toList());
        if (hashes.isEmpty()) {
            return;
        }
        LambdaUpdateWrapper<MemoryPendingCandidateEntity> update = Wrappers.<MemoryPendingCandidateEntity>lambdaUpdate()
                .eq(MemoryPendingCandidateEntity::getUserId, userId)
                .eq(MemoryPendingCandidateEntity::getStatus, PENDING_STATUS_ACTIVE)
                .in(MemoryPendingCandidateEntity::getDedupeHash, hashes)
                .set(MemoryPendingCandidateEntity::getStatus, PENDING_STATUS_PROMOTED);
        if (StringUtils.hasText(scopeAgentId)) {
            update.eq(MemoryPendingCandidateEntity::getScopeAgentId, scopeAgentId);
        } else {
            update.and(wrapper -> wrapper.isNull(MemoryPendingCandidateEntity::getScopeAgentId)
                    .or().eq(MemoryPendingCandidateEntity::getScopeAgentId, ""));
        }
        memoryPendingCandidateRepository.update(null, update);
    }

    private List<String> saveMemoriesInternal(String userId, String sessionId, String scopeAgentId,
            List<CandidateMemory> candidates) {
        List<PreparedMemory> preparedMemories = prepareMemories(candidates);
        if (CollectionUtils.isEmpty(preparedMemories)) {
            log.debug("记忆写入跳过：候选为空，userId={}, sessionId={}", userId, sessionId);
            return Collections.emptyList();
        }

        EmbeddingModel embeddingModel = embeddingModelProvider.resolveEmbeddingModel(userId);
        Map<String, MemoryItemEntity> existingByHash = loadExistingMemoriesByHash(userId, scopeAgentId, preparedMemories);
        SemanticDuplicateResolution semanticDuplicateResolution = loadSemanticDuplicateMemories(userId, scopeAgentId,
                embeddingModel, preparedMemories, existingByHash);
        existingByHash.putAll(semanticDuplicateResolution.primaryByHash());
        Map<String, MemoryItemEntity> toVectorizeById = new LinkedHashMap<>();
        List<MemoryItemEntity> oldExistingSnapshots = new ArrayList<>();
        Set<String> oldSnapshotItemIds = new HashSet<>();

        for (PreparedMemory prepared : preparedMemories) {
            MemoryItemEntity existed = existingByHash.get(prepared.hash());
            MemoryItemEntity toSave;
            if (existed == null) {
                toSave = buildNewMemory(userId, sessionId, scopeAgentId, prepared);
                memoryItemRepository.checkInsert(toSave);
            } else {
                if (oldSnapshotItemIds.add(existed.getId())) {
                    oldExistingSnapshots.add(copyMemoryItem(existed));
                }
                MemoryItemEntity current = toVectorizeById.get(existed.getId());
                toSave = mergeExistingMemory(current != null ? current : existed, prepared);
                memoryItemRepository.checkedUpdateById(toSave);
            }
            toVectorizeById.put(toSave.getId(), toSave);
        }
        List<MemoryItemEntity> toVectorize = new ArrayList<>(toVectorizeById.values());

        try {
            replaceMemoryVectors(userId, embeddingModel, toVectorize);
            archiveMergedSemanticDuplicates(semanticDuplicateResolution.redundantItems());
            archiveConflictingMemories(userId, scopeAgentId, toVectorize);
            memoryRecaller.invalidateUserRecallCache(userId);
        } catch (Exception e) {
            compensateVectorWriteFailure(userId, embeddingModel, toVectorize, oldExistingSnapshots);
            log.error("记忆向量批量入库失败 userId={}, itemIds={}, err={}", userId,
                    toVectorize.stream().map(MemoryItemEntity::getId).collect(Collectors.toList()), e.getMessage(), e);
            throw new BusinessException("记忆向量入库失败: " + e.getMessage(), e);
        }

        List<String> itemIds = toVectorize.stream().map(MemoryItemEntity::getId).filter(Objects::nonNull).toList();
        log.debug("记忆写入完成，userId={}, sessionId={}, 保存条数={}", userId, sessionId, itemIds.size());
        return itemIds;
    }

    private List<CandidateMemory> upsertPendingCandidates(String userId, String sessionId, String scopeAgentId,
            List<CandidateMemory> candidates) {
        List<PreparedMemory> preparedMemories = prepareMemories(candidates);
        if (CollectionUtils.isEmpty(preparedMemories)) {
            return Collections.emptyList();
        }

        Map<String, MemoryPendingCandidateEntity> existingByHash = loadPendingCandidatesByHash(userId, scopeAgentId,
                preparedMemories);
        List<CandidateMemory> promotedCandidates = new ArrayList<>();
        for (PreparedMemory prepared : preparedMemories) {
            MemoryPendingCandidateEntity existing = existingByHash.get(prepared.hash());
            if (existing == null) {
                MemoryPendingCandidateEntity pendingCandidate = buildPendingCandidate(userId, sessionId, scopeAgentId,
                        prepared);
                if (shouldPromotePendingCandidate(pendingCandidate)) {
                    promotedCandidates.add(toPromotedCandidate(pendingCandidate));
                    continue;
                }
                insertPendingCandidate(pendingCandidate);
                existingByHash.put(prepared.hash(), pendingCandidate);
                continue;
            }

            MemoryPendingCandidateEntity merged = mergePendingCandidate(existing, sessionId, prepared);
            if (shouldPromotePendingCandidate(merged)) {
                merged.setStatus(PENDING_STATUS_PROMOTED);
                updatePendingCandidateById(merged);
                promotedCandidates.add(toPromotedCandidate(merged));
                existingByHash.remove(prepared.hash());
                continue;
            }
            updatePendingCandidateById(merged);
            existingByHash.put(prepared.hash(), merged);
        }
        if (!preparedMemories.isEmpty()) {
            log.debug("待确认记忆处理完成，userId={}, sessionId={}, 新增或更新候选={}, 升级候选={}", userId, sessionId,
                    preparedMemories.size(), promotedCandidates.size());
        }
        return promotedCandidates;
    }

    private Map<String, MemoryPendingCandidateEntity> loadPendingCandidatesByHash(String userId, String scopeAgentId,
            List<PreparedMemory> preparedMemories) {
        List<String> hashes = preparedMemories.stream().map(PreparedMemory::hash).filter(StringUtils::hasText).distinct()
                .collect(Collectors.toList());
        if (hashes.isEmpty()) {
            return Collections.emptyMap();
        }
        LambdaQueryWrapper<MemoryPendingCandidateEntity> query = Wrappers.<MemoryPendingCandidateEntity>lambdaQuery()
                .eq(MemoryPendingCandidateEntity::getUserId, userId)
                .eq(MemoryPendingCandidateEntity::getStatus, PENDING_STATUS_ACTIVE)
                .in(MemoryPendingCandidateEntity::getDedupeHash, hashes);
        appendExactPendingScopeConstraint(query, scopeAgentId);
        return memoryPendingCandidateRepository.selectList(query).stream()
                .filter(item -> StringUtils.hasText(item.getDedupeHash()))
                .collect(Collectors.toMap(MemoryPendingCandidateEntity::getDedupeHash, item -> item,
                        (first, ignored) -> first, LinkedHashMap::new));
    }

    private MemoryPendingCandidateEntity buildPendingCandidate(String userId, String sessionId, String scopeAgentId,
            PreparedMemory prepared) {
        MemoryPendingCandidateEntity entity = new MemoryPendingCandidateEntity();
        entity.setUserId(userId);
        entity.setScopeAgentId(StringUtils.hasText(scopeAgentId) ? scopeAgentId : null);
        entity.setSourceSessionId(sessionId);
        entity.setType(prepared.type().name());
        entity.setText(prepared.text());
        entity.setImportance(prepared.importance());
        entity.setTags(safeList(prepared.tags()));
        entity.setDedupeHash(prepared.hash());
        entity.setSeenCount(1);
        entity.setStatus(PENDING_STATUS_ACTIVE);
        return entity;
    }

    private MemoryPendingCandidateEntity mergePendingCandidate(MemoryPendingCandidateEntity existing, String sessionId,
            PreparedMemory prepared) {
        existing.setType(StringUtils.hasText(existing.getType()) ? existing.getType() : prepared.type().name());
        existing.setText(pickRichText(existing.getText(), prepared.text()));
        existing.setImportance(max(existing.getImportance(), prepared.importance()));
        existing.setTags(mergeTags(existing.getTags(), prepared.tags()));
        existing.setSourceSessionId(sessionId);
        existing.setSeenCount(Math.max(1, Optional.ofNullable(existing.getSeenCount()).orElse(0) + 1));
        existing.setStatus(PENDING_STATUS_ACTIVE);
        return existing;
    }

    private boolean shouldPromotePendingCandidate(MemoryPendingCandidateEntity candidate) {
        if (candidate == null) {
            return false;
        }
        int promoteSeenCount = Math.max(1, memoryExtractProperties.getPendingPromoteSeenCount());
        return Optional.ofNullable(candidate.getSeenCount()).orElse(0) >= promoteSeenCount;
    }

    private void insertPendingCandidate(MemoryPendingCandidateEntity candidate) {
        int affected = memoryPendingCandidateRepository.insert(candidate);
        if (affected == 0) {
            throw new BusinessException("待确认记忆写入失败");
        }
    }

    private void updatePendingCandidateById(MemoryPendingCandidateEntity candidate) {
        int affected = memoryPendingCandidateRepository.updateById(candidate);
        if (affected == 0) {
            throw new BusinessException("待确认记忆更新失败");
        }
    }

    private CandidateMemory toPromotedCandidate(MemoryPendingCandidateEntity candidate) {
        if (candidate == null) {
            return null;
        }
        MemoryType memoryType = MemoryType.safeOf(candidate.getType());
        CandidateMemory promoted = new CandidateMemory();
        promoted.setType(memoryType);
        promoted.setText(candidate.getText());
        promoted.setImportance(Math.max(resolveDirectThreshold(memoryType), safeImportance(candidate.getImportance())));
        promoted.setTags(safeList(candidate.getTags()));
        return promoted;
    }

    private List<PreparedMemory> prepareMemories(List<CandidateMemory> candidates) {
        if (CollectionUtils.isEmpty(candidates)) {
            return Collections.emptyList();
        }
        Map<String, PreparedMemory> preparedByHash = new LinkedHashMap<>();
        for (CandidateMemory candidate : candidates) {
            if (candidate == null || !StringUtils.hasText(candidate.getText())) {
                continue;
            }
            String text = MemoryContentSupport.sanitizeText(candidate.getText());
            if (!StringUtils.hasText(text)) {
                continue;
            }
            if (MemoryContentSupport.isLowInformationText(text, 4) || MemoryContentSupport.isLikelySensitiveText(text)
                    || MemoryContentSupport.isLikelyOperationalText(text)
                    || MemoryContentSupport.isLikelyMetaDialogueText(text)) {
                continue;
            }
            String normalized = normalizeText(text);
            if (!StringUtils.hasText(normalized)) {
                continue;
            }
            String hash = sha256(normalized);
            MemoryType type = candidate.getType() != null ? candidate.getType() : MemoryType.FACT;
            PreparedMemory current = new PreparedMemory(type, text, safeImportance(candidate.getImportance()),
                    MemoryContentSupport.sanitizeTags(candidate.getTags(), 6), candidate.getData(), hash);
            preparedByHash.merge(hash, current, this::mergePreparedMemory);
        }
        return new ArrayList<>(preparedByHash.values());
    }

    private PreparedMemory mergePreparedMemory(PreparedMemory oldMemory, PreparedMemory newMemory) {
        return new PreparedMemory(oldMemory.type(), pickRichText(oldMemory.text(), newMemory.text()),
                max(oldMemory.importance(), newMemory.importance()), mergeTags(oldMemory.tags(), newMemory.tags()),
                mergeData(oldMemory.data(), newMemory.data()), oldMemory.hash());
    }

    private Map<String, MemoryItemEntity> loadExistingMemoriesByHash(String userId, String scopeAgentId,
            List<PreparedMemory> memories) {
        List<String> hashes = memories.stream().map(PreparedMemory::hash).filter(StringUtils::hasText).distinct()
                .collect(Collectors.toList());
        if (hashes.isEmpty()) {
            return Collections.emptyMap();
        }
        LambdaQueryWrapper<MemoryItemEntity> query = Wrappers.<MemoryItemEntity>lambdaQuery()
                .eq(MemoryItemEntity::getUserId, userId).in(MemoryItemEntity::getDedupeHash, hashes);
        appendExactScopeConstraint(query, scopeAgentId);
        return memoryItemRepository.selectList(query).stream().filter(item -> StringUtils.hasText(item.getDedupeHash()))
                .collect(Collectors.toMap(MemoryItemEntity::getDedupeHash, item -> item, (first, ignored) -> first,
                        LinkedHashMap::new));
    }

    private SemanticDuplicateResolution loadSemanticDuplicateMemories(String userId, String scopeAgentId,
            EmbeddingModel embeddingModel, List<PreparedMemory> memories, Map<String, MemoryItemEntity> existingByHash) {
        List<PreparedMemory> unmatched = memories.stream().filter(memory -> !existingByHash.containsKey(memory.hash()))
                .collect(Collectors.toList());
        if (unmatched.isEmpty()) {
            return SemanticDuplicateResolution.empty();
        }

        try {
            List<SemanticDedupeCluster> dedupeClusters = clusterSemanticDedupeCandidates(unmatched);
            List<PreparedMemory> representatives = dedupeClusters.stream().map(SemanticDedupeCluster::representative)
                    .collect(Collectors.toList());
            Map<String, List<String>> memberHashesByRepresentativeHash = dedupeClusters.stream()
                    .collect(Collectors.toMap(cluster -> cluster.representative().hash(),
                            cluster -> cluster.members().stream().map(PreparedMemory::hash).distinct()
                                    .collect(Collectors.toList()),
                            (first, ignored) -> first, LinkedHashMap::new));
            if (representatives.size() < unmatched.size()) {
                log.debug("记忆语义去重批内聚类生效，userId={}, unmatched={}, representatives={}", userId,
                        unmatched.size(), representatives.size());
            }

            List<TextSegment> querySegments = representatives.stream()
                    .map(memory -> new TextSegment(memory.text(), new Metadata()))
                    .collect(Collectors.toList());
            List<Embedding> queryEmbeddings = embedSegmentsInBatches(embeddingModel, querySegments);
            if (queryEmbeddings == null || queryEmbeddings.size() != representatives.size()) {
                log.warn("记忆语义去重跳过：批量向量化结果数量不一致，userId={}", userId);
                return SemanticDuplicateResolution.empty();
            }

            Map<String, List<String>> duplicateItemIdsByHash = new LinkedHashMap<>();
            Set<String> duplicateItemIds = new LinkedHashSet<>();
            for (int i = 0; i < representatives.size(); i++) {
                EmbeddingSearchRequest request = EmbeddingSearchRequest.builder().filter(new IsEqualTo(USER_ID, userId))
                        .maxResults(Math.max(1, memoryRecallProperties.getSemanticDedupe().getMaxResults()))
                        .minScore(memoryRecallProperties.getSemanticDedupe().getMinScore())
                        .queryEmbedding(queryEmbeddings.get(i)).build();
                EmbeddingSearchResult<TextSegment> result = memoryEmbeddingStore.search(request);
                if (result == null || CollectionUtils.isEmpty(result.matches())) {
                    continue;
                }
                List<String> matchedItemIds = new ArrayList<>();
                for (EmbeddingMatch<TextSegment> match : result.matches()) {
                    String itemId = (String) match.embedded().metadata().toMap().get(ITEM_ID);
                    if (StringUtils.hasText(itemId) && !matchedItemIds.contains(itemId)) {
                        matchedItemIds.add(itemId);
                        duplicateItemIds.add(itemId);
                    }
                }
                if (!matchedItemIds.isEmpty()) {
                    String representativeHash = representatives.get(i).hash();
                    for (String memberHash : memberHashesByRepresentativeHash.getOrDefault(representativeHash,
                            List.of(representativeHash))) {
                        duplicateItemIdsByHash.put(memberHash, matchedItemIds);
                    }
                }
            }
            if (duplicateItemIds.isEmpty()) {
                return SemanticDuplicateResolution.empty();
            }

            LambdaQueryWrapper<MemoryItemEntity> duplicateQuery = Wrappers.<MemoryItemEntity>lambdaQuery()
                    .eq(MemoryItemEntity::getUserId, userId).eq(MemoryItemEntity::getStatus, ACTIVE)
                    .in(MemoryItemEntity::getId, duplicateItemIds);
            appendExactScopeConstraint(duplicateQuery, scopeAgentId);
            List<MemoryItemEntity> duplicateItems = memoryItemRepository.selectList(duplicateQuery);
            Map<String, MemoryItemEntity> itemById = duplicateItems.stream()
                    .collect(Collectors.toMap(MemoryItemEntity::getId, item -> item, (first, ignored) -> first));
            Map<String, MemoryItemEntity> duplicateByHash = new LinkedHashMap<>();
            Map<String, MemoryItemEntity> redundantItems = new LinkedHashMap<>();
            duplicateItemIdsByHash.forEach((hash, itemIds) -> {
                List<MemoryItemEntity> cluster = itemIds.stream().map(itemById::get).filter(Objects::nonNull).toList();
                if (cluster.isEmpty()) {
                    return;
                }
                MemoryItemEntity primary = copyMemoryItem(selectPrimaryDuplicate(cluster));
                for (MemoryItemEntity duplicate : cluster) {
                    if (duplicate == null || Objects.equals(duplicate.getId(), primary.getId())) {
                        continue;
                    }
                    mergeDuplicateItem(primary, duplicate);
                    redundantItems.putIfAbsent(duplicate.getId(), duplicate);
                }
                duplicateByHash.put(hash, primary);
            });
            if (!duplicateByHash.isEmpty()) {
                log.debug("记忆语义去重命中，userId={}, 命中条数={}", userId, duplicateByHash.size());
            }
            return new SemanticDuplicateResolution(duplicateByHash, new ArrayList<>(redundantItems.values()));
        } catch (Exception e) {
            log.warn("记忆语义去重失败，降级为hash去重 userId={}, err={}", userId, e.getMessage());
            return SemanticDuplicateResolution.empty();
        }
    }

    private List<SemanticDedupeCluster> clusterSemanticDedupeCandidates(List<PreparedMemory> memories) {
        if (CollectionUtils.isEmpty(memories) || memories.size() == 1) {
            return memories == null ? Collections.emptyList()
                    : memories.stream().map(memory -> new SemanticDedupeCluster(memory, List.of(memory)))
                            .collect(Collectors.toList());
        }
        double threshold = Math.max(0.50,
                Math.min(0.98, memoryRecallProperties.getSemanticDedupe().getIntraBatchTextSimilarity()));
        List<SemanticDedupeClusterBuilder> clusters = new ArrayList<>();
        for (PreparedMemory memory : memories) {
            if (memory == null) {
                continue;
            }
            SemanticDedupeClusterBuilder bestCluster = null;
            double bestSimilarity = 0;
            for (SemanticDedupeClusterBuilder cluster : clusters) {
                if (cluster.representative().type() != memory.type()) {
                    continue;
                }
                double similarity = textSimilarity(cluster.representative().text(), memory.text());
                if (similarity > bestSimilarity) {
                    bestSimilarity = similarity;
                    bestCluster = cluster;
                }
            }
            if (bestCluster != null && bestSimilarity >= threshold) {
                bestCluster.add(memory);
                continue;
            }
            clusters.add(new SemanticDedupeClusterBuilder(memory));
        }
        return clusters.stream().map(SemanticDedupeClusterBuilder::build).collect(Collectors.toList());
    }

    private double textSimilarity(String left, String right) {
        String normalizedLeft = normalizeText(left);
        String normalizedRight = normalizeText(right);
        if (!StringUtils.hasText(normalizedLeft) || !StringUtils.hasText(normalizedRight)) {
            return 0;
        }
        if (Objects.equals(normalizedLeft, normalizedRight)) {
            return 1;
        }
        if (normalizedLeft.contains(normalizedRight) || normalizedRight.contains(normalizedLeft)) {
            int shorterLength = Math.min(normalizedLeft.length(), normalizedRight.length());
            int longerLength = Math.max(normalizedLeft.length(), normalizedRight.length());
            return longerLength == 0 ? 0 : (double) shorterLength / longerLength;
        }
        Set<String> leftShingles = buildTextShingles(normalizedLeft);
        Set<String> rightShingles = buildTextShingles(normalizedRight);
        if (leftShingles.isEmpty() || rightShingles.isEmpty()) {
            return 0;
        }
        int intersection = 0;
        for (String shingle : leftShingles) {
            if (rightShingles.contains(shingle)) {
                intersection++;
            }
        }
        int union = leftShingles.size() + rightShingles.size() - intersection;
        return union <= 0 ? 0 : (double) intersection / union;
    }

    private Set<String> buildTextShingles(String text) {
        if (!StringUtils.hasText(text)) {
            return Collections.emptySet();
        }
        String normalized = text.replaceAll("\\s+", "");
        int codePointLength = normalized.codePointCount(0, normalized.length());
        if (codePointLength <= 3) {
            return Set.of(normalized);
        }
        List<Integer> offsets = new ArrayList<>();
        for (int offset = 0; offset < normalized.length();) {
            offsets.add(offset);
            offset = normalized.offsetByCodePoints(offset, 1);
        }
        offsets.add(normalized.length());
        Set<String> shingles = new LinkedHashSet<>();
        for (int i = 0; i + 3 < offsets.size(); i++) {
            shingles.add(normalized.substring(offsets.get(i), offsets.get(i + 3)));
        }
        return shingles;
    }

    private MemoryItemEntity buildNewMemory(String userId, String sessionId, String scopeAgentId, PreparedMemory prepared) {
        MemoryItemEntity entity = new MemoryItemEntity();
        entity.setUserId(userId);
        entity.setType(prepared.type().name());
        entity.setText(prepared.text());
        entity.setData(prepared.data());
        entity.setImportance(prepared.importance());
        entity.setTags(safeList(prepared.tags()));
        entity.setSourceSessionId(sessionId);
        entity.setScopeAgentId(StringUtils.hasText(scopeAgentId) ? scopeAgentId : null);
        entity.setDedupeHash(prepared.hash());
        entity.setStatus(ACTIVE);
        entity.setHitCount(0);
        return entity;
    }

    private MemoryItemEntity mergeExistingMemory(MemoryItemEntity existed, PreparedMemory prepared) {
        existed.setStatus(ACTIVE);
        existed.setImportance(max(existed.getImportance(), prepared.importance()));
        existed.setTags(mergeTags(existed.getTags(), prepared.tags()));
        existed.setData(mergeData(existed.getData(), prepared.data()));
        existed.setText(pickRichText(existed.getText(), prepared.text()));
        if (!StringUtils.hasText(existed.getType())) {
            existed.setType(prepared.type().name());
        }
        return existed;
    }

    private void mergeDuplicateItem(MemoryItemEntity target, MemoryItemEntity duplicate) {
        if (target == null || duplicate == null) {
            return;
        }
        target.setImportance(max(target.getImportance(), duplicate.getImportance()));
        target.setTags(mergeTags(target.getTags(), duplicate.getTags()));
        target.setData(mergeData(target.getData(), duplicate.getData()));
        target.setText(pickRichText(target.getText(), duplicate.getText()));
        if (!StringUtils.hasText(target.getType())) {
            target.setType(duplicate.getType());
        }
    }

    private MemoryItemEntity selectPrimaryDuplicate(List<MemoryItemEntity> cluster) {
        return cluster.stream().filter(Objects::nonNull)
                .max(Comparator.comparing((MemoryItemEntity item) -> item.getImportance() == null ? 0F : item.getImportance())
                        .thenComparing(item -> item.getUpdatedAt() == null ? LocalDateTime.MIN : item.getUpdatedAt())
                        .thenComparing(item -> StringUtils.hasText(item.getText()) ? item.getText().length() : 0))
                .orElseThrow();
    }

    private void archiveMergedSemanticDuplicates(List<MemoryItemEntity> duplicateItems) {
        if (CollectionUtils.isEmpty(duplicateItems)) {
            return;
        }
        archiveMemories(duplicateItems, "semantic-dedupe-merge", false);
    }

    private void archiveConflictingMemories(String userId, String scopeAgentId, List<MemoryItemEntity> newItems) {
        if (!StringUtils.hasText(userId) || CollectionUtils.isEmpty(newItems)) {
            return;
        }
        List<MemoryItemEntity> conflictInputs = newItems.stream().filter(Objects::nonNull)
                .filter(item -> item.getStatus() == null || item.getStatus() == ACTIVE)
                .filter(item -> isConflictManagedType(parseMemoryType(item.getType())))
                .filter(item -> hasConflictTerm(item.getText())).toList();
        if (conflictInputs.isEmpty()) {
            return;
        }
        Set<String> newItemIds = conflictInputs.stream().map(MemoryItemEntity::getId).filter(StringUtils::hasText)
                .collect(Collectors.toSet());
        List<String> types = conflictInputs.stream().map(MemoryItemEntity::getType).filter(StringUtils::hasText)
                .distinct().toList();
        if (types.isEmpty()) {
            return;
        }

        LambdaQueryWrapper<MemoryItemEntity> existingQuery = Wrappers.<MemoryItemEntity>lambdaQuery()
                .eq(MemoryItemEntity::getUserId, userId).eq(MemoryItemEntity::getStatus, ACTIVE)
                .in(MemoryItemEntity::getType, types);
        if (!newItemIds.isEmpty()) {
            existingQuery.notIn(MemoryItemEntity::getId, newItemIds);
        }
        appendExactScopeConstraint(existingQuery, scopeAgentId);
        List<MemoryItemEntity> existingItems = memoryItemRepository.selectList(existingQuery).stream()
                .filter(item -> item != null && StringUtils.hasText(item.getId()))
                .filter(item -> hasConflictTerm(item.getText())).toList();
        if (CollectionUtils.isEmpty(existingItems)) {
            return;
        }

        Map<String, MemoryItemEntity> conflictsById = new LinkedHashMap<>();
        for (MemoryItemEntity incoming : conflictInputs) {
            for (MemoryItemEntity existing : existingItems) {
                if (!Objects.equals(existing.getType(), incoming.getType())) {
                    continue;
                }
                if (isConflictingMemory(existing, incoming)) {
                    conflictsById.putIfAbsent(existing.getId(), existing);
                }
            }
        }
        if (!conflictsById.isEmpty()) {
            log.info("归档冲突旧记忆，userId={}, count={}, newItemIds={}", userId, conflictsById.size(), newItemIds);
            archiveMemories(new ArrayList<>(conflictsById.values()), "conflict-replaced-by-new-memory", false);
        }
    }

    private boolean isConflictManagedType(MemoryType type) {
        return type == MemoryType.PROFILE || type == MemoryType.TASK;
    }

    private boolean isConflictingMemory(MemoryItemEntity existing, MemoryItemEntity incoming) {
        String existingText = normalizeText(existing.getText());
        String incomingText = normalizeText(incoming.getText());
        if (!StringUtils.hasText(existingText) || !StringUtils.hasText(incomingText)
                || Objects.equals(existing.getDedupeHash(), incoming.getDedupeHash())) {
            return false;
        }
        for (ConflictTermGroup group : CONFLICT_TERM_GROUPS) {
            int existingVariant = group.matchVariant(existingText);
            int incomingVariant = group.matchVariant(incomingText);
            if (existingVariant >= 0 && incomingVariant >= 0 && existingVariant != incomingVariant) {
                return group.isStrongReplacementGroup() || tagsOverlap(existing.getTags(), incoming.getTags());
            }
        }
        return false;
    }

    private boolean tagsOverlap(List<String> left, List<String> right) {
        if (CollectionUtils.isEmpty(left) || CollectionUtils.isEmpty(right)) {
            return false;
        }
        Set<String> normalizedLeft = left.stream().filter(StringUtils::hasText).map(value -> value.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        return right.stream().filter(StringUtils::hasText).map(value -> value.toLowerCase(Locale.ROOT))
                .anyMatch(normalizedLeft::contains);
    }

    private boolean hasConflictTerm(String text) {
        String normalized = normalizeText(text);
        return StringUtils.hasText(normalized)
                && CONFLICT_TERM_GROUPS.stream().anyMatch(group -> group.matchVariant(normalized) >= 0);
    }

    private void replaceMemoryVectors(String userId, EmbeddingModel embeddingModel, List<MemoryItemEntity> items) {
        if (CollectionUtils.isEmpty(items)) {
            return;
        }
        int batchSize = resolveEmbeddingBatchSize();
        List<String> allItemIds = items.stream().map(MemoryItemEntity::getId).filter(Objects::nonNull).toList();
        for (int start = 0; start < items.size(); start += batchSize) {
            int end = Math.min(start + batchSize, items.size());
            List<MemoryItemEntity> batchItems = items.subList(start, end);
            List<TextSegment> segments = batchItems.stream().map(this::toMemorySegment).collect(Collectors.toList());
            List<Embedding> embeddings = embedSegmentsInBatches(embeddingModel, segments);
            if (embeddings == null || embeddings.size() != segments.size()) {
                throw new BusinessException("批量向量化结果数量不一致");
            }
            List<String> itemIds = batchItems.stream().map(MemoryItemEntity::getId).filter(Objects::nonNull).toList();
            List<String> embeddingIds = itemIds.stream().map(this::toEmbeddingStoreId).collect(Collectors.toList());
            memoryEmbeddingStore.addAll(embeddingIds, embeddings, segments);
        }
        log.debug("记忆向量批量替换完成，userId={}, itemIds={}", userId, allItemIds);
    }

    private TextSegment toMemorySegment(MemoryItemEntity item) {
        Metadata metadata = new Metadata();
        metadata.put(USER_ID, item.getUserId());
        metadata.put(ITEM_ID, item.getId());
        metadata.put(MEMORY_TYPE, StringUtils.hasText(item.getType()) ? item.getType() : MemoryType.FACT.name());
        metadata.put(TAGS, String.join(",", item.getTags() == null ? List.of() : item.getTags()));
        metadata.put(SCOPE_AGENT_ID, StringUtils.hasText(item.getScopeAgentId()) ? item.getScopeAgentId() : "");
        metadata.put(IMPORTANCE, item.getImportance() == null ? 0.5f : item.getImportance());
        metadata.put(UPDATED_AT_EPOCH_MS, resolveUpdatedAtEpochMs(item));
        metadata.put(SOURCE_SESSION_ID, StringUtils.hasText(item.getSourceSessionId()) ? item.getSourceSessionId() : "");
        metadata.put(STATUS, String.valueOf(item.getStatus() == null ? ACTIVE : item.getStatus()));
        return new TextSegment(item.getText(), metadata);
    }

    private void compensateVectorWriteFailure(String userId, EmbeddingModel embeddingModel,
            List<MemoryItemEntity> attemptedItems, List<MemoryItemEntity> oldExistingSnapshots) {
        try {
            Set<String> attemptedItemIds = attemptedItems.stream().map(MemoryItemEntity::getId).filter(Objects::nonNull)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            for (String itemId : attemptedItemIds) {
                memoryEmbeddingStore.removeAll(new IsEqualTo(ITEM_ID, itemId));
            }
            if (!CollectionUtils.isEmpty(oldExistingSnapshots)) {
                List<TextSegment> oldSegments = oldExistingSnapshots.stream().map(this::toMemorySegment)
                        .collect(Collectors.toList());
                List<Embedding> oldEmbeddings = embedSegmentsInBatches(embeddingModel, oldSegments);
                if (oldEmbeddings != null && oldEmbeddings.size() == oldSegments.size()) {
                    List<String> oldItemIds = oldExistingSnapshots.stream().map(MemoryItemEntity::getId).toList();
                    List<String> oldEmbeddingIds = oldItemIds.stream().map(this::toEmbeddingStoreId).toList();
                    memoryEmbeddingStore.addAll(oldEmbeddingIds, oldEmbeddings, oldSegments);
                }
            }
            memoryRecaller.invalidateUserRecallCache(userId);
        } catch (Exception compensationError) {
            log.warn("记忆向量失败补偿未完全成功 userId={}, err={}", userId, compensationError.getMessage(),
                    compensationError);
        }
    }

    private MemoryItemEntity copyMemoryItem(MemoryItemEntity source) {
        MemoryItemEntity copy = new MemoryItemEntity();
        copy.setId(source.getId());
        copy.setUserId(source.getUserId());
        copy.setType(source.getType());
        copy.setText(source.getText());
        copy.setData(source.getData() == null ? null : new LinkedHashMap<>(source.getData()));
        copy.setImportance(source.getImportance());
        copy.setTags(source.getTags() == null ? null : new ArrayList<>(source.getTags()));
        copy.setSourceSessionId(source.getSourceSessionId());
        copy.setScopeAgentId(source.getScopeAgentId());
        copy.setDedupeHash(source.getDedupeHash());
        copy.setStatus(source.getStatus());
        copy.setLastHitAt(source.getLastHitAt());
        copy.setHitCount(source.getHitCount());
        copy.setCreatedAt(source.getCreatedAt());
        copy.setUpdatedAt(source.getUpdatedAt());
        copy.setDeletedAt(source.getDeletedAt());
        return copy;
    }

    private String toEmbeddingStoreId(String itemId) {
        if (!StringUtils.hasText(itemId)) {
            return UUID.randomUUID().toString();
        }
        try {
            return UUID.fromString(itemId).toString();
        } catch (IllegalArgumentException ignored) {
        }
        String normalized = itemId.trim();
        if (normalized.matches("^[0-9a-fA-F]{32}$")) {
            String uuid = normalized.substring(0, 8) + "-" + normalized.substring(8, 12) + "-"
                    + normalized.substring(12, 16) + "-" + normalized.substring(16, 20) + "-"
                    + normalized.substring(20);
            try {
                return UUID.fromString(uuid).toString();
            } catch (IllegalArgumentException ignored) {
            }
        }
        return UUID.nameUUIDFromBytes(normalized.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private boolean archiveMemoryBatch(String userId, List<MemoryItemEntity> items, String reason, boolean strictVectorCleanup) {
        if (!StringUtils.hasText(userId) || CollectionUtils.isEmpty(items)) {
            return false;
        }
        List<String> itemIds = items.stream().filter(Objects::nonNull).map(MemoryItemEntity::getId)
                .filter(StringUtils::hasText).distinct().toList();
        if (itemIds.isEmpty()) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        LambdaUpdateWrapper<MemoryItemEntity> updateWrapper = Wrappers.<MemoryItemEntity>lambdaUpdate()
                .eq(MemoryItemEntity::getUserId, userId).eq(MemoryItemEntity::getStatus, ACTIVE)
                .in(MemoryItemEntity::getId, itemIds).set(MemoryItemEntity::getStatus, ARCHIVED)
                .set(MemoryItemEntity::getDeletedAt, now).set(MemoryItemEntity::getUpdatedAt, now);
        int affected = memoryItemRepository.update(null, updateWrapper);
        if (affected == 0) {
            log.debug("记忆归档跳过：条目已非活跃状态，userId={}, itemIds={}, reason={}", userId, itemIds, reason);
            return false;
        }
        for (MemoryItemEntity item : items) {
            if (item == null) {
                continue;
            }
            item.setStatus(ARCHIVED);
            item.setDeletedAt(now);
            item.setUpdatedAt(now);
        }
        cleanupArchivedMemoryVectors(userId, itemIds, reason, strictVectorCleanup);
        log.debug("记忆已批量归档，userId={}, itemCount={}, affected={}, reason={}", userId, itemIds.size(), affected, reason);
        return true;
    }

    private void cleanupArchivedMemoryVectors(String userId, List<String> itemIds, String reason,
            boolean strictVectorCleanup) {
        if (CollectionUtils.isEmpty(itemIds)) {
            return;
        }
        try {
            if (itemIds.size() == 1) {
                memoryEmbeddingStore.removeAll(new IsEqualTo(ITEM_ID, itemIds.get(0)));
            } else {
                memoryEmbeddingStore.removeAll(new IsIn(ITEM_ID, itemIds));
            }
            return;
        } catch (Exception batchException) {
            log.warn("记忆批量归档后向量批量清理失败，回退逐条清理 userId={}, itemCount={}, reason={}, err={}", userId,
                    itemIds.size(), reason, batchException.getMessage(), batchException);
        }
        List<String> failedItemIds = new ArrayList<>();
        for (String itemId : itemIds) {
            try {
                memoryEmbeddingStore.removeAll(new IsEqualTo(ITEM_ID, itemId));
            } catch (Exception itemException) {
                failedItemIds.add(itemId);
                log.warn("记忆归档后向量逐条清理失败 userId={}, itemId={}, reason={}, err={}", userId, itemId, reason,
                        itemException.getMessage(), itemException);
            }
        }
        if (!failedItemIds.isEmpty() && strictVectorCleanup) {
            throw new BusinessException("记忆向量清理失败: " + failedItemIds);
        }
    }

    private int resolveEmbeddingBatchSize() {
        return Math.max(1, Math.min(memoryEmbeddingProperties.getWriteBatchSize(), 50));
    }

    private List<Embedding> embedSegmentsInBatches(EmbeddingModel embeddingModel, List<TextSegment> segments) {
        if (CollectionUtils.isEmpty(segments)) {
            return Collections.emptyList();
        }
        int batchSize = resolveEmbeddingBatchSize();
        List<Embedding> embeddings = new ArrayList<>(segments.size());
        for (int start = 0; start < segments.size(); start += batchSize) {
            int end = Math.min(start + batchSize, segments.size());
            List<TextSegment> batchSegments = segments.subList(start, end);
            List<Embedding> batchEmbeddings = embeddingModel.embedAll(batchSegments).content();
            if (batchEmbeddings == null || batchEmbeddings.size() != batchSegments.size()) {
                throw new BusinessException("批量向量化结果数量不一致");
            }
            embeddings.addAll(batchEmbeddings);
        }
        return embeddings;
    }

    private void appendExactScopeConstraint(LambdaQueryWrapper<MemoryItemEntity> query, String scopeAgentId) {
        if (query == null) {
            return;
        }
        if (StringUtils.hasText(scopeAgentId)) {
            query.eq(MemoryItemEntity::getScopeAgentId, scopeAgentId);
            return;
        }
        query.and(wrapper -> wrapper.isNull(MemoryItemEntity::getScopeAgentId)
                .or().eq(MemoryItemEntity::getScopeAgentId, ""));
    }

    private void appendExactPendingScopeConstraint(LambdaQueryWrapper<MemoryPendingCandidateEntity> query,
            String scopeAgentId) {
        if (query == null) {
            return;
        }
        if (StringUtils.hasText(scopeAgentId)) {
            query.eq(MemoryPendingCandidateEntity::getScopeAgentId, scopeAgentId);
            return;
        }
        query.and(wrapper -> wrapper.isNull(MemoryPendingCandidateEntity::getScopeAgentId)
                .or().eq(MemoryPendingCandidateEntity::getScopeAgentId, ""));
    }

    private float resolveDirectThreshold(MemoryType memoryType) {
        MemoryType safeType = memoryType == null ? MemoryType.FACT : memoryType;
        return safeType == MemoryType.EPISODIC ? safeImportance(memoryExtractProperties.getEpisodicMinImportance())
                : safeImportance(memoryExtractProperties.getMinImportance());
    }

    private long resolveUpdatedAtEpochMs(MemoryItemEntity item) {
        LocalDateTime updatedAt = item != null ? item.getUpdatedAt() : null;
        if (updatedAt == null && item != null) {
            updatedAt = item.getCreatedAt();
        }
        if (updatedAt == null) {
            updatedAt = LocalDateTime.now();
        }
        return updatedAt.toInstant(ZoneOffset.UTC).toEpochMilli();
    }

    private record PreparedMemory(MemoryType type, String text, Float importance, List<String> tags,
            Map<String, Object> data, String hash) {
    }

    private record SemanticDedupeCluster(PreparedMemory representative, List<PreparedMemory> members) {
    }

    private static final class SemanticDedupeClusterBuilder {
        private PreparedMemory representative;
        private final List<PreparedMemory> members = new ArrayList<>();

        private SemanticDedupeClusterBuilder(PreparedMemory representative) {
            this.representative = representative;
            this.members.add(representative);
        }

        private PreparedMemory representative() {
            return representative;
        }

        private void add(PreparedMemory memory) {
            members.add(memory);
            if (memory.importance() != null && (representative.importance() == null
                    || memory.importance() > representative.importance())
                    || memory.text() != null && representative.text() != null
                            && memory.text().length() > representative.text().length()) {
                representative = memory;
            }
        }

        private SemanticDedupeCluster build() {
            return new SemanticDedupeCluster(representative, new ArrayList<>(members));
        }
    }

    private record SemanticDuplicateResolution(Map<String, MemoryItemEntity> primaryByHash,
            List<MemoryItemEntity> redundantItems) {

        private static SemanticDuplicateResolution empty() {
            return new SemanticDuplicateResolution(Collections.emptyMap(), Collections.emptyList());
        }
    }

    private static final class ConflictTermGroup {
        private final String name;
        private final List<List<String>> variants;

        @SafeVarargs
        private ConflictTermGroup(String name, List<String>... variants) {
            this.name = name;
            this.variants = List.of(variants);
        }

        private int matchVariant(String text) {
            if (!StringUtils.hasText(text)) {
                return -1;
            }
            for (int i = 0; i < variants.size(); i++) {
                List<String> keywords = variants.get(i);
                if (keywords.stream().filter(StringUtils::hasText)
                        .anyMatch(keyword -> text.contains(keyword.toLowerCase(Locale.ROOT)))) {
                    return i;
                }
            }
            return -1;
        }

        private boolean isStrongReplacementGroup() {
            return "language".equals(name) || "shell".equals(name);
        }
    }
}
