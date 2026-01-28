package com.example.agentx.infrastructure.converter;

import org.apache.ibatis.type.MappedTypes;

import java.util.List;

/**
 * 模型配置转换器
 */
@MappedTypes(List.class)
public class ListConverter extends JsonToStringConverter<List> {

    public ListConverter() {
        super(List.class);
    }
}
