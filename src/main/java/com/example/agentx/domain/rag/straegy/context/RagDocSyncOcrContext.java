package com.example.agentx.domain.rag.straegy.context;

import com.example.agentx.domain.rag.model.enums.RagDocSyncOcrEnum;
import com.example.agentx.domain.rag.straegy.RagDocSyncOcrStrategy;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class RagDocSyncOcrContext {

    @Resource
    private Map<String, RagDocSyncOcrStrategy> taskExportStrategyMap;

    public RagDocSyncOcrStrategy getTaskExportStrategy(String strategy) {
        return taskExportStrategyMap.get(RagDocSyncOcrEnum.getLabelByValue(strategy));
    }
}

