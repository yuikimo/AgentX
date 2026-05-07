package com.example.agentx.domain.token.service.impl;

import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.example.agentx.domain.conversation.constant.Role;
import com.example.agentx.domain.prompt.ConversationPromptTemplates;
import com.example.agentx.domain.token.model.TokenMessage;
import com.example.agentx.domain.token.model.TokenProcessResult;
import com.example.agentx.domain.token.model.config.TokenOverflowConfig;
import com.example.agentx.domain.shared.enums.TokenOverflowStrategyEnum;
import com.example.agentx.domain.token.service.TokenOverflowStrategy;
import com.example.agentx.infrastructure.llm.ChatResponseTokenUsageUtils;
import com.example.agentx.infrastructure.llm.LLMProviderService;
import com.example.agentx.infrastructure.llm.config.ProviderConfig;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/** 摘要策略Token超限处理实现 将超出阈值的早期消息生成摘要，保留摘要和最新消息 */
public class SummarizeTokenOverflowStrategy implements TokenOverflowStrategy {

    private static final Logger logger = LoggerFactory.getLogger(SummarizeTokenOverflowStrategy.class);
    private static final int DEFAULT_MAX_TOKENS = 4096;
    private static final int DEFAULT_SUMMARY_THRESHOLD_PERCENT = 45;
    private static final int MIN_SUMMARY_THRESHOLD_PERCENT = 30;
    private static final int MAX_SUMMARY_THRESHOLD_PERCENT = 90;
    private static final double DEFAULT_RETAIN_RATIO = 0.2;
    private static final int MIN_RETAINED_MESSAGES = 4;
    private static final long DEFAULT_SUMMARY_WAIT_MS = 2500L;
    private static final long MIN_SUMMARY_WAIT_MS = 1200L;
    private static final int FALLBACK_SUMMARY_MAX_CHARS = 600;
    private static final int FALLBACK_MESSAGE_LIMIT = 8;
    private static final int FALLBACK_EXISTING_LINE_LIMIT = 3;
    private static final int FALLBACK_USER_MESSAGE_LIMIT = 4;
    private static final int FALLBACK_CONTEXT_MESSAGE_LIMIT = 4;

    /** 策略配置 */
    private final TokenOverflowConfig config;

    /** 需要进行摘要的消息 */
    private List<TokenMessage> messagesToSummarize;

    /** 生成的摘要消息对象 */
    private TokenMessage summaryMessage;

    /** 构造函数
     * 
     * @param config 策略配置 */
    public SummarizeTokenOverflowStrategy(TokenOverflowConfig config) {
        this.config = config;
        this.messagesToSummarize = new ArrayList<>();
        this.summaryMessage = null;
    }

    /** 处理消息列表，应用摘要策略 将超过阈值的早期消息替换为一个摘要消息并添加到历史消息中，新摘要会追加到摘要记录中并更新创建时间
     * 
     * @param messages 待处理的消息列表
     * @return 处理后的消息列表（包含摘要消息+保留的消息） */
    @Override
    public TokenProcessResult process(List<TokenMessage> messages, TokenOverflowConfig tokenOverflowConfig) {
        TokenOverflowConfig effectiveConfig = tokenOverflowConfig != null ? tokenOverflowConfig : this.config;
        if (!needsProcessing(messages, effectiveConfig)) {
            TokenProcessResult result = new TokenProcessResult();
            result.setRetainedMessages(messages);
            result.setStrategyName(getName());
            result.setProcessed(false);
            result.setTotalTokens(calculateTotalTokens(messages));
            return result;
        }

        // 按时间排序
        List<TokenMessage> sortedMessages = messages.stream().sorted(Comparator.comparing(TokenMessage::getCreatedAt))
                .collect(Collectors.toList());

        int maxTokens = getMaxTokens(effectiveConfig);
        int retainedTokenTarget = resolveRetainedTokenTarget(maxTokens, effectiveConfig.getReserveRatio());

        List<TokenMessage> retainedMessages = selectRecentMessages(sortedMessages, retainedTokenTarget);
        int summarizeEndIndex = Math.max(0, sortedMessages.size() - retainedMessages.size());
        messagesToSummarize = sortedMessages.subList(0, summarizeEndIndex);
        if (messagesToSummarize.isEmpty()) {
            TokenProcessResult result = new TokenProcessResult();
            result.setRetainedMessages(messages);
            result.setStrategyName(getName());
            result.setProcessed(false);
            result.setTotalTokens(calculateTotalTokens(messages));
            return result;
        }

        // 生成新的摘要消息
        TokenMessage newSummary = this.generateSummary(messagesToSummarize, effectiveConfig, messages);

        List<TokenMessage> outputMessages = new ArrayList<>();
        outputMessages.add(newSummary);
        outputMessages.addAll(retainedMessages);
        outputMessages = trimToMaxTokens(outputMessages, maxTokens);

        // 创建结果对象
        TokenProcessResult result = new TokenProcessResult();
        result.setRetainedMessages(outputMessages);
        result.setSummary(newSummary.getContent());
        result.setStrategyName(getName());
        result.setProcessed(true);
        result.setTotalTokens(calculateTotalTokens(outputMessages));

        return result;
    }

