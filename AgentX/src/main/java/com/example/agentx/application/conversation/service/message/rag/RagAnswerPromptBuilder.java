package com.example.agentx.application.conversation.service.message.rag;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import com.example.agentx.application.conversation.util.ConversationPromptContextUtils;
import com.example.agentx.application.rag.dto.DocumentUnitDTO;
import com.example.agentx.domain.agent.model.LLMModelConfig;
import com.example.agentx.domain.conversation.model.MessageEntity;
import com.example.agentx.domain.prompt.PromptXmlUtils;
import com.example.agentx.domain.prompt.RagPromptTemplates;
import com.example.agentx.domain.rag.constant.ConfidenceTier;
import com.example.agentx.domain.token.service.TokenEstimatorService;
import com.example.agentx.infrastructure.llm.config.ProviderConfig;
import com.example.agentx.infrastructure.llm.config.ProviderConfigFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Component
public class RagAnswerPromptBuilder {

    private static final int DEFAULT_CONTEXT_BUDGET_TOKENS = 1200;
    private static final int MIN_CONTEXT_BUDGET_TOKENS = 240;
    private static final int MAX_CONTEXT_BUDGET_TOKENS = 12000;
    private static final double MAX_CONTEXT_WINDOW_RATIO = 0.25;
    private static final int MIN_DOC_BUDGET_TOKENS = 120;
    private static final int MAX_DOC_BUDGET_TOKENS = 500;
    private static final int MIN_OUTPUT_RESERVE_TOKENS = 512;
    private static final double OUTPUT_RESERVE_RATIO = 0.2;
    private static final int MIN_AVAILABLE_CONTEXT_BUDGET = 120;

    private final TokenEstimatorService tokenEstimatorService;
    private final ProviderConfigFactory providerConfigFactory;

    public RagAnswerPromptBuilder(TokenEstimatorService tokenEstimatorService, ProviderConfigFactory providerConfigFactory) {
        this.tokenEstimatorService = tokenEstimatorService;
        this.providerConfigFactory = providerConfigFactory;
    }

    public String buildUserPrompt(String question, List<DocumentUnitDTO> documents, RagChatContext ragContext,
            RagRetrievalResult retrievalResult) {
        return RagPromptTemplates.buildRagAnswerUserPrompt(buildContextXml(documents, ragContext),
                PromptXmlUtils.escapeXml(StringUtils.defaultString(question).trim()),
                buildRetrievalFocusSection(question, retrievalResult));
    }

    private String buildContextXml(List<DocumentUnitDTO> documents, RagChatContext ragContext) {
        if (documents == null || documents.isEmpty()) {
            return "<context></context>";
        }

        int contextBudgetTokens = resolveContextBudgetTokens(ragContext);
        int consumedTokens = estimateMessageBodyTokens(ragContext, "<context></context>");
        int includedDocCount = 0;

        StringBuilder contextXml = new StringBuilder("<context>\n");
        List<DocumentUnitDTO> sortedDocuments = normalizeDocumentsForPrompt(documents);

        for (int index = 0; index < sortedDocuments.size(); index++) {
            DocumentUnitDTO doc = sortedDocuments.get(index);
            int remainingTokens = contextBudgetTokens - consumedTokens;
            int docsLeft = sortedDocuments.size() - index;
            if (remainingTokens <= MIN_DOC_BUDGET_TOKENS / 2) {
                break;
            }

            int perDocBudget = Math.min(MAX_DOC_BUDGET_TOKENS,
                    Math.max(MIN_DOC_BUDGET_TOKENS, remainingTokens / Math.max(1, docsLeft)));
            String content = clipTextToTokenBudget(ragContext, doc.getContent(), perDocBudget);
            if (StringUtils.isBlank(content)) {
                continue;
            }

            boolean truncated = !StringUtils.equals(StringUtils.defaultString(doc.getContent()).trim(), content);
            String docXml = buildDocXml(doc, content, truncated);
            int docTokens = estimateMessageBodyTokens(ragContext, docXml);
            if (consumedTokens + docTokens > contextBudgetTokens && includedDocCount > 0) {
                break;
            }

            contextXml.append(docXml).append("\n");
            consumedTokens += docTokens;
            includedDocCount++;
        }

        int omittedCount = Math.max(0, documents.size() - includedDocCount);
        if (omittedCount > 0) {
            contextXml.append("<omitted count=\"").append(omittedCount)
                    .append("\">其余相关片段因上下文预算限制被省略</omitted>\n");
        }
        contextXml.append("</context>");
        return contextXml.toString();
    }

