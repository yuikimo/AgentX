package com.example.agentx.domain.prompt;

import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Prompt 上下文构建器，按固定顺序组装 system prompt 片段 */
public class PromptContextBuilder {

    private final List<PromptSection> personaSections = new ArrayList<>();
    private final List<PromptSection> capabilitiesSections = new ArrayList<>();
    private final List<PromptSection> memorySections = new ArrayList<>();
    private final List<PromptSection> ragSections = new ArrayList<>();
    private final List<PromptSection> constraintSections = new ArrayList<>();
    private final List<PromptSection> supplementSections = new ArrayList<>();

    public PromptContextBuilder persona(String section) {
        return persona(PromptSection.of(PromptSectionType.PERSONA, section));
    }

    public PromptContextBuilder persona(PromptSection section) {
        addSection(personaSections, section);
        return this;
    }

    public PromptContextBuilder capabilities(String section) {
        return capabilities(PromptSection.of(PromptSectionType.CAPABILITIES, section));
    }

    public PromptContextBuilder capabilities(PromptSection section) {
        addSection(capabilitiesSections, section);
        return this;
    }

    public PromptContextBuilder memory(String section) {
        return memory(PromptSection.of(PromptSectionType.MEMORY, section));
    }

    public PromptContextBuilder memory(PromptSection section) {
        addSection(memorySections, section);
        return this;
    }

    public PromptContextBuilder rag(String section) {
        return rag(PromptSection.of(PromptSectionType.RAG, section));
    }

    public PromptContextBuilder rag(PromptSection section) {
        addSection(ragSections, section);
        return this;
    }

    public PromptContextBuilder constraints(String section) {
        return constraints(PromptSection.of(PromptSectionType.CONSTRAINTS, section));
    }

    public PromptContextBuilder constraints(PromptSection section) {
        addSection(constraintSections, section);
        return this;
    }

    public PromptContextBuilder supplement(String section) {
        return supplement(PromptSection.of(PromptSectionType.SUPPLEMENT, section));
    }

    public PromptContextBuilder supplement(PromptSection section) {
        addSection(supplementSections, section);
        return this;
    }

    public String build() {
        return String.join("\n\n", buildSections());
    }

    public List<String> buildSections() {
        return buildPromptSections().stream().map(PromptSection::content).toList();
    }

    public List<PromptSection> buildPromptSections() {
        List<PromptSection> orderedSections = new ArrayList<>();
        orderedSections.addAll(personaSections);
        orderedSections.addAll(capabilitiesSections);
        orderedSections.addAll(memorySections);
        orderedSections.addAll(ragSections);
        orderedSections.addAll(supplementSections);
        orderedSections.addAll(constraintSections);
        return orderedSections;
    }

    private void addSection(List<PromptSection> target, PromptSection section) {
        if (section == null || StringUtils.isBlank(section.content())) {
            return;
        }
        target.add(section.withContent(section.content().trim()));
    }

    public enum PromptSectionType {
        PERSONA,
        CONTEXT_USAGE_POLICY,
        TOOL_POLICY,
        TOOL_CATALOG,
        PRESET_TOOLS,
        STABLE_MEMORY,
        SUMMARY,
        DYNAMIC_MEMORY,
        RECENT_TOOL_CONTEXT,
        TOOL_AVAILABILITY_NOTICE,
        CAPABILITIES,
        MEMORY,
        RAG,
        SUPPLEMENT,
        CONSTRAINTS
    }

    public record PromptSection(PromptSectionType type, String content, int priority, int minTokens, int maxTokens,
            List<PromptSectionType> dependsOn) {
        private static final int DEFAULT_MIN_TOKENS = 64;

        public static PromptSection of(PromptSectionType type, String content) {
            return new PromptSection(type, content, 0, DEFAULT_MIN_TOKENS, Integer.MAX_VALUE,
                    Collections.emptyList());
        }

        public PromptSection(PromptSectionType type, String content, int priority, int minTokens, int maxTokens) {
            this(type, content, priority, minTokens, maxTokens, Collections.emptyList());
        }

        public PromptSection withContent(String updatedContent) {
            return new PromptSection(type, updatedContent, priority, minTokens, maxTokens, dependsOn);
        }
    }
}
