package com.example.agentx.domain.memory.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.agentx.domain.memory.model.CandidateMemory;
import com.example.agentx.domain.memory.model.MemoryItemEntity;
import com.example.agentx.domain.memory.model.MemoryResult;
import com.example.agentx.domain.memory.model.MemoryType;
import com.example.agentx.domain.memory.repository.MemoryItemRepository;
import com.example.agentx.infrastructure.exception.BusinessException;
import com.example.agentx.infrastructure.rag.factory.EmbeddingModelFactory;
import com.example.agentx.infrastructure.rag.service.UserModelConfigResolver;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static com.example.agentx.domain.memory.constant.MemoryMetadataConstant.ITEM_ID;
import static com.example.agentx.domain.memory.constant.MemoryMetadataConstant.MEMORY_TYPE;
import static com.example.agentx.domain.memory.constant.MemoryMetadataConstant.STATUS;
import static com.example.agentx.domain.memory.constant.MemoryMetadataConstant.TAGS;
import static com.example.agentx.domain.memory.constant.MemoryMetadataConstant.USER_ID;

/**
 * 记忆存取领域服务（与现有向量/模型体系对齐）
 */
@Service
public class MemoryDomainService {

    private static final Logger log = LoggerFactory.getLogger(MemoryDomainService.class);

    private static final int ACTIVE = 1;

    private final MemoryItemRepository memoryItemRepository;
    private final EmbeddingModelFactory embeddingModelFactory;
    private final UserModelConfigResolver userModelConfigResolver;

    private final EmbeddingStore<TextSegment> memoryEmbeddingStore;

    public MemoryDomainService(MemoryItemRepository memoryItemRepository, EmbeddingModelFactory embeddingModelFactory,
                               UserModelConfigResolver userModelConfigResolver,
                               @Qualifier("memoryEmbeddingStore") EmbeddingStore<TextSegment> memoryEmbeddingStore) {
        this.memoryItemRepository = memoryItemRepository;
        this.embeddingModelFactory = embeddingModelFactory;
        this.userModelConfigResolver = userModelConfigResolver;
        this.memoryEmbeddingStore = memoryEmbeddingStore;
    }