    /** 获取策略名称
     * 
     * @return 策略名称 */
    @Override
    public String getName() {
        return TokenOverflowStrategyEnum.SUMMARIZE.name();
    }

    /** 判断是否需要进行Token超限处理
     * 
     * @param messages 待处理的消息列表
     * @return 是否需要处理 */
    @Override
    public boolean needsProcessing(List<TokenMessage> messages) {
        return needsProcessing(messages, this.config);
    }

    private boolean needsProcessing(List<TokenMessage> messages, TokenOverflowConfig overflowConfig) {
        if (messages == null || messages.isEmpty()) {
            return false;
        }

        int maxTokens = getMaxTokens(overflowConfig);
        int thresholdPercent = resolveThresholdPercent(overflowConfig);
        int triggerTokens = (int) Math.floor(maxTokens * (thresholdPercent / 100.0));
        triggerTokens = Math.max(1, Math.min(maxTokens, triggerTokens));
        return calculateTotalTokens(messages) > triggerTokens;
    }

    /** 获取需要摘要的消息列表（按时间排序） 这是应用层应该使用的方法，用于获取需要进行摘要处理的消息对象
     * 
     * @return 需要摘要的消息列表（按时间从旧到新排序） */
    public List<TokenMessage> getMessagesToSummarize() {
        return messagesToSummarize;
    }

    /** 生成摘要内容并更新摘要消息记录 */
    private TokenMessage generateSummary(List<TokenMessage> messages, TokenOverflowConfig tokenOverflowConfig,
            List<TokenMessage> historyMessages) {

        ProviderConfig providerConfig = tokenOverflowConfig.getProviderConfig();
        String existingSummary = messages.stream().filter(message -> Role.SUMMARY.name().equals(message.getRole()))
                .map(TokenMessage::getContent).filter(Objects::nonNull).collect(Collectors.joining("\n\n"));
        List<TokenMessage> incrementalMessages = messages.stream()
                .filter(message -> !Role.SUMMARY.name().equals(message.getRole())).collect(Collectors.toList());
        if (incrementalMessages.isEmpty() && !existingSummary.isBlank()) {
            return this.createNewSummaryMessage(existingSummary, existingSummary.length() / 2, historyMessages);
        }

        CompletableFuture<GeneratedSummary> summaryFuture;
        try {
            summaryFuture = CompletableFuture.supplyAsync(
                    () -> callSummaryModel(providerConfig, existingSummary, incrementalMessages),
                    resolveSummaryExecutor(tokenOverflowConfig));
        } catch (RuntimeException e) {
            logger.warn("提交摘要模型任务失败，使用本地降级摘要: {}", e.getMessage());
            return createFallbackSummaryMessage(existingSummary, incrementalMessages, historyMessages);
        }

        long waitMs = resolveSummaryWaitMs(tokenOverflowConfig);
        if (waitMs <= 0L) {
            return createFallbackSummaryMessage(existingSummary, incrementalMessages, historyMessages);
        }

        try {
            GeneratedSummary generatedSummary = summaryFuture.get(waitMs, TimeUnit.MILLISECONDS);
            return this.createNewSummaryMessage(generatedSummary.text(), generatedSummary.outputTokens(), historyMessages);
        } catch (TimeoutException e) {
            summaryFuture.cancel(true);
            logger.warn("摘要模型生成超时，使用本地降级摘要: waitMs={}", waitMs);
            return createFallbackSummaryMessage(existingSummary, incrementalMessages, historyMessages);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            summaryFuture.cancel(true);
            return createFallbackSummaryMessage(existingSummary, incrementalMessages, historyMessages);
        } catch (Exception e) {
            Throwable cause = e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
            logger.warn("摘要模型生成失败，使用本地降级摘要: {}", cause.getMessage());
            return createFallbackSummaryMessage(existingSummary, incrementalMessages, historyMessages);
        }
    }

