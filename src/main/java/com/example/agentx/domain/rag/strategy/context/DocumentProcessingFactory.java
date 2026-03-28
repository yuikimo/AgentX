package com.example.agentx.domain.rag.strategy.context;

import com.example.agentx.domain.rag.model.enums.DocumentProcessingType;
import com.example.agentx.domain.rag.strategy.DocumentProcessingStrategy;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class DocumentProcessingFactory {

    @Resource
    private Map<String, DocumentProcessingStrategy> documentProcessingStrategyMap;

    public DocumentProcessingStrategy getDocumentStrategyHandler(String strategy) {
        return documentProcessingStrategyMap.get(DocumentProcessingType.getLabelByValue(strategy));
    }
}
