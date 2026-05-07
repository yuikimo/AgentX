package com.example.agentx.infrastructure.rag.service;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import com.example.agentx.infrastructure.exception.BusinessException;
import com.example.agentx.infrastructure.rag.config.EmbeddingProperties;

/** 已登记向量表白名单，供动态SQL调用前校验 */
@Component
public class VectorTableRegistry {

    private static final Pattern TABLE_PATTERN = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)?$");

    private final Set<String> allowedTableNames = ConcurrentHashMap.newKeySet();

    public VectorTableRegistry(EmbeddingProperties embeddingProperties) {
        registerTable(embeddingProperties.getVectorStore().getTable());
    }

    public void registerTable(String tableName) {
        allowedTableNames.add(validateAndNormalize(tableName));
    }

    public boolean isAllowed(String tableName) {
        return allowedTableNames.contains(validateAndNormalize(tableName));
    }

    public String validateAllowed(String tableName) {
        String normalizedTableName = validateAndNormalize(tableName);
        if (!allowedTableNames.contains(normalizedTableName)) {
            throw new BusinessException("未登记的向量表名: " + tableName);
        }
        return normalizedTableName;
    }

    private String validateAndNormalize(String tableName) {
        if (tableName == null || tableName.isBlank() || !TABLE_PATTERN.matcher(tableName).matches()) {
            throw new BusinessException("非法向量表名: " + tableName);
        }
        return tableName.toLowerCase(Locale.ROOT);
    }
}