    private GeneratedSummary callSummaryModel(ProviderConfig providerConfig, String existingSummary,
            List<TokenMessage> incrementalMessages) {
        ChatModel chatLanguageModel = LLMProviderService.getStrand(providerConfig.getProtocol(), providerConfig);
        SystemMessage systemMessage = new SystemMessage("你是一个专业的对话摘要整合器，请严格按照以下要求工作：\n"
                + "1. 只基于输入内容生成客观摘要，不得补充对话中不存在的信息；\n"
                + "2. 如果提供了已有摘要，请将其与新增对话整合成一份新的完整摘要，而不是简单追加；\n"
                + "3. 摘要优先保留当前会话继续推进所需的信息：用户目标、关键约束、重要决策、已完成进展和待解决问题；\n"
                + "4. 对长期稳定的身份背景或回答偏好，仅在它仍直接影响当前会话时才保留，并保持一句话以内，避免与长期记忆职责重复；\n"
                + "5. 不要保留寒暄、口语填充、重复内容，也不要保留一次性工具操作细节、原始命令、文件路径、长日志或敏感信息；\n"
                + "6. 若已有摘要与新增消息表达同一事实，只保留更简洁、更新的表述，保持时间顺序；\n"
                + "7. 输出使用简洁中文，按 3-6 条要点分行，总长度不超过600个中文字符，不要输出 Markdown 代码块；\n"
                + "8. 输出必须以这段文字开头：" + ConversationPromptTemplates.getSummaryPrefix());
        StringBuilder userPrompt = new StringBuilder();
        userPrompt.append("<existing_summary>\n").append(existingSummary).append("\n</existing_summary>\n");
        userPrompt.append("<incremental_messages>\n");
        for (TokenMessage message : incrementalMessages) {
            userPrompt.append("[").append(message.getRole()).append("] ")
                    .append(message.getContent() == null ? "" : message.getContent().trim()).append("\n");
        }
        userPrompt.append("</incremental_messages>");
        UserMessage userMessage = UserMessage.from(userPrompt.toString());
        ChatResponse chatResponse = chatLanguageModel.chat(Arrays.asList(systemMessage, userMessage));
        return new GeneratedSummary(chatResponse.aiMessage().text(), ChatResponseTokenUsageUtils.outputTokenCount(chatResponse));
    }

    private Executor resolveSummaryExecutor(TokenOverflowConfig tokenOverflowConfig) {
        if (tokenOverflowConfig != null && tokenOverflowConfig.getSummaryExecutor() != null) {
            return tokenOverflowConfig.getSummaryExecutor();
        }
        return ForkJoinPool.commonPool();
    }

    private long resolveSummaryWaitMs(TokenOverflowConfig tokenOverflowConfig) {
        if (tokenOverflowConfig == null || tokenOverflowConfig.getSummaryGenerationWaitMs() == null) {
            return DEFAULT_SUMMARY_WAIT_MS;
        }
        long configured = tokenOverflowConfig.getSummaryGenerationWaitMs();
        if (configured <= 0L) {
            return 0L;
        }
        return Math.max(MIN_SUMMARY_WAIT_MS, configured);
    }

    private TokenMessage createFallbackSummaryMessage(String existingSummary, List<TokenMessage> incrementalMessages,
            List<TokenMessage> historyMessages) {
        String fallbackSummary = buildFallbackSummary(existingSummary, incrementalMessages);
        return this.createNewSummaryMessage(fallbackSummary, Math.max(1, fallbackSummary.length() / 2), historyMessages);
    }