    /**
     * 保存记忆（去重/合并 + 向量入库）
     *
     * @return 写入/更新后的 itemId 列表
     */
    public List<String> saveMemories(String userId, String sessionId, List<CandidateMemory> candidates) {
        if (CollectionUtils.isEmpty(candidates)) {
            return Collections.emptyList();
        }

        // 构造嵌入模型
        var embeddingCfg = userModelConfigResolver.getUserEmbeddingModelConfig(userId);
        var embeddingModel = embeddingModelFactory.createEmbeddingModel(new EmbeddingModelFactory.EmbeddingConfig(
                embeddingCfg.getApiKey(), embeddingCfg.getBaseUrl(), embeddingCfg.getModelEndpoint()));

        List<String> itemIds = new ArrayList<>();
        for (CandidateMemory c : candidates) {
            if (c == null || !StringUtils.hasText(c.getText())) {
                continue;
            }

            MemoryType type = (c.getType() != null) ? c.getType() : MemoryType.FACT;
            String normalized = normalizeText(c.getText());
            String hash = sha256(normalized);

            // 查重（同用户，同hash）
            MemoryItemEntity existed = memoryItemRepository.selectOne(Wrappers.<MemoryItemEntity>lambdaQuery()
                    .eq(MemoryItemEntity::getUserId, userId)
                    .eq(MemoryItemEntity::getDedupeHash, hash));

            MemoryItemEntity toSave;
            if (existed == null) {
                // 新增
                toSave = new MemoryItemEntity();
                toSave.setUserId(userId);
                toSave.setType(type.name());
                toSave.setText(c.getText().trim());
                toSave.setData(c.getData());
                toSave.setImportance(safeImportance(c.getImportance()));
                toSave.setTags(safeList(c.getTags()));
                toSave.setSourceSessionId(sessionId);
                toSave.setDedupeHash(hash);
                toSave.setStatus(ACTIVE);
                try {
                    memoryItemRepository.insert(toSave);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            } else {
                // 合并（简单策略：importance 取 max，tags 合并去重，text 以更长者为准）
                toSave = existed;
                Float newImportance = max(existed.getImportance(), c.getImportance());
                toSave.setImportance(newImportance); // 1. 重要性取两者最高
                toSave.setTags(mergeTags(existed.getTags(), c.getTags())); // 2. 标签合并去重
                toSave.setData(mergeData(existed.getData(), c.getData())); // 3. 附加数据合并
                toSave.setText(pickRichText(existed.getText(), c.getText())); // 4. 文本取更长/信息量更大的那个
                memoryItemRepository.updateById(toSave);
            }

            itemIds.add(toSave.getId());

            // 向量入库
            try {
                // 1. 贴标签（Metadata 元数据）
                Metadata md = new Metadata();
                md.put(USER_ID, userId);
                md.put(ITEM_ID, toSave.getId());
                md.put(MEMORY_TYPE, type.name());
                md.put(TAGS, String.join(",", toSave.getTags() == null ? List.of() : toSave.getTags()));
                md.put(STATUS, String.valueOf(ACTIVE));

                // 2. 转换为向量并保存
                TextSegment segment = new TextSegment(toSave.getText(), md);
                Embedding emb = embeddingModel.embed(segment).content();
                memoryEmbeddingStore.add(emb, segment);
            } catch (Exception e) {
                log.error("向量入库失败 userId={}, itemId={}, err={}", userId, toSave.getId(), e.getMessage(), e);
                throw new BusinessException("记忆向量入库失败: " + e.getMessage(), e);
            }
        }
        return itemIds;
    }

    /**
     * 记忆检索（相似度 + 重要性加权）
     */
    public List<MemoryResult> searchRelevant(String userId, String query, int topK) {
        if (!StringUtils.hasText(query)) {
            return Collections.emptyList();
        }

        // 限制最多只能回想 16 条记忆
        int k = Math.max(1, Math.min(topK, 16));

        // 构造嵌入模型
        var embeddingCfg = userModelConfigResolver.getUserEmbeddingModelConfig(userId);
        var embeddingModel = embeddingModelFactory.createEmbeddingModel(new EmbeddingModelFactory.EmbeddingConfig(
                embeddingCfg.getApiKey(), embeddingCfg.getBaseUrl(), embeddingCfg.getModelEndpoint()
        ));

        try {
            Embedding queryEmbedding = embeddingModel.embed(query).content();

            EmbeddingSearchRequest req = EmbeddingSearchRequest.builder()
                    .filter(new IsEqualTo(USER_ID, userId)) // 仅召回本用户记忆
                    .maxResults(k * 3) // 候选加倍，再做加权筛选
                    .minScore(0.3)
                    .queryEmbedding(queryEmbedding)
                    .build();
            EmbeddingSearchResult<TextSegment> result = memoryEmbeddingStore.search(req);
            List<EmbeddingMatch<TextSegment>> matches = result.matches();
            if (CollectionUtils.isEmpty(matches)) {
                return Collections.emptyList();
            }

            // 批量获取 itemIds 并过滤 status = 1
            List<String> itemIds = matches.stream()
                    .map(m -> (String) m.embedded().metadata().toMap().get(ITEM_ID))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
            if (itemIds.isEmpty()) {
                return Collections.emptyList();
            }

            List<MemoryItemEntity> items = memoryItemRepository.selectList(Wrappers.<MemoryItemEntity>lambdaQuery()
                    .in(MemoryItemEntity::getId, itemIds));
            Map<String, MemoryItemEntity> itemMap = items.stream().collect(Collectors.toMap(MemoryItemEntity::getId,
                    it -> it, (a, b) -> a));

            // 生成结果，按加权分排序： sim * w1 + importance * w2
            List<MemoryResult> results = new ArrayList<>();
            for (EmbeddingMatch<TextSegment> m : matches) {
                String itemId = (String) m.embedded().metadata().toMap().get(ITEM_ID);
                if (!itemMap.containsKey(itemId)) {
                    continue;
                }

                MemoryItemEntity it = itemMap.get(itemId);
                double sim = m.score(); // 相似度得分
                // 核心打分公式： 70% 相似度 + 30% 重要性
                double weight = 0.7 * sim + 0.3 * (it.getImportance() == null ? 0.5 : it.getImportance());

                MemoryResult mr = new MemoryResult();
                mr.setItemId(itemId);
                mr.setType(MemoryType.safeOf(it.getType()));
                mr.setText(it.getText());
                mr.setImportance(it.getImportance());
                mr.setTags(it.getTags());
                mr.setScore(weight); // 设置最终综合得分
                results.add(mr);
            }

            return results.stream()
                    .sorted(Comparator.comparing(MemoryResult::getScore).reversed()) // 从高到低排序
                    .limit(k)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("记忆检索失败 userId={}, err={}", userId, e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    // =============== helper methods ===============

    /**
     * 分页列出用户记忆（可按类型过滤）
     */
    public Page<MemoryItemEntity> pageMemories(String userId, String type, int page, int pageSize) {
        Page<MemoryItemEntity> mpPage = new Page<>(Math.max(1, page), Math.max(1, pageSize));
        var qw = Wrappers.<MemoryItemEntity>lambdaQuery().eq(MemoryItemEntity::getUserId, userId);
        if (type != null && !type.isBlank()) {
            qw.eq(MemoryItemEntity::getType, type.trim().toUpperCase());
        }
        qw.orderByDesc(MemoryItemEntity::getUpdatedAt);
        memoryItemRepository.selectPage(mpPage, qw);
        return mpPage;
    }

    /**
     * 列出用户的记忆（可按类型过滤，带上限）
     */
    public List<MemoryItemEntity> listMemories(String userId, String type, Integer limit) {
        var qw = Wrappers.<MemoryItemEntity>lambdaQuery().eq(MemoryItemEntity::getUserId, userId);
        if (type != null && !type.isBlank()) {
            qw.eq(MemoryItemEntity::getType, type.trim().toUpperCase());
        }
        qw.orderByDesc(MemoryItemEntity::getUpdatedAt);
        List<MemoryItemEntity> list = memoryItemRepository.selectList(qw);
        if (limit != null && limit > 0 && list.size() > limit) {
            return list.subList(0, limit);
        }
        return list;
    }

    /**
     * 归档（软删除）记忆条目
     */
    public boolean delete(String userId, String itemId) {
        LambdaQueryWrapper<MemoryItemEntity> qw = Wrappers.<MemoryItemEntity>lambdaQuery()
                .eq(MemoryItemEntity::getUserId, userId)
                .eq(MemoryItemEntity::getId, itemId);
        memoryItemRepository.delete(qw);
        return true;
    }

    private static String normalizeText(String s) {
        return s == null ? "" : s.replaceAll("\n+", "\n").replaceAll("\s+", " ").trim().toLowerCase();
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest)
                sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new BusinessException("计算hash失败", e);
        }
    }

    private static Float safeImportance(Float f) {
        if (f == null)
            return 0.5f;
        return Math.max(0f, Math.min(1f, f));
    }

    private static List<String> safeList(List<String> list) {
        return list == null ? new ArrayList<>() : new ArrayList<>(list);
    }

    private static Float max(Float a, Float b) {
        if (a == null)
            return b == null ? 0.5f : b;
        if (b == null)
            return a;
        return Math.max(a, b);
    }

    private static List<String> mergeTags(List<String> a, List<String> b) {
        Set<String> set = new LinkedHashSet<>();
        if (a != null)
            set.addAll(a);
        if (b != null)
            set.addAll(b);
        return new ArrayList<>(set);
    }

    private static Map<String, Object> mergeData(Map<String, Object> a, Map<String, Object> b) {
        if (a == null && b == null)
            return null;
        Map<String, Object> m = new LinkedHashMap<>();
        if (a != null)
            m.putAll(a);
        if (b != null)
            m.putAll(b);
        return m;
    }

    private static String pickRichText(String oldText, String newText) {
        if (!StringUtils.hasText(newText))
            return oldText;
        if (!StringUtils.hasText(oldText))
            return newText;
        return newText.length() >= oldText.length() ? newText : oldText;
    }
}
