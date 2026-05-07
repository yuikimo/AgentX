package com.example.agentx.infrastructure.converter;

import java.util.List;

/** 字符串列表JSON转换器 */
public class ListStringConverter extends JsonToStringConverter<List<String>> {

    public ListStringConverter() {
        super((Class<List<String>>) (Class<?>) List.class);
    }
}