    private String buildFallbackSummary(String existingSummary, List<TokenMessage> incrementalMessages) {
        String prefix = ConversationPromptTemplates.getSummaryPrefix();
        String normalizedExisting = stripSummaryPrefix(cleanSummaryText(existingSummary), prefix);
        List<TokenMessage> recentMessages = incrementalMessages == null ? Collections.emptyList()
                : incrementalMessages.stream().filter(Objects::nonNull).skip(Math.max(0,
                        incrementalMessages.size() - FALLBACK_MESSAGE_LIMIT)).collect(Collectors.toList());
        List<String> bullets = new ArrayList<>();

        appendExistingSummaryBullets(bullets, normalizedExisting);
        appendUserFocusBullet(bullets, recentMessages);
        appendConstraintBullet(bullets, recentMessages);
        appendProgressBullet(bullets, recentMessages);
        appendPendingBullet(bullets, recentMessages);

        if (bullets.isEmpty()) {
            bullets.add("- 历史消息已被压缩，但未提取到稳定要点。");
        }
        return buildBoundedFallbackSummary(prefix, bullets);
    }

    private void appendFallbackPart(StringBuilder summary, String part) {
        if (summary.length() > 0 && summary.charAt(summary.length() - 1) != '\n') {
            summary.append('\n');
        }
        summary.append(part);
    }

    private String cleanSummaryText(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\r', ' ').replace('\n', ' ').replaceAll("\\s+", " ").trim();
    }

    private String stripSummaryPrefix(String value, String prefix) {
        if (value == null || prefix == null) {
            return "";
        }
        return value.startsWith(prefix) ? value.substring(prefix.length()).trim() : value;
    }

    private void appendExistingSummaryBullets(List<String> bullets, String existingSummary) {
        if (StringUtils.isBlank(existingSummary)) {
            return;
        }
        List<String> normalizedLines = Arrays.stream(existingSummary.split("\\n+")).map(this::cleanSummaryText)
                .map(this::stripBulletPrefix).filter(StringUtils::isNotBlank).distinct()
                .limit(FALLBACK_EXISTING_LINE_LIMIT).toList();
        if (normalizedLines.isEmpty()) {
            bullets.add("- 已有背景：" + abbreviate(existingSummary, 110));
            return;
        }
        for (String line : normalizedLines) {
            bullets.add("- " + abbreviate(line, 110));
        }
    }

    private void appendUserFocusBullet(List<String> bullets, List<TokenMessage> recentMessages) {
        List<String> focuses = collectRoleContents(recentMessages, Role.USER.name(), FALLBACK_USER_MESSAGE_LIMIT, 72);
        if (focuses.isEmpty()) {
            return;
        }
        bullets.add("- 用户近期关注：" + String.join("；", focuses));
    }

    private void appendConstraintBullet(List<String> bullets, List<TokenMessage> recentMessages) {
        LinkedHashSet<String> constraints = new LinkedHashSet<>();
        for (TokenMessage message : recentMessages) {
            if (message == null || !Role.USER.name().equals(message.getRole())) {
                continue;
            }
            String content = cleanSummaryText(message.getContent());
            if (content.isBlank()) {
                continue;
            }
            if (containsConstraintSignal(content)) {
                constraints.add(abbreviate(content, 84));
            }
            if (constraints.size() >= 2) {
                break;
            }
        }
        if (!constraints.isEmpty()) {
            bullets.add("- 关键要求/限制：" + String.join("；", constraints));
        }
    }

    private void appendProgressBullet(List<String> bullets, List<TokenMessage> recentMessages) {
        List<String> progress = new ArrayList<>();
        for (TokenMessage message : recentMessages) {
            if (message == null || Role.USER.name().equals(message.getRole())) {
                continue;
            }
            String content = cleanSummaryText(message.getContent());
            if (content.isBlank()) {
                continue;
            }
            progress.add(roleLabel(message.getRole()) + "：" + abbreviate(content, 72));
        }
        if (progress.isEmpty()) {
            return;
        }
        int fromIndex = Math.max(0, progress.size() - FALLBACK_CONTEXT_MESSAGE_LIMIT);
        bullets.add("- 最近进展：" + String.join("；", progress.subList(fromIndex, progress.size())));
    }

    private void appendPendingBullet(List<String> bullets, List<TokenMessage> recentMessages) {
        for (int index = recentMessages.size() - 1; index >= 0; index--) {
            TokenMessage message = recentMessages.get(index);
            if (message == null || !Role.USER.name().equals(message.getRole())) {
                continue;
            }
            String content = cleanSummaryText(message.getContent());
            if (content.isBlank()) {
                continue;
            }
            bullets.add("- 当前待处理：" + abbreviate(content, 96));
            return;
        }
    }

