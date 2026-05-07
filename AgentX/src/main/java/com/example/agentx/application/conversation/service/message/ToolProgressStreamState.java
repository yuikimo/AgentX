package com.example.agentx.application.conversation.service.message;

import java.util.concurrent.atomic.AtomicBoolean;

/** 流式工具执行期间，用于协调文本分段与工具进度事件。 */
public class ToolProgressStreamState {

    private final AtomicBoolean textSegmentFlushed = new AtomicBoolean(false);

    public boolean markTextSegmentFlushed() {
        return textSegmentFlushed.compareAndSet(false, true);
    }

    public boolean consumeTextSegmentFlushed() {
        return textSegmentFlushed.getAndSet(false);
    }
}
