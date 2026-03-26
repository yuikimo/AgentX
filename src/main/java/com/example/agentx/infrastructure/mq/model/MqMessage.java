package com.example.agentx.infrastructure.mq.model;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

import com.alibaba.fastjson2.JSONObject;
import com.example.agentx.infrastructure.mq.enums.EventType;

public class MqMessage<T> implements Serializable {

    @Serial
    private static final long serialVersionUID = -4017535676119878318L;

    /**
     * 链路id
     */
    private String traceId;
    /**
     * 描述
     */
    private String description;
    /**
     * 操作的数据对象
     */
    private T data;
    /**
     * 当前发送时间戳
     */
    private Long timestamp;
    /**
     * 事件类型
     */
    private List<EventType> eventTypes;

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }

    public Long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Long timestamp) {
        this.timestamp = timestamp;
    }

    public List<EventType> getEventTypes() {
        return eventTypes;
    }

    public void setEventTypes(List<EventType> eventTypes) {
        this.eventTypes = eventTypes;
    }

    @Override
    public String toString() {
        return JSONObject.toJSONString(this);
    }

}