    private String buildDocXml(DocumentUnitDTO doc, String content, boolean truncated) {
        StringBuilder docXml = new StringBuilder();
        docXml.append("<doc");
        appendXmlAttribute(docXml, "id", resolveCitationId(doc));
        appendXmlAttribute(docXml, "filename", doc.getFilename());
        if (doc.getPage() != null) {
            appendXmlAttribute(docXml, "page", String.valueOf(doc.getPage()));
        }
        if (truncated) {
            appendXmlAttribute(docXml, "truncated", "true");
        }
        docXml.append(">\n").append(PromptXmlUtils.escapeXml(content)).append("\n</doc>");
        return docXml.toString();
    }

    private void appendXmlAttribute(StringBuilder builder, String name, String value) {
        if (StringUtils.isBlank(value)) {
            return;
        }
        builder.append(" ").append(name).append("=\"").append(PromptXmlUtils.escapeXml(value)).append("\"");
    }

    private int resolveContextBudgetTokens(RagChatContext ragContext) {
        LLMModelConfig llmModelConfig = ragContext != null ? ragContext.getLlmModelConfig() : null;
        if (llmModelConfig == null || llmModelConfig.getMaxTokens() == null) {
            return DEFAULT_CONTEXT_BUDGET_TOKENS;
        }
        int maxTokens = llmModelConfig.getMaxTokens();
        double reserveRatio = llmModelConfig.getReserveRatio() != null ? llmModelConfig.getReserveRatio() : 0.25;
        int availableTokens = maxTokens - (int) Math.floor(maxTokens * reserveRatio);
        int systemTokens = estimateSystemPromptTokens(ragContext) + estimatePromptAssemblyTokens(ragContext);
        int historyTokens = estimateHistoryTokens(ragContext);
        int currentQuestionTokens = estimateCurrentQuestionTokens(ragContext);
        int outputReserveTokens = Math.max(MIN_OUTPUT_RESERVE_TOKENS,
                (int) Math.floor(maxTokens * OUTPUT_RESERVE_RATIO));
        int remainingBudget = availableTokens - systemTokens - historyTokens - currentQuestionTokens - outputReserveTokens;
        if (remainingBudget <= 0) {
            return 0;
        }
        int minBudget = Math.min(MIN_CONTEXT_BUDGET_TOKENS, Math.max(MIN_AVAILABLE_CONTEXT_BUDGET, availableTokens / 8));
        if (remainingBudget < minBudget) {
            return remainingBudget;
        }
        int dynamicMaxBudget = Math.min(MAX_CONTEXT_BUDGET_TOKENS,
                Math.max(DEFAULT_CONTEXT_BUDGET_TOKENS, (int) Math.floor(maxTokens * MAX_CONTEXT_WINDOW_RATIO)));
        return Math.min(dynamicMaxBudget, remainingBudget);
    }

    private String clipTextToTokenBudget(RagChatContext ragContext, String text, int maxTokens) {
        String normalized = StringUtils.defaultString(text).trim();
        if (StringUtils.isBlank(normalized)) {
            return "";
        }
        int fullTokenCount = estimateMessageBodyTokens(ragContext, normalized);
        if (fullTokenCount <= maxTokens) {
            return normalized;
        }
        String boundaryClipped = clipBySemanticBoundary(ragContext, normalized, maxTokens, fullTokenCount);
        if (StringUtils.isNotBlank(boundaryClipped)) {
            return boundaryClipped;
        }
        int approximateLength = estimateClippedLength(normalized, maxTokens, fullTokenCount);
        int left = 1;
        int right = Math.min(normalized.length(), Math.max(1, approximateLength));
        String best = "";
        while (left <= right) {
            int mid = (left + right) >>> 1;
            String candidate = normalized.substring(0, mid).trim();
            if (candidate.isEmpty()) {
                left = mid + 1;
                continue;
            }
            int tokenCount = estimateMessageBodyTokens(ragContext, candidate);
            if (tokenCount <= maxTokens) {
                best = candidate;
                left = mid + 1;
            } else {
                right = mid - 1;
            }
        }
        if (StringUtils.isNotBlank(best) && best.length() < normalized.length()) {
            int expandLeft = best.length() + 1;
            int expandRight = Math.min(normalized.length(), Math.max(best.length() + 1, approximateLength + 120));
            while (expandLeft <= expandRight) {
                int mid = (expandLeft + expandRight) >>> 1;
                String candidate = normalized.substring(0, mid).trim();
                if (candidate.isEmpty()) {
                    expandLeft = mid + 1;
                    continue;
                }
                int tokenCount = estimateMessageBodyTokens(ragContext, candidate);
                if (tokenCount <= maxTokens) {
                    best = candidate;
                    expandLeft = mid + 1;
                } else {
                    expandRight = mid - 1;
                }
            }
        }
        return StringUtils.isNotBlank(best) ? best : normalized.substring(0, 1);
    }

