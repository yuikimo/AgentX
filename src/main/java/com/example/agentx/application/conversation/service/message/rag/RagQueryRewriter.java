package com.example.agentx.application.conversation.service.message.rag;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;
import com.example.agentx.application.conversation.service.handler.context.ChatContext;
import com.example.agentx.application.conversation.util.ConversationPromptContextUtils;
import com.example.agentx.domain.conversation.model.ConversationAttachment;
import com.example.agentx.domain.conversation.constant.Role;
import com.example.agentx.domain.conversation.model.MessageEntity;
import com.example.agentx.domain.prompt.PromptXmlUtils;
import com.example.agentx.domain.prompt.RagPromptTemplates;
import com.example.agentx.domain.rag.config.RagProperties;
import com.example.agentx.infrastructure.llm.LLMServiceFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.time.Duration;
import java.util.regex.Pattern;

@Component
public class RagQueryRewriter {

    private static final Logger logger = LoggerFactory.getLogger(RagQueryRewriter.class);

    private static final int MAX_REWRITE_HISTORY_TURNS = 6;
    private static final int MAX_REWRITE_TURN_LENGTH = 160;
    private static final int MAX_REWRITE_QUESTION_LENGTH = 120;
    private static final Pattern EN_REFERENCE_PATTERN = Pattern
            .compile("\\b(it|this|that|those|them|above|previous|earlier|continue|same|that one)\\b");
    private static final Pattern EN_FOLLOW_UP_CUE_PATTERN = Pattern.compile(
            "\\b(then|next|also|further|in that case|based on (that|above)|elaborate|drill down|continue|same)\\b");
    private static final Pattern ZH_REFERENCE_PATTERN = Pattern
            .compile(".*(这|那|它|他|她|他们|它们|上述|前面|之前|刚才|继续|再来|再说|同样|这个|那个|其|此|该|上一步|上面|下面|如上|这些|那些|上文|下文).*");
    private static final Pattern ZH_FOLLOW_UP_CUE_PATTERN = Pattern
            .compile(".*(那么|然后|接下来|进一步|另外|此外|详细|具体|展开|细说|补充|延伸|例子|举例|对比).*");
    private static final AtomicLong REWRITE_TIMEOUT_COUNTER = new AtomicLong();
    private static final AtomicLong REWRITE_FAILURE_COUNTER = new AtomicLong();

    private final LLMServiceFactory llmServiceFactory;
    private final RagProperties ragProperties;
    private final TaskExecutor rewriteTaskExecutor;

    public RagQueryRewriter(LLMServiceFactory llmServiceFactory, RagProperties ragProperties,
            @Qualifier("ragSearchGroupTaskExecutor") TaskExecutor rewriteTaskExecutor) {
        this.llmServiceFactory = llmServiceFactory;
        this.ragProperties = ragProperties;
        this.rewriteTaskExecutor = rewriteTaskExecutor;
    }

    public boolean shouldRewriteQuestion(RagChatContext ragContext) {
        return shouldRewriteQuestion((ChatContext) ragContext);
    }

    public boolean shouldRewriteQuestion(ChatContext chatContext) {
        String question = StringUtils.defaultString(chatContext != null ? chatContext.getUserMessage() : null).trim();
        if (!shouldRewriteQuestion(question)) {
            return false;
        }
        if (shouldSkipRewriteForAttachments(chatContext)) {
            return false;
        }
        if (question.length() > MAX_REWRITE_QUESTION_LENGTH) {
            return false;
        }
        String historyContext = buildRewriteHistoryContext(chatContext);
        if (StringUtils.isBlank(historyContext)) {
            return false;
        }
        return isLikelyFollowUpQuestion(question, chatContext);
    }

    public boolean shouldRewriteQuestion(String question) {
        String normalized = StringUtils.defaultString(question).trim();
        if (StringUtils.isBlank(normalized)) {
            return false;
        }
        if (normalized.length() > MAX_REWRITE_QUESTION_LENGTH) {
            return false;
        }
        return isLikelyFollowUpQuestion(normalized, null);
    }

    public String rewriteQuestion(RagChatContext ragContext) {
        return rewriteQuestion((ChatContext) ragContext);
    }

    public String rewriteQuestion(ChatContext chatContext) {
        return rewriteQuestionWithStatus(chatContext).getEffectiveQuestion();
    }

    public RewriteExecutionResult rewriteQuestionWithStatus(RagChatContext ragContext) {
        return rewriteQuestionWithStatus((ChatContext) ragContext);
    }