    private List<String> collectRoleContents(List<TokenMessage> messages, String role, int limit, int maxCharsPerItem) {
        if (messages == null || messages.isEmpty()) {
            return Collections.emptyList();
        }
        LinkedHashSet<String> collected = new LinkedHashSet<>();
        for (int index = messages.size() - 1; index >= 0 && collected.size() < limit; index--) {
            TokenMessage message = messages.get(index);
            if (message == null || !Objects.equals(role, message.getRole())) {
                continue;
            }
            String content = cleanSummaryText(message.getContent());
            if (content.isBlank()) {
                continue;
            }
            collected.add(abbreviate(content, maxCharsPerItem));
        }
        return collected.stream().toList();
    }

    private boolean containsConstraintSignal(String content) {
        return content.contains("不要") || content.contains("必须") || content.contains("需要")
                || content.contains("请") || content.contains("优先") || content.contains("限制")
                || content.contains("只保留") || content.contains("只需要") || content.contains("不要处理")
                || content.contains("先不") || content.contains("暂不");
    }

    private String buildBoundedFallbackSummary(String prefix, List<String> bullets) {
        StringBuilder summary = new StringBuilder(prefix);
        for (String bullet : bullets) {
            String normalized = cleanSummaryText(bullet).replace(" - ", " ");
            if (StringUtils.isBlank(normalized)) {
                continue;
            }
            String line = normalized.startsWith("- ") ? normalized : "- " + normalized;
            int candidateLength = summary.length() + (summary.length() > prefix.length() ? 1 : 0) + line.length();
            if (candidateLength > FALLBACK_SUMMARY_MAX_CHARS) {
                int remaining = FALLBACK_SUMMARY_MAX_CHARS - summary.length() - (summary.length() > prefix.length() ? 1 : 0);
                if (remaining > 8) {
                    appendFallbackPart(summary, abbreviate(line, remaining));
                }
                break;
            }
            appendFallbackPart(summary, line);
        }
        if (summary.length() == prefix.length()) {
            appendFallbackPart(summary, "- 历史消息已被压缩，但未提取到稳定要点。");
        }
        return abbreviate(summary.toString(), FALLBACK_SUMMARY_MAX_CHARS);
    }

    private String stripBulletPrefix(String value) {
        if (StringUtils.isBlank(value)) {
            return "";
        }
        int index = 0;
        while (index < value.length()) {
            char current = value.charAt(index);
            if (Character.isWhitespace(current) || current == '-' || current == '*' || current == '•'
                    || current == '.' || current == ')' || Character.isDigit(current)) {
                index++;
                continue;
            }
            break;
        }
        return index >= value.length() ? "" : value.substring(index).trim();
    }

    private String roleLabel(String role) {
        if (Role.USER.name().equals(role)) {
            return "用户";
        }
        if (Role.ASSISTANT.name().equals(role)) {
            return "助手";
        }
        if (Role.SYSTEM.name().equals(role)) {
            return "系统";
        }
        return role == null ? "消息" : role;
    }

    private String abbreviate(String value, int limit) {
        if (value == null || value.length() <= limit) {
            return value == null ? "" : value;
        }
        return value.substring(0, Math.max(0, limit - 1)) + "…";
    }

    private record GeneratedSummary(String text, Integer outputTokens) {
    }

    private int resolveThresholdPercent(TokenOverflowConfig overflowConfig) {
        if (overflowConfig == null || overflowConfig.getSummaryThreshold() == null) {
            return DEFAULT_SUMMARY_THRESHOLD_PERCENT;
        }
        int configured = overflowConfig.getSummaryThreshold();
        if (configured <= 0) {
            return DEFAULT_SUMMARY_THRESHOLD_PERCENT;
        }
        return Math.max(MIN_SUMMARY_THRESHOLD_PERCENT, Math.min(MAX_SUMMARY_THRESHOLD_PERCENT, configured));
    }

    private int resolveRetainedTokenTarget(int maxTokens, Double retainRatio) {
        double ratio = retainRatio != null ? retainRatio : DEFAULT_RETAIN_RATIO;
        ratio = Math.max(0D, Math.min(0.9D, ratio));
        int target = (int) Math.floor(maxTokens * ratio);
        return Math.max(1, target);
    }

