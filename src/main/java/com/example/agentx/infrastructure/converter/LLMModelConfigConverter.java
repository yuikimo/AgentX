package com.example.agentx.infrastructure.converter;

import com.example.agentx.domain.agent.model.LLMModelConfig;
import org.apache.ibatis.type.MappedTypes;

/**
 * LLMModelConfig JSON转换器
 */
@MappedTypes(LLMModelConfig.class)
public class LLMModelConfigConverter extends JsonToStringConverter<LLMModelConfig> {

    public LLMModelConfigConverter() {
        super(LLMModelConfig.class);
    }
}