    public RewriteExecutionResult rewriteQuestionWithStatus(ChatContext chatContext) {
        String originalQuestion = StringUtils.defaultString(chatContext != null ? chatContext.getUserMessage() : null)
                .trim();
        return awaitRewriteQuestion(rewriteQuestionWithStatusAsync(chatContext), originalQuestion,
                Math.max(300L, ragProperties.getQueryRewrite().getTimeoutMs()));
    }

    public CompletableFuture<RewriteExecutionResult> rewriteQuestionWithStatusAsync(RagChatContext ragContext) {
        return rewriteQuestionWithStatusAsync((ChatContext) ragContext);
    }

    public CompletableFuture<RewriteExecutionResult> rewriteQuestionWithStatusAsync(ChatContext chatContext) {
        String originalQuestion = StringUtils.defaultString(chatContext != null ? chatContext.getUserMessage() : null)
                .trim();
        if (shouldSkipRewriteForAttachments(chatContext)) {
            return CompletableFuture.completedFuture(
                    RewriteExecutionResult.skipped(originalQuestion, "attachments_present", 0L));
        }
        String historyContext = buildRewriteHistoryContext(chatContext);
        if (StringUtils.isBlank(originalQuestion) || StringUtils.isBlank(historyContext)
                || !shouldRewriteQuestion(originalQuestion)) {
            return CompletableFuture.completedFuture(
                    RewriteExecutionResult.skipped(originalQuestion, "rewrite_not_needed", 0L));
        }

        long startTime = System.currentTimeMillis();
        return CompletableFuture.supplyAsync(() -> {
            try {
                String effectiveQuestion = rewriteQuestionInternal(chatContext, historyContext, originalQuestion);
                return RewriteExecutionResult.completed(originalQuestion, effectiveQuestion,
                        System.currentTimeMillis() - startTime);
            } catch (Exception e) {
                long failureCount = REWRITE_FAILURE_COUNTER.incrementAndGet();
                long elapsedMs = System.currentTimeMillis() - startTime;
                logger.warn("RAG问题改写失败，回退原问题: sessionId={}, userId={}, err={}",
                        chatContext != null ? chatContext.getSessionId() : null,
                        chatContext != null ? chatContext.getUserId() : null, e.getMessage());
                logger.debug("RAG问题改写失败统计: elapsedMs={}, failureCount={}", elapsedMs, failureCount);
                return RewriteExecutionResult.failed(originalQuestion, elapsedMs,
                        StringUtils.defaultIfBlank(e.getMessage(), "rewrite_failed"));
            }
        }, rewriteTaskExecutor);
    }

