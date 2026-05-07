package com.example.agentx.infrastructure.rag.service;

import java.util.Locale;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import com.example.agentx.domain.rag.constant.EmbeddingDistanceMetric;
import com.example.agentx.infrastructure.exception.BusinessException;

/** 动态创建/校验 Embedding 向量表 */
@Component
public class EmbeddingTableManager {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingTableManager.class);
    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_]*$");

    private final JdbcTemplate jdbcTemplate;

    public EmbeddingTableManager(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void ensureTable(String fullTableName, int dimension, EmbeddingDistanceMetric metric) {
        TableRef tableRef = parseAndValidate(fullTableName);
        if (dimension <= 0) {
            throw new BusinessException("无效的嵌入维度: " + dimension);
        }

        String qualifiedTable = quoteQualified(tableRef);
        String indexSuffix = tableRef.table().toLowerCase(Locale.ROOT);
        String opClass = switch (metric) {
            case L2 -> "vector_l2_ops";
            case IP -> "vector_ip_ops";
            case COSINE -> "vector_cosine_ops";
        };

        jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS vector");
        jdbcTemplate.execute(String.format(
                "CREATE TABLE IF NOT EXISTS %s (embedding_id uuid PRIMARY KEY NOT NULL, embedding vector(%d), text text, metadata jsonb)",
                qualifiedTable, dimension));
        jdbcTemplate.execute(String.format(
                "CREATE INDEX IF NOT EXISTS idx_%s_embedding ON %s USING ivfflat (embedding %s)",
                indexSuffix, qualifiedTable, opClass));
        jdbcTemplate.execute(String.format(
                "CREATE INDEX IF NOT EXISTS idx_%s_dataset ON %s ((metadata ->> 'DATA_SET_ID'))",
                indexSuffix, qualifiedTable));
        jdbcTemplate.execute(String.format(
                "CREATE INDEX IF NOT EXISTS idx_%s_fts ON %s USING GIN (to_tsvector('chinese_cfg', text))",
                indexSuffix, qualifiedTable));

        log.debug("Embedding向量表已就绪: table={}, dimension={}, metric={}", fullTableName, dimension, metric);
    }

    private TableRef parseAndValidate(String fullTableName) {
        if (fullTableName == null || fullTableName.isBlank()) {
            throw new BusinessException("向量表名不能为空");
        }

        String[] parts = fullTableName.split("\\.");
        String schema;
        String table;
        if (parts.length == 1) {
            schema = "public";
            table = parts[0];
        } else if (parts.length == 2) {
            schema = parts[0];
            table = parts[1];
        } else {
            throw new BusinessException("非法向量表名: " + fullTableName);
        }

        if (!IDENTIFIER_PATTERN.matcher(schema).matches() || !IDENTIFIER_PATTERN.matcher(table).matches()) {
            throw new BusinessException("非法向量表标识符: " + fullTableName);
        }
        return new TableRef(schema, table);
    }

    private String quoteQualified(TableRef tableRef) {
        return "\"" + tableRef.schema() + "\".\"" + tableRef.table() + "\"";
    }

    private record TableRef(String schema, String table) {
    }
}

