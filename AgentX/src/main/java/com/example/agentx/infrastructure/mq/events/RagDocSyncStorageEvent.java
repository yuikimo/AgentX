package com.example.agentx.infrastructure.mq.events;

import com.example.agentx.infrastructure.mq.core.MessageRoute;

/** Route constants for vector storage messages. */
public final class RagDocSyncStorageEvent {

    private RagDocSyncStorageEvent() {
    }

    public static final String EXCHANGE_NAME = "rag.doc.task.syncStorage.exchange10";
    public static final String QUEUE_NAME = "rag.doc.task.syncStorage.queue10";
    public static final String ROUTE_KEY = "rag.doc.task.syncStorage10";
    public static final String DLQ_EXCHANGE_NAME = "rag.doc.task.syncStorage.dlq.exchange10";
    public static final String DLQ_QUEUE_NAME = "rag.doc.task.syncStorage.dlq.queue10";
    public static final String DLQ_ROUTE_KEY = "rag.doc.task.syncStorage.dlq10";

    public static MessageRoute route() {
        return MessageRoute.topic(EXCHANGE_NAME, ROUTE_KEY, QUEUE_NAME);
    }

    public static MessageRoute deadLetterRoute() {
        return MessageRoute.topic(DLQ_EXCHANGE_NAME, DLQ_ROUTE_KEY, DLQ_QUEUE_NAME);
    }
}