    public RewriteExecutionResult awaitRewriteQuestion(CompletableFuture<RewriteExecutionResult> rewriteFuture,
            String originalQuestion, long waitMs) {
        long startTime = System.currentTimeMillis();
        if (rewriteFuture == null) {
            return RewriteExecutionResult.skipped(originalQuestion, "rewrite_not_needed", 0L);
        }
        try {
            return rewriteFuture.get(Math.max(0L, waitMs), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            rewriteFuture.cancel(true);
            long timeoutCount = REWRITE_TIMEOUT_COUNTER.incrementAndGet();
            long elapsedMs = System.currentTimeMillis() - startTime;
            logger.debug("RAG问题改写未在等待窗口内完成，回退原问题: timeoutMs={}, elapsedMs={}, timeoutCount={}",
                    Math.max(0L, waitMs), elapsedMs, timeoutCount);
            return RewriteExecutionResult.timedOut(originalQuestion, elapsedMs);
        } catch (Exception e) {
            rewriteFuture.cancel(true);
            long failureCount = REWRITE_FAILURE_COUNTER.incrementAndGet();
            long elapsedMs = System.currentTimeMillis() - startTime;
            Throwable cause = e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
            logger.warn("RAG问题改写等待失败，回退原问题: err={}", cause.getMessage());
            logger.debug("RAG问题改写失败统计: elapsedMs={}, failureCount={}", elapsedMs, failureCount);
            return RewriteExecutionResult.failed(originalQuestion, elapsedMs,
                    StringUtils.defaultIfBlank(cause.getMessage(), "rewrite_failed"));
        }
    }

    private String rewriteQuestionInternal(ChatContext chatContext, String historyContext, String originalQuestion) {
        ChatModel strandClient = llmServiceFactory.getStrandClient(chatContext.getProvider(), chatContext.getModel(),
                Duration.ofMillis(Math.max(500L, ragProperties.getQueryRewrite().getTimeoutMs())));
        var promptSpec = RagPromptTemplates.buildQueryRewritePromptSpec(historyContext,
                PromptXmlUtils.escapeXml(originalQuestion));
        List<ChatMessage> messages = List.of(new SystemMessage(promptSpec.getSystemPrompt()),
                UserMessage.from(promptSpec.getUserPrompt()));
        ChatResponse response = strandClient.chat(messages);
        String rewrittenText = response != null && response.aiMessage() != null ? response.aiMessage().text() : null;
        return normalizeRewrittenQuestion(rewrittenText, originalQuestion);
    }

    private String buildRewriteHistoryContext(ChatContext chatContext) {
        List<MessageEntity> messageHistory = chatContext == null ? null : chatContext.getMessageHistory();
        return ConversationPromptContextUtils.buildRewriteHistoryContext(messageHistory,
                chatContext == null ? null : chatContext.getContextEntity(), MAX_REWRITE_HISTORY_TURNS,
                MAX_REWRITE_TURN_LENGTH);
    }

    private boolean shouldSkipRewriteForAttachments(ChatContext chatContext) {
        if (!ragProperties.getQueryRewrite().isSkipWhenAttachmentsPresent() || chatContext == null
                || chatContext.getAttachments() == null
                || chatContext.getAttachments().isEmpty()) {
            return false;
        }
        return chatContext.getAttachments().stream().filter(Objects::nonNull)
                .anyMatch(attachment -> attachment.isDocumentLike() || shouldTreatAsContextualAttachment(attachment));
    }

    private boolean shouldTreatAsContextualAttachment(ConversationAttachment attachment) {
        if (attachment == null) {
            return false;
        }
        String summary = StringUtils.defaultString(attachment.getSummary()).trim();
        return StringUtils.isNotBlank(summary) && !attachment.isImage();
    }

    private boolean isLikelyFollowUpQuestion(String question, ChatContext chatContext) {
        String normalized = StringUtils.defaultString(question).trim();
        if (StringUtils.isBlank(normalized)) {
            return false;
        }
        if (normalized.length() <= 24) {
            return true;
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (EN_REFERENCE_PATTERN.matcher(lower).find() || EN_FOLLOW_UP_CUE_PATTERN.matcher(lower).find()) {
            return true;
        }
        if (ZH_REFERENCE_PATTERN.matcher(normalized).matches() || ZH_FOLLOW_UP_CUE_PATTERN.matcher(normalized).matches()) {
            return true;
        }

        String previousUserMessage = findLatestUserMessage(chatContext);
        if (StringUtils.isBlank(previousUserMessage)) {
            return false;
        }

        double overlapRatio = calculateTokenOverlap(normalized, previousUserMessage);
        if (overlapRatio >= 0.18d) {
            return true;
        }
        return overlapRatio >= 0.08d && likelyNeedsContext(normalized);
    }

    private String findLatestUserMessage(ChatContext chatContext) {
        List<MessageEntity> history = chatContext == null ? null : chatContext.getMessageHistory();
        if (history == null || history.isEmpty()) {
            return "";
        }
        for (int i = history.size() - 1; i >= 0; i--) {
            MessageEntity message = history.get(i);
            if (message == null || message.getRole() != Role.USER) {
                continue;
            }
            String content = StringUtils.defaultString(message.getContent()).trim();
            if (StringUtils.isNotBlank(content)) {
                return content;
            }
        }
        return "";
    }

    private double calculateTokenOverlap(String currentQuestion, String previousQuestion) {
        Set<String> currentTokens = extractComparableTokens(currentQuestion);
        Set<String> previousTokens = extractComparableTokens(previousQuestion);
        if (currentTokens.isEmpty() || previousTokens.isEmpty()) {
            return 0.0d;
        }
        int intersection = 0;
        for (String token : currentTokens) {
            if (previousTokens.contains(token)) {
                intersection++;
            }
        }
        int union = currentTokens.size() + previousTokens.size() - intersection;
        if (union <= 0) {
            return 0.0d;
        }
        return intersection * 1.0d / union;
    }

    private Set<String> extractComparableTokens(String text) {
        String normalized = StringUtils.defaultString(text).toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}\\u4E00-\\u9FFF]+", " ").trim();
        if (StringUtils.isBlank(normalized)) {
            return Set.of();
        }
        String[] rawTokens = normalized.split("\\s+");
        Set<String> tokens = new LinkedHashSet<>();
        List<String> cjkFragments = new ArrayList<>();
        for (String rawToken : rawTokens) {
            if (StringUtils.isBlank(rawToken)) {
                continue;
            }
            if (containsCjk(rawToken)) {
                cjkFragments.add(rawToken);
                if (rawToken.length() == 1) {
                    tokens.add(rawToken);
                }
                continue;
            }
            if (rawToken.length() >= 2) {
                tokens.add(rawToken);
            }
        }
        for (String cjk : cjkFragments) {
            if (cjk.length() <= 1) {
                continue;
            }
            for (int i = 0; i < cjk.length() - 1; i++) {
                tokens.add(cjk.substring(i, i + 2));
            }
        }
        return tokens;
    }

