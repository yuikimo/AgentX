package com.example.agentx.infrastructure.converter;

import com.example.agentx.domain.tool.model.config.ToolDefinition;
import org.apache.ibatis.type.MappedTypes;

import java.util.List;

/**
 * 工具定义列表JSON转换器
 */
@MappedTypes(List.class)
public class ToolDefinitionListConverter extends JsonToStringConverter<List<ToolDefinition>> {

    public ToolDefinitionListConverter() {
        super((Class<List<ToolDefinition>>) (Class<?>) List.class);
    }
}