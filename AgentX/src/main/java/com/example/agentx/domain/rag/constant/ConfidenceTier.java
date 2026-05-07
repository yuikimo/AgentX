package com.example.agentx.domain.rag.constant;

/** 检索结果置信度等级 */
public enum ConfidenceTier {

    HIGH(3),
    LOW(2),
    FALLBACK(1);

    private final int priority;

    ConfidenceTier(int priority) {
        this.priority = priority;
    }

    public int getPriority() {
        return priority;
    }

    public static ConfidenceTier max(ConfidenceTier left, ConfidenceTier right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left.priority >= right.priority ? left : right;
    }
}