    private boolean containsCjk(String token) {
        if (token == null || token.isEmpty()) {
            return false;
        }
        for (int i = 0; i < token.length(); i++) {
            Character.UnicodeScript script = Character.UnicodeScript.of(token.charAt(i));
            if (script == Character.UnicodeScript.HAN || script == Character.UnicodeScript.HIRAGANA
                    || script == Character.UnicodeScript.KATAKANA || script == Character.UnicodeScript.HANGUL) {
                return true;
            }
        }
        return false;
    }

    private boolean likelyNeedsContext(String question) {
        String normalized = StringUtils.defaultString(question).trim();
        if (StringUtils.isBlank(normalized)) {
            return false;
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (lower.startsWith("then ") || lower.startsWith("so ") || lower.startsWith("what about ")
                || lower.startsWith("and ")) {
            return true;
        }
        return normalized.startsWith("那") || normalized.startsWith("那么") || normalized.startsWith("然后")
                || normalized.startsWith("接下来") || normalized.startsWith("另外") || normalized.startsWith("此外")
                || normalized.startsWith("继续");
    }

    private String normalizeRewrittenQuestion(String rewrittenQuestion, String originalQuestion) {
        String normalized = StringUtils.defaultString(rewrittenQuestion).trim();
        if (StringUtils.isBlank(normalized)) {
            return originalQuestion;
        }
        normalized = normalized.replace("```", "").trim();
        if ((normalized.startsWith("\"") && normalized.endsWith("\""))
                || (normalized.startsWith("'") && normalized.endsWith("'"))) {
            normalized = normalized.substring(1, normalized.length() - 1).trim();
        }
        return StringUtils.isNotBlank(normalized) ? normalized : originalQuestion;
    }

    public static final class RewriteExecutionResult {
        private final String originalQuestion;
        private final String effectiveQuestion;
        private final boolean rewriteApplied;
        private final boolean timedOut;
        private final long latencyMs;
        private final String fallbackReason;

        private RewriteExecutionResult(String originalQuestion, String effectiveQuestion, boolean rewriteApplied,
                boolean timedOut, long latencyMs, String fallbackReason) {
            this.originalQuestion = originalQuestion;
            this.effectiveQuestion = effectiveQuestion;
            this.rewriteApplied = rewriteApplied;
            this.timedOut = timedOut;
            this.latencyMs = latencyMs;
            this.fallbackReason = fallbackReason;
        }

        public static RewriteExecutionResult completed(String originalQuestion, String effectiveQuestion, long latencyMs) {
            String normalizedEffectiveQuestion = StringUtils.defaultIfBlank(effectiveQuestion, originalQuestion);
            return new RewriteExecutionResult(originalQuestion, normalizedEffectiveQuestion,
                    !StringUtils.equals(normalizedEffectiveQuestion, originalQuestion), false, latencyMs, null);
        }

        public static RewriteExecutionResult skipped(String originalQuestion, String fallbackReason, long latencyMs) {
            return new RewriteExecutionResult(originalQuestion, originalQuestion, false, false, latencyMs,
                    fallbackReason);
        }

        public static RewriteExecutionResult timedOut(String originalQuestion, long latencyMs) {
            return new RewriteExecutionResult(originalQuestion, originalQuestion, false, true, latencyMs,
                    "rewrite_timeout");
        }

        public static RewriteExecutionResult failed(String originalQuestion, long latencyMs, String fallbackReason) {
            return new RewriteExecutionResult(originalQuestion, originalQuestion, false, false, latencyMs,
                    fallbackReason);
        }

        public String getOriginalQuestion() {
            return originalQuestion;
        }

        public String getEffectiveQuestion() {
            return effectiveQuestion;
        }

        public boolean isRewriteApplied() {
            return rewriteApplied;
        }

        public boolean isTimedOut() {
            return timedOut;
        }

        public long getLatencyMs() {
            return latencyMs;
        }

        public String getFallbackReason() {
            return fallbackReason;
        }
    }
}
