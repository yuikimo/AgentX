package com.example.agentx.domain.llm.event;

/**
 * 模型删除事件
 */
public class ModelDeletedEvent extends ModelDomainEvent {

    public ModelDeletedEvent(String modelId, String userId) {
        super(modelId, userId);
    }
}