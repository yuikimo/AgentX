package com.example.agentx.infrastructure.converter;

import org.apache.ibatis.type.MappedTypes;

import java.util.ArrayList;

/**
 * List JSON转换器
 */
@MappedTypes(ArrayList.class)
public class ListConverter extends JsonToStringConverter<ArrayList> {

    public ListConverter() {
        super(ArrayList.class);
    }
}