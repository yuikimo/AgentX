package com.example.agentx.infrastructure.mq.model;

import org.springframework.amqp.core.ExchangeTypes;

/**
 * 广播MQ消息发送事件
 */
public abstract class MQFanoutSendEventModel<T> extends MQSendEventModel<T> {

    private String description = "base info cud event";

    public MQFanoutSendEventModel(T data) {
        super(data);
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    @Override
    public String exchangeType() {
        return ExchangeTypes.FANOUT;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public String exchangeName() {
        return "fanout.plss.record.base.info.exchange";
    }

    @Override
    public String routeKey() {
        return "fanout.record.base.info";
    }

}
