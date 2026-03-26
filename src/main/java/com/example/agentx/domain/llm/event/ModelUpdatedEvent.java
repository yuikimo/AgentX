package com.example.agentx.domain.llm.event;

import com.example.agentx.domain.llm.model.ModelEntity;

/**
 * 模型更新事件
 */
public class ModelUpdatedEvent extends ModelDomainEvent {

    /**
     * 更新后的模型实体
     */
    private final ModelEntity model;

    public ModelUpdatedEvent(String modelId, String userId, ModelEntity model) {
        super(modelId, userId);
        this.model = model;
    }

    public ModelEntity getModel() {
        return model;
    }
}