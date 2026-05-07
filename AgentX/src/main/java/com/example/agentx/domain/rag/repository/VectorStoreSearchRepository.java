package com.example.agentx.domain.rag.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import com.example.agentx.domain.rag.model.VectorStoreResult;
import com.example.agentx.infrastructure.exception.BusinessException;
import com.example.agentx.infrastructure.rag.service.VectorTableRegistry;

/** 向量表全文检索（支持动态表名） */
@Repository
public class VectorStoreSearchRepository {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;
    private final ObjectMapper objectMapper;
    private final VectorTableRegistry vectorTableRegistry;

    public VectorStoreSearchRepository(NamedParameterJdbcTemplate namedParameterJdbcTemplate, ObjectMapper objectMapper,
            VectorTableRegistry vectorTableRegistry) {
        this.namedParameterJdbcTemplate = namedParameterJdbcTemplate;
        this.objectMapper = objectMapper;
        this.vectorTableRegistry = vectorTableRegistry;
    }

    public List<VectorStoreResult> keywordSearch(String tableName, List<String> dataSetIds, String userQuery, Integer maxResults) {
        if (dataSetIds == null || dataSetIds.isEmpty()) {
            return Collections.emptyList();
        }
        String safeTableName = vectorTableRegistry.validateAllowed(tableName);
        String sql = """
                WITH search_query AS (
                    SELECT websearch_to_tsquery('chinese_cfg', :userQuery) AS query
                ),
                ranked AS (
                    SELECT
                        embedding_id::text AS embedding_id,
                        text,
                        metadata::text AS metadata,
                        ts_rank_cd(to_tsvector('chinese_cfg', text), search_query.query) AS raw_score
                    FROM %s, search_query
                    WHERE (metadata ->> 'DATA_SET_ID') IN (:datasetIds)
                      AND search_query.query <> ''::tsquery
                      AND to_tsvector('chinese_cfg', text) @@ search_query.query
                )
                SELECT
                    embedding_id,
                    text,
                    metadata,
                    CASE
                        WHEN MAX(raw_score) OVER () > 0 THEN raw_score / MAX(raw_score) OVER ()
                        ELSE 0
                    END AS score
                FROM ranked
                ORDER BY raw_score DESC
                LIMIT :maxResults
                """.formatted(safeTableName);

        MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("datasetIds", dataSetIds);
        params.addValue("userQuery", userQuery);
        params.addValue("maxResults", maxResults);

        return namedParameterJdbcTemplate.query(sql, params, (rs, rowNum) -> {
            VectorStoreResult result = new VectorStoreResult();
            result.setEmbeddingId(rs.getString("embedding_id"));
            result.setText(rs.getString("text"));
            String metadataJson = rs.getString("metadata");
            if (metadataJson != null) {
                try {
                    result.setMetadata(objectMapper.readValue(metadataJson, MAP_TYPE));
                } catch (Exception e) {
                    throw new BusinessException("解析向量检索metadata失败: " + e.getMessage(), e);
                }
            }
            result.setScore(rs.getDouble("score"));
            return result;
        });
    }
}
