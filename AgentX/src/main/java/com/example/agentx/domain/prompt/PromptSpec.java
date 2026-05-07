package com.example.agentx.domain.prompt;

import org.apache.commons.lang3.StringUtils;

/** 结构化提示词规格 */
public final class PromptSpec {

    private final String systemPrompt;
    private final String userPrompt;
    private final String instructionPrompt;

    private PromptSpec(String systemPrompt, String userPrompt, String instructionPrompt) {
        this.systemPrompt = StringUtils.defaultString(systemPrompt).trim();
        this.userPrompt = StringUtils.defaultString(userPrompt).trim();
        this.instructionPrompt = StringUtils.defaultString(instructionPrompt).trim();
    }

    public static PromptSpec of(String systemPrompt, String userPrompt) {
        return new PromptSpec(systemPrompt, userPrompt, null);
    }

    public static PromptSpec instruction(String instructionPrompt) {
        return new PromptSpec(null, null, instructionPrompt);
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public String getUserPrompt() {
        return userPrompt;
    }

    public String getInstructionPrompt() {
        return instructionPrompt;
    }
}