    private int getMaxTokens(TokenOverflowConfig overflowConfig) {
        if (overflowConfig == null || overflowConfig.getMaxTokens() == null || overflowConfig.getMaxTokens() <= 0) {
            return DEFAULT_MAX_TOKENS;
        }
        return overflowConfig.getMaxTokens();
    }

    private List<TokenMessage> selectRecentMessages(List<TokenMessage> sortedMessages, int retainedTokenTarget) {
        if (sortedMessages == null || sortedMessages.isEmpty()) {
            return Collections.emptyList();
        }
        List<TokenMessage> retainedDescending = new ArrayList<>();
        int retainedTokens = 0;
        for (int index = sortedMessages.size() - 1; index >= 0; index--) {
            TokenMessage message = sortedMessages.get(index);
            if (message == null) {
                continue;
            }
            if (Role.SUMMARY.name().equals(message.getRole())) {
                continue;
            }
            int tokens = message.getBodyTokenCount() != null ? message.getBodyTokenCount() : 0;
            boolean forceKeep = retainedDescending.size() < MIN_RETAINED_MESSAGES;
            if (forceKeep || retainedTokens + tokens <= retainedTokenTarget) {
                retainedDescending.add(message);
                retainedTokens += tokens;
            } else {
                break;
            }
        }
        Collections.reverse(retainedDescending);
        return retainedDescending;
    }

    private List<TokenMessage> trimToMaxTokens(List<TokenMessage> messages, int maxTokens) {
        if (messages == null || messages.isEmpty()) {
            return Collections.emptyList();
        }
        int totalTokens = calculateTotalTokens(messages);
        if (totalTokens <= maxTokens) {
            return messages;
        }
        List<TokenMessage> trimmed = new ArrayList<>(messages);
        // Remove oldest retained messages first (keep summary at index 0).
        while (trimmed.size() > 1 && totalTokens > maxTokens) {
            TokenMessage removed = trimmed.remove(1);
            if (removed != null && removed.getBodyTokenCount() != null) {
                totalTokens -= removed.getBodyTokenCount();
            } else {
                totalTokens = calculateTotalTokens(trimmed);
            }
        }
        if (totalTokens > maxTokens && !trimmed.isEmpty()) {
            // Extreme case: summary itself is too large; keep it only.
            return Collections.singletonList(trimmed.get(0));
        }
        return trimmed;
    }

    /** 创建新的摘要消息记录
     *
     * @param newSummary 摘要内容 */
    private TokenMessage createNewSummaryMessage(String newSummary, Integer newSummaryBodyTokenCount,
            List<TokenMessage> historyMessages) {

        TokenMessage newSummaryMessage = new TokenMessage();
        newSummaryMessage.setRole(Role.SUMMARY.name());
        newSummaryMessage.setContent(newSummary);
        int bodyTokens = newSummaryBodyTokenCount != null && newSummaryBodyTokenCount > 0 ? newSummaryBodyTokenCount
                : (newSummary == null ? 0 : Math.max(1, newSummary.length() / 2));
        newSummaryMessage.setBodyTokenCount(bodyTokens);
        newSummaryMessage.setTokenCount(bodyTokens);

        // 找到历史消息中的最早时间
        LocalDateTime earliestTime = historyMessages.stream()
                .filter(message -> !message.getRole().equals(Role.SUMMARY.name())).map(TokenMessage::getCreatedAt)
                .min(LocalDateTime::compareTo).orElse(LocalDateTime.now());

        // 设置创建时间和更新时间为最早时间的前一秒
        LocalDateTime summaryTime = earliestTime.minusSeconds(1);
        newSummaryMessage.setCreatedAt(summaryTime);
        return newSummaryMessage;
    }

    /** 计算消息列表的总token数 */
    private int calculateTotalTokens(List<TokenMessage> messages) {
        return messages.stream().mapToInt(m -> m.getBodyTokenCount() != null ? m.getBodyTokenCount() : 0).sum();
    }

    /** 获取生成的摘要消息对象
     * 
     * @return 摘要消息对象 */
    public TokenMessage getSummaryMessage() {
        return summaryMessage;
    }
}
