package com.example.agentx.infrastructure.mq.rabbit;

import com.example.agentx.infrastructure.mq.core.MessageEnvelope;
import com.example.agentx.infrastructure.mq.core.MessageHeaders;
import com.example.agentx.infrastructure.mq.core.MessagePublisher;
import com.example.agentx.infrastructure.mq.core.MessageRoute;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.BuiltinExchangeType;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * Direct RabbitMQ publisher using the raw client API.
 */
public final class RabbitDirectPublisher implements MessagePublisher {

    private static final Logger log = LoggerFactory.getLogger(RabbitDirectPublisher.class);

    private final Connection connection;

    public RabbitDirectPublisher(Connection connection) {
        this.connection = connection;
    }

    @Override
    public void publish(MessageRoute route, MessageEnvelope<?> envelope) {
        publish(route, envelope, null);
    }

    @Override
    public void publish(MessageRoute route, MessageEnvelope<?> envelope, Long ttlMillis) {
        try (Channel channel = connection.createChannel()) {
            // idempotent declare
            BuiltinExchangeType type = BuiltinExchangeType.valueOf(route.getType().toUpperCase());
            channel.exchangeDeclare(route.getExchange(), type, true);
            channel.queueDeclare(route.getQueue(), true, false, false, null);
            channel.queueBind(route.getQueue(), route.getExchange(), route.getRoutingKey());

            Map<String, Object> headers = new HashMap<>();
            if (envelope.getTraceId() != null) {
                headers.put(MessageHeaders.TRACE_ID, envelope.getTraceId());
            }

            AMQP.BasicProperties.Builder builder = new AMQP.BasicProperties.Builder()
                    .contentType("application/json")
                    .deliveryMode(2)
                    .headers(headers);
            if (ttlMillis != null && ttlMillis > 0) {
                builder.expiration(Long.toString(ttlMillis));
            }

            String body = envelope.toJson();
            channel.basicPublish(route.getExchange(), route.getRoutingKey(), builder.build(),
                    body.getBytes(StandardCharsets.UTF_8));
            log.debug("Published message to {}:{} -> {}", route.getExchange(), route.getRoutingKey(), route.getQueue());
        } catch (IOException | TimeoutException e) {
            throw new IllegalStateException("Failed to publish message", e);
        }
    }
}
