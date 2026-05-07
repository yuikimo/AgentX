package com.example.agentx.infrastructure.mq.events;

import com.example.agentx.infrastructure.mq.core.MessageRoute;

/** Route constants for OCR processing messages. */
public final class RagDocSyncOcrEvent {

    private RagDocSyncOcrEvent() {
    }

    public static final String EXCHANGE_NAME = "rag.doc.task.syncOcr.exchange-10";
    public static final String QUEUE_NAME = "rag.doc.task.syncOcr.queue-10";
    public static final String ROUTE_KEY = "rag.doc.task.syncOcr-10";
    public static final String DLQ_EXCHANGE_NAME = "rag.doc.task.syncOcr.dlq.exchange-10";
    public static final String DLQ_QUEUE_NAME = "rag.doc.task.syncOcr.dlq.queue-10";
    public static final String DLQ_ROUTE_KEY = "rag.doc.task.syncOcr.dlq-10";

    public static MessageRoute route() {
        return MessageRoute.topic(EXCHANGE_NAME, ROUTE_KEY, QUEUE_NAME);
    }

    public static MessageRoute deadLetterRoute() {
        return MessageRoute.topic(DLQ_EXCHANGE_NAME, DLQ_ROUTE_KEY, DLQ_QUEUE_NAME);
    }
}
