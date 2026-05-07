package com.example.agentx.infrastructure.rag.service;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;
import com.example.agentx.domain.rag.constant.EmbeddingDistanceMetric;
import com.example.agentx.infrastructure.rag.config.EmbeddingProperties;

/** EmbeddingStore 按Profile路由（本地缓存） */
@Component
public class EmbeddingStoreRouter {

    private final EmbeddingProperties embeddingProperties;
    private final EmbeddingTableManager embeddingTableManager;
    private final VectorTableRegistry vectorTableRegistry;
    private final DataSource dataSource;
    private final Map<String, EmbeddingStore<TextSegment>> storeCache = new ConcurrentHashMap<>();

    public EmbeddingStoreRouter(EmbeddingProperties embeddingProperties, EmbeddingTableManager embeddingTableManager,
            VectorTableRegistry vectorTableRegistry, DataSource dataSource) {
        this.embeddingProperties = embeddingProperties;
        this.embeddingTableManager = embeddingTableManager;
        this.vectorTableRegistry = vectorTableRegistry;
        this.dataSource = dataSource;
    }

    public EmbeddingStore<TextSegment> getOrCreateStore(String profileId, String tableName, Integer dimension,
            EmbeddingDistanceMetric metric) {
        vectorTableRegistry.registerTable(tableName);
        return storeCache.computeIfAbsent(buildStoreCacheKey(profileId, tableName, dimension, metric),
                key -> createStore(tableName, dimension, metric));
    }

    public String getDefaultTableName() {
        return embeddingProperties.getVectorStore().getTable();
    }

    public Integer getDefaultDimension() {
        return embeddingProperties.getVectorStore().getDimension();
    }

    private EmbeddingStore<TextSegment> createStore(String tableName, Integer dimension, EmbeddingDistanceMetric metric) {
        int finalDimension = dimension != null && dimension > 0 ? dimension : embeddingProperties.getVectorStore().getDimension();
        EmbeddingDistanceMetric finalMetric = metric == null ? EmbeddingDistanceMetric.COSINE : metric;
        embeddingTableManager.ensureTable(tableName, finalDimension, finalMetric);
        return PgVectorEmbeddingStore.datasourceBuilder().datasource(dataSource).table(tableName).dropTableFirst(false)
                .createTable(false).dimension(finalDimension).build();
    }

    private String buildStoreCacheKey(String profileId, String tableName, Integer dimension,
            EmbeddingDistanceMetric metric) {
        return String.join("|", profileId == null ? "default" : profileId,
                tableName == null ? embeddingProperties.getVectorStore().getTable() : tableName,
                String.valueOf(dimension == null ? embeddingProperties.getVectorStore().getDimension() : dimension),
                String.valueOf(metric == null ? EmbeddingDistanceMetric.COSINE : metric));
    }
}
