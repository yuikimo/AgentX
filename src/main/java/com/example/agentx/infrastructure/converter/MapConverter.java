package com.example.agentx.infrastructure.converter;

import java.util.Map;

/** Map对象JSON转换器 */
public class MapConverter extends JsonToStringConverter<Map> {

    public MapConverter() {
        super(Map.class);
    }
}
