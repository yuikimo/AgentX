package com.example.agentx.infrastructure.llm.protocol.enums;

import com.example.agentx.infrastructure.exception.BusinessException;

public enum ProviderProtocol {

    OpenAI;

    public static ProviderProtocol fromCode(String code) {
        for (ProviderProtocol protocol : values()) {
            if (protocol.name().equals(code)) {
                return protocol;
            }
        }
        throw new BusinessException("Unknown model type code: " + code);
    }
}
