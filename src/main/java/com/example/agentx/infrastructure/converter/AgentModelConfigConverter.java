package com.example.agentx.infrastructure.converter;

import com.example.agentx.domain.agent.model.AgentModelConfig;
import org.apache.ibatis.type.MappedTypes;

/**
 * 模型配置转换器
 */
@MappedTypes(AgentModelConfig.class)
public class AgentModelConfigConverter extends JsonToStringConverter<AgentModelConfig> {

    public AgentModelConfigConverter() {
        super(AgentModelConfig.class);
    }
}