    private int estimateClippedLength(String text, int maxTokens, int fullTokenCount) {
        if (StringUtils.isBlank(text) || fullTokenCount <= 0 || maxTokens <= 0) {
            return 1;
        }
        double ratio = Math.min(1.0d, (double) maxTokens / fullTokenCount);
        int estimated = (int) Math.ceil(text.length() * ratio * 1.12d);
        return Math.max(1, Math.min(text.length(), estimated));
    }

    private int estimateMessageBodyTokens(RagChatContext ragContext, String content) {
        return tokenEstimatorService.estimateTextTokenCount(content, buildProviderConfig(ragContext));
    }

    private ProviderConfig buildProviderConfig(RagChatContext ragContext) {
        if (ragContext == null) {
            return null;
        }
        ProviderConfig cached = ragContext.getResolvedProviderConfig();
        if (cached != null) {
            return cached;
        }
        ProviderConfig resolved = providerConfigFactory.fromChatContext(ragContext);
        ragContext.setResolvedProviderConfig(resolved);
        return resolved;
    }

    private int estimateSystemPromptTokens(RagChatContext ragContext) {
        if (ragContext == null || ragContext.getAgent() == null) {
            return 0;
        }
        return estimateMessageBodyTokens(ragContext, ragContext.getAgent().getSystemPrompt());
    }

    private int estimateHistoryTokens(RagChatContext ragContext) {
        if (ragContext == null || ragContext.getMessageHistory() == null) {
            return 0;
        }
        int total = 0;
        for (MessageEntity historyMessage : ragContext.getMessageHistory()) {
            if (historyMessage == null) {
                continue;
            }
            Integer bodyTokens = historyMessage.getBodyTokenCount();
            total += bodyTokens != null && bodyTokens > 0
                    ? bodyTokens
                    : estimateMessageBodyTokens(ragContext, historyMessage.getContent());
        }
        return total;
    }

    private int estimateCurrentQuestionTokens(RagChatContext ragContext) {
        if (ragContext == null) {
            return 0;
        }
        String retrievalFocusSection = ragContext.isRewrittenQuery()
                ? buildRetrievalFocusSection(ragContext.getUserMessage(), buildSyntheticRetrievalResult(ragContext))
                : "";
        String userPromptShell = RagPromptTemplates.buildRagAnswerUserPrompt("<context></context>",
                PromptXmlUtils.escapeXml(StringUtils.defaultString(ragContext.getUserMessage()).trim()),
                retrievalFocusSection);
        return estimateMessageBodyTokens(ragContext, userPromptShell);
    }

    private RagRetrievalResult buildSyntheticRetrievalResult(RagChatContext ragContext) {
        RagRetrievalResult retrievalResult = new RagRetrievalResult(List.of(), "", true);
        retrievalResult.setEffectiveQuestion(ragContext.getRetrievalQuery());
        retrievalResult.setRewriteApplied(ragContext.isRewrittenQuery());
        return retrievalResult;
    }

    private int estimatePromptAssemblyTokens(RagChatContext ragContext) {
        if (ragContext == null) {
            return 0;
        }
        int total = 0;
        List<String> stableSections = ragContext.getPromptStableSystemSections();
        List<String> dynamicSections = ragContext.getPromptDynamicSystemSections();
        if (stableSections != null) {
            for (String section : stableSections) {
                total += estimateMessageBodyTokens(ragContext, section);
            }
        }
        if (dynamicSections != null) {
            for (String section : dynamicSections) {
                total += estimateMessageBodyTokens(ragContext, section);
            }
        }
        return total;
    }

