package com.example.agentx.domain.memory.model;

/** 记忆类型 */
public enum MemoryType {
    PROFILE, TASK, FACT, EPISODIC;

    public static MemoryType ofNullable(String name) {
        if (name == null) {
            return null;
        }
        try {
            return MemoryType.valueOf(name.trim().toUpperCase());
        } catch (Exception ignore) {
            return null;
        }
    }

    public static MemoryType safeOf(String name) {
        MemoryType memoryType = ofNullable(name);
        return memoryType == null ? FACT : memoryType;
    }
}
