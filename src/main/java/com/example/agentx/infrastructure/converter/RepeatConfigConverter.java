package com.example.agentx.infrastructure.converter;

import com.example.agentx.domain.scheduledtask.model.RepeatConfig;
import org.apache.ibatis.type.MappedTypes;

/**
 * 重复配置转换器
 */
@MappedTypes(RepeatConfig.class)
public class RepeatConfigConverter extends JsonToStringConverter<RepeatConfig> {

    public RepeatConfigConverter() {
        super(RepeatConfig.class);
    }
}