    private String buildRetrievalFocusSection(String question, RagRetrievalResult retrievalResult) {
        if (retrievalResult == null || !retrievalResult.isRewriteApplied()) {
            return "";
        }
        String effectiveQuestion = ConversationPromptContextUtils.abbreviatePromptText(retrievalResult.getEffectiveQuestion(),
                180);
        String originalQuestion = ConversationPromptContextUtils.abbreviatePromptText(question, 180);
        if (StringUtils.isBlank(effectiveQuestion) || StringUtils.equalsIgnoreCase(effectiveQuestion, originalQuestion)) {
            return "";
        }
        return RagPromptTemplates.wrapRetrievalFocus(PromptXmlUtils.escapeXml(effectiveQuestion));
    }

    private List<DocumentUnitDTO> normalizeDocumentsForPrompt(List<DocumentUnitDTO> documents) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }
        List<DocumentUnitDTO> normalized = new ArrayList<>();
        Set<String> seenIds = new LinkedHashSet<>();
        for (DocumentUnitDTO document : documents.stream().filter(Objects::nonNull).sorted(promptDocumentComparator())
                .toList()) {
            String docId = StringUtils.defaultString(document.getId());
            if (!docId.isBlank() && seenIds.contains(docId)) {
                continue;
            }
            if (!docId.isBlank()) {
                seenIds.add(docId);
            }
            normalized.add(copyDocument(document));
        }
        return normalized;
    }

    private Comparator<DocumentUnitDTO> promptDocumentComparator() {
        return Comparator.comparing((DocumentUnitDTO doc) -> resolveTierPriority(doc.getConfidenceTier())).reversed()
                .thenComparing(DocumentUnitDTO::getSimilarityScore, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(doc -> StringUtils.defaultString(doc.getFileId()))
                .thenComparing(doc -> doc.getPage() == null ? Integer.MAX_VALUE : doc.getPage())
                .thenComparing(doc -> StringUtils.defaultString(doc.getId()));
    }

    private int resolveTierPriority(ConfidenceTier confidenceTier) {
        return confidenceTier == null ? 0 : confidenceTier.getPriority();
    }

    private DocumentUnitDTO copyDocument(DocumentUnitDTO source) {
        DocumentUnitDTO copy = new DocumentUnitDTO();
        copy.setId(source.getId());
        copy.setFileId(source.getFileId());
        copy.setFilename(source.getFilename());
        copy.setPage(source.getPage());
        copy.setChunkIndex(source.getChunkIndex());
        copy.setContent(source.getContent());
        copy.setSimilarityScore(source.getSimilarityScore());
        copy.setConfidenceTier(source.getConfidenceTier());
        copy.setIsOcr(source.getIsOcr());
        copy.setIsVector(source.getIsVector());
        copy.setCreatedAt(source.getCreatedAt());
        copy.setUpdatedAt(source.getUpdatedAt());
        copy.setCitationId(source.getCitationId());
        return copy;
    }

    private String resolveCitationId(DocumentUnitDTO doc) {
        if (doc == null) {
            return "";
        }
        return StringUtils.isNotBlank(doc.getCitationId()) ? doc.getCitationId() : doc.getId();
    }

    private String clipBySemanticBoundary(RagChatContext ragContext, String normalized, int maxTokens, int fullTokenCount) {
        List<String> segments = splitSemanticSegments(normalized);
        if (segments.isEmpty()) {
            return "";
        }
        int estimatedLength = estimateClippedLength(normalized, maxTokens, fullTokenCount);
        StringBuilder builder = new StringBuilder();
        String best = "";
        for (String segment : segments) {
            if (StringUtils.isBlank(segment)) {
                continue;
            }
            String candidate = builder.length() == 0 ? segment.trim() : builder + segment;
            if (candidate.length() > estimatedLength + 160) {
                break;
            }
            if (estimateMessageBodyTokens(ragContext, candidate) > maxTokens) {
                break;
            }
            builder.setLength(0);
            builder.append(candidate);
            best = candidate.trim();
        }
        return best;
    }

    private List<String> splitSemanticSegments(String normalized) {
        List<String> segments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int index = 0; index < normalized.length(); index++) {
            char ch = normalized.charAt(index);
            current.append(ch);
            if (isSentenceBoundary(ch)) {
                segments.add(current.toString());
                current.setLength(0);
            }
        }
        if (current.length() > 0) {
            segments.add(current.toString());
        }
        return segments;
    }

    private boolean isSentenceBoundary(char ch) {
        return ch == '\n' || ch == '。' || ch == '！' || ch == '？' || ch == '.' || ch == '!' || ch == '?'
                || ch == ';' || ch == '；';
    }
}
