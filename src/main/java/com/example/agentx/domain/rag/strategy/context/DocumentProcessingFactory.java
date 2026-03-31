package com.example.agentx.domain.rag.strategy.context;

import java.util.Map;

import org.springframework.stereotype.Service;
import com.example.agentx.domain.rag.model.enums.DocumentProcessingType;
import com.example.agentx.domain.rag.strategy.DocumentProcessingStrategy;

import jakarta.annotation.Resource;

@Service
public class DocumentProcessingFactory {

    @Resource
    private Map<String, DocumentProcessingStrategy> documentProcessingStrategyMap;

    public DocumentProcessingStrategy getDocumentStrategyHandler(String strategy) {
        return documentProcessingStrategyMap.get(DocumentProcessingType.getLabelByValue(strategy));
    }
}
