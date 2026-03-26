package com.example.agentx.domain.llm.event;

import com.example.agentx.domain.llm.model.ModelEntity;

/**
 * 模型创建事件
 */
public class ModelCreatedEvent extends ModelDomainEvent {

    /**
     * 模型实体
     */
    private final ModelEntity model;

    public ModelCreatedEvent(String modelId, String userId, ModelEntity model) {
        super(modelId, userId);
        this.model = model;
    }

    public ModelEntity getModel() {
        return model;
    }
}