package com.example.agentx.domain.memory.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import com.example.agentx.domain.memory.config.MemoryExtractProperties;
import com.example.agentx.domain.memory.model.CandidateMemory;
import com.example.agentx.domain.memory.model.MemoryType;
import com.example.agentx.domain.prompt.PromptXmlUtils;
import com.example.agentx.domain.token.service.TokenEstimatorService;
import com.example.agentx.domain.rag.model.ModelConfig;
import com.example.agentx.infrastructure.llm.LLMProviderService;
import com.example.agentx.infrastructure.llm.config.ProviderConfig;
import com.example.agentx.infrastructure.llm.config.ProviderConfigFactory;
import com.example.agentx.infrastructure.llm.protocol.enums.ProviderProtocol;
import com.example.agentx.infrastructure.rag.service.UserModelConfigResolver;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 记忆抽取服务（对话后从一轮对话提取可长期复用的要点） */
@Service
public class MemoryExtractorService {

    private static final Logger log = LoggerFactory.getLogger(MemoryExtractorService.class);
    private static final Pattern CONTROL_CHAR_PATTERN = Pattern.compile("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]");
    private static final Pattern JSON_CODE_BLOCK_PATTERN = Pattern.compile("(?is)```(?:json)?\\s*(\\{.*}|\\[.*])\\s*```");
    private static final Pattern JSON_MEMORY_PATTERN = Pattern.compile(
            "(?is)\\{\\s*\"type\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\"\\s*,\\s*\"text\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\"\\s*,\\s*\"importance\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(?:,\\s*\"tags\"\\s*:\\s*\\[(.*?)\\])?\\s*\\}");
    private static final Pattern JSON_TAG_PATTERN = Pattern.compile("\"((?:\\\\.|[^\"])*)\"");
    private static final String EXTRACT_PROMPT_TEMPLATE = """
            你是一名对话记忆提取器。你的任务是从“同一会话中最近 1-N 轮对话片段”中抽取对后续多轮交互有复用价值的要点，并返回结构化 JSON。

            你会收到 XML 输入，结构类似：
            <conversation_batch>
              <turn index="1">
                <recent_history>
                  <message>user: ...</message>
                  <message>assistant: ...</message>
                </recent_history>
                <user>...</user>
                <assistant>...</assistant>
              </turn>
            </conversation_batch>

            其中：
            - <user> 是当前轮用户发言；
            - <assistant> 是当前轮 AI 回复，主要用于辅助识别由回复明确化的稳定事实/偏好/计划；
            - <recent_history> 仅用于消解“改成两周/继续之前那个计划”这类依赖上下文的表达，不能机械照抄历史。

            一、类型定义（仅限以下四类）
            - PROFILE：用户稳定的偏好/人格特质/固定格式要求。
            - TASK：明确的中长期目标/持续性计划。
            - FACT：与用户身份或工作环境相关、在较长时间内稳定不变的事实。
            - EPISODIC：未来 3–5 轮内明显有帮助的情节性信息。

            二、严格的“不抽取”判定
            - 一次性操作/命令/工具调用/浏览或文件系统操作的请求或描述。
            - 仅与当轮问题相关的临时细节、临时数据或结论。
            - 含有隐私信息或敏感凭据。

            三、提取与打分规则
            - 仅在“明确有助于后续多轮”的情况下才抽取；否则返回 should_extract=false 且 memories=[]。
            - importance 评分范围 0.0–1.0，综合考虑稳定性、复用价值、明确性与风险。
            - 仅输出 importance ≥ %s 的候选。
            - 非 EPISODIC 记忆中，importance ≥ %s 的候选会直接写入；EPISODIC 需 importance ≥ %s 才会直接写入。
            - 非 EPISODIC 候选若 importance 位于 [%s, %s)，EPISODIC 候选若 importance 位于 [%s, %s)，系统会先放入待确认队列，后续再次出现相近内容时再升级为正式记忆，因此这类候选也应输出。
            - 若当前轮明确改变长期偏好、默认语言、回答风格、常用命令环境或持续目标，应输出新的记忆；系统会用新记忆替换冲突旧记忆。
            - 去重与合并：相同语义合并为 1 条；最多输出 1–3 条最高价值要点。
            - 文本应简洁可复用，避免逐字复述用户原话；不要输出命令、文件路径或一次性请求。

            四、输出格式（仅输出 JSON）
            {"should_extract":true,"memories":[{"type":"PROFILE|TASK|FACT|EPISODIC","text":"...","importance":0.0,"tags":["t1","t2"]}]}

            五、示例
            A. 输入：“调用子 agent 查看 user 目录下的文件”
               输出：{"should_extract":false,"memories":[]}
            B. 输入：“以后都用简体中文回答，并尽量给出 bash 示例”
               输出：{"should_extract":true,"memories":[{"type":"PROFILE","text":"用户偏好简体中文回答，并偏好附带 bash 示例","importance":0.9,"tags":["preference"]}]}
            C. 输入：“这周要把 Agent 项目搭建完并写文档”
               输出：{"should_extract":true,"memories":[{"type":"TASK","text":"用户本周目标：完成 Agent 项目搭建并补齐文档","importance":0.9,"tags":["goal"]}]}
            D. 输入：“我主要用 Python，平时在上海办公”
               输出：{"should_extract":true,"memories":[{"type":"FACT","text":"用户主要使用 Python，常驻上海办公","importance":0.85,"tags":["background"]}]}
            """;

    private final UserModelConfigResolver userModelConfigResolver;
    private final MemoryDomainService memoryDomainService;
    private final Executor memoryTaskExecutor;
    private final TokenEstimatorService tokenEstimatorService;
    private final MemoryExtractProperties memoryExtractProperties;
    private final ObjectMapper objectMapper;
    private final ProviderConfigFactory providerConfigFactory;
    private final ConcurrentMap<String, PendingBatch> pendingBatches = new ConcurrentHashMap<>();
    private final TaskScheduler batchFlushTaskScheduler;
    private volatile long lastPendingBatchCleanupAt;

    public MemoryExtractorService(UserModelConfigResolver userModelConfigResolver,
            MemoryDomainService memoryDomainService, @Qualifier("memoryExtractTaskExecutor") Executor memoryTaskExecutor,
            TokenEstimatorService tokenEstimatorService, MemoryExtractProperties memoryExtractProperties,
            ObjectMapper objectMapper, ProviderConfigFactory providerConfigFactory,
            @Qualifier("memoryExtractBatchFlushTaskScheduler") TaskScheduler batchFlushTaskScheduler) {
        this.userModelConfigResolver = userModelConfigResolver;
        this.memoryDomainService = memoryDomainService;
        this.memoryTaskExecutor = memoryTaskExecutor;
        this.tokenEstimatorService = tokenEstimatorService;
        this.memoryExtractProperties = memoryExtractProperties;
        this.objectMapper = objectMapper;
        this.providerConfigFactory = providerConfigFactory;
        this.batchFlushTaskScheduler = batchFlushTaskScheduler;
    }

    /** 异步抽取并持久化（供外部直接调用，无需处理返回值） */
    public void extractAndPersistAsync(String userId, String sessionId, String userMessage) {
        extractAndPersistAsync(userId, sessionId, null, userMessage);
    }

    /** 异步抽取并持久化（带 scopeAgentId） */
    public void extractAndPersistAsync(String userId, String sessionId, String scopeAgentId, String userMessage) {
        submitExtractionTask(userId, sessionId,
                () -> extractAndPersistInternal(userId, sessionId, scopeAgentId, userMessage, null,
                        Collections.emptyList()));
    }

    /** 异步抽取并持久化（带当前轮AI回复与少量历史，支持批量 flush） */
    public void extractAndPersistAsync(String userId, String sessionId, String userMessage, String assistantReply,
            List<String> recentHistory) {
        extractAndPersistAsync(userId, sessionId, null, userMessage, assistantReply, recentHistory);
    }

    /** 异步抽取并持久化（带 scopeAgentId、当前轮AI回复与少量历史，支持批量 flush） */
    public void extractAndPersistAsync(String userId, String sessionId, String scopeAgentId, String userMessage,
            String assistantReply, List<String> recentHistory) {
        submitExtractionTask(userId, sessionId,
                () -> extractAndPersistInternal(userId, sessionId, scopeAgentId, userMessage, assistantReply,
                        recentHistory));
    }

    private void submitExtractionTask(String userId, String sessionId, Runnable task) {
        try {
            memoryTaskExecutor.execute(task);
        } catch (RejectedExecutionException e) {
            log.warn("记忆抽取任务队列已满，跳过本轮抽取 userId={}, sessionId={}", userId, sessionId);
        }
    }

    private void extractAndPersistInternal(String userId, String sessionId, String scopeAgentId, String userMessage,
            String assistantReply, List<String> recentHistory) {
        try {
            if (!StringUtils.hasText(userId) || !StringUtils.hasText(sessionId)) {
                return;
            }
            MemoryExtractionTurn turn = MemoryExtractionTurn.of(userMessage, assistantReply, recentHistory);
            if (shouldSkipTurn(turn)) {
                log.debug("记忆抽取前置过滤跳过，userId={}, sessionId={}, userMessage={}", userId, sessionId,
                        abbreviate(userMessage, 80));
                return;
            }
            enqueueTurn(userId, sessionId, scopeAgentId, turn);
        } catch (Exception e) {
            log.warn("async extract&persist failed userId={}, sessionId={}, err={}", userId, sessionId, e.getMessage());
        }
    }

    /** 抽取候选记忆（基于一组最近对话片段）
     * @param userId 用户ID
     * @param sessionId 会话ID（仅记录来源）
     * @param turns 最近待抽取的对话轮次
     * @return 候选记忆列表（可能为空） */
    public List<CandidateMemory> extract(String userId, String sessionId, List<MemoryExtractionTurn> turns) {
        return extractBatch(userId, sessionId, turns).acceptedCandidates();
    }

    private MemoryExtractionOutcome extractBatch(String userId, String sessionId, List<MemoryExtractionTurn> turns) {
        if (CollectionUtils.isEmpty(turns)) {
            return MemoryExtractionOutcome.empty(false);
        }
        try {
            // 优先使用会话/工作区模型，兜底用户默认聊天模型
            ModelConfig chatCfg = userModelConfigResolver.getPreferredChatModelConfig(userId, sessionId);
            ProviderConfig providerConfig = providerConfigFactory.fromModelConfig(chatCfg, null);
            providerConfig.setPromptCachingEnabled(memoryExtractProperties.isPromptCachingEnabled());
            providerConfig.setCacheSystemMessages(memoryExtractProperties.isPromptCachingEnabled());
            providerConfig.setCacheTools(false);
            providerConfig.setTimeout(Duration.ofMillis(
                    Math.max(1000L, memoryExtractProperties.getExtractionTimeoutMillis())));
            if (chatCfg.getProtocol() == ProviderProtocol.OPENAI) {
                providerConfig.setResponseFormat("json_object");
            }
            ChatModel chatModel = LLMProviderService.getStrand(chatCfg.getProtocol(), providerConfig);

            List<ChatMessage> messages = new ArrayList<>();
            messages.add(new SystemMessage(buildExtractPrompt()));
            String extractionPayload = buildExtractionPayload(turns);
            if (StringUtils.hasText(extractionPayload)) {
                messages.add(new UserMessage(extractionPayload));
            }

            ChatResponse resp = chatModel.chat(messages);

            String structuredOutput = resp.aiMessage().text();
            if (!StringUtils.hasText(structuredOutput)) {
                return MemoryExtractionOutcome.empty(false);
            }

            return parseStructuredMemories(structuredOutput, chatCfg.getProtocol());
        } catch (Exception e) {
            log.warn("记忆抽取失败 userId={}, err={}", userId, e.getMessage());
            return MemoryExtractionOutcome.empty(false);
        }
    }

    /** 抽取候选记忆（兼容旧方法） */
    public List<CandidateMemory> extract(String userId, String sessionId, String userMessage) {
        if (!StringUtils.hasText(userMessage)) {
            return new ArrayList<>();
        }
        return extractBatch(userId, sessionId, Collections.singletonList(MemoryExtractionTurn.of(userMessage, null,
                Collections.emptyList()))).acceptedCandidates();
    }

    @PreDestroy
    public void shutdown() {
        pendingBatches.forEach((key, batch) -> flushPendingBatch(key, batch, "shutdown"));
    }

    private static Double asDouble(Object o) {
        if (o instanceof Number n)
            return n.doubleValue();
        try {
            return o == null ? null : Double.parseDouble(String.valueOf(o));
        } catch (Exception ignore) {
            return null;
        }
    }

    private MemoryExtractionOutcome parseStructuredMemories(String output, ProviderProtocol protocol) {
        if (!StringUtils.hasText(output)) {
            return MemoryExtractionOutcome.empty(false);
        }

        String normalizedOutput = normalizeModelOutput(output);
        JsonParseResult jsonResult = tryParseJsonMemories(normalizedOutput);
        if (jsonResult.parsed()) {
            return postProcessCandidates(jsonResult.output());
        }

        if (protocol == ProviderProtocol.OPENAI) {
            log.warn("记忆抽取 JSON 解析失败，OpenAI 结构化输出不启用 regex fallback，输出前120字符={}",
                    abbreviate(normalizedOutput, 120));
            return MemoryExtractionOutcome.empty(false);
        }

        if (normalizedOutput.length() > Math.max(256, memoryExtractProperties.getRegexFallbackMaxChars())) {
            log.warn("记忆抽取输出过长，跳过 regex fallback，outputLength={}", normalizedOutput.length());
            return MemoryExtractionOutcome.empty(false);
        }

        List<CandidateMemory> regexMemories = tryRegexParseJsonMemories(normalizedOutput);
        if (!regexMemories.isEmpty()) {
            log.debug("记忆抽取启用 regex fallback，命中{}条", regexMemories.size());
            return postProcessCandidates(new StructuredExtractionOutput(null, regexMemories));
        }

        log.warn("记忆抽取解析失败，输出前120字符={}", abbreviate(normalizedOutput, 120));
        return MemoryExtractionOutcome.empty(false);
    }

    private JsonParseResult tryParseJsonMemories(String rawOutput) {
        String jsonPayload = extractJsonPayload(rawOutput);
        if (!StringUtils.hasText(jsonPayload)) {
            return JsonParseResult.notParsed();
        }
        try {
            JsonNode root = objectMapper.readTree(jsonPayload);
            return JsonParseResult.parsed(parseJsonNodeToOutput(root));
        } catch (Exception ignore) {
            return JsonParseResult.notParsed();
        }
    }

    private StructuredExtractionOutput parseJsonNodeToOutput(JsonNode root) {
        if (root == null || root.isNull()) {
            return StructuredExtractionOutput.empty();
        }
        List<CandidateMemory> results = new ArrayList<>();
        if (root.isObject()) {
            Boolean shouldExtract = getJsonBoolean(root, "should_extract");
            if (shouldExtract == null) {
                shouldExtract = getJsonBoolean(root, "shouldExtract");
            }
            JsonNode memoriesNode = root.get("memories");
            if (memoriesNode != null && memoriesNode.isArray()) {
                memoriesNode.forEach(node -> addJsonMemory(results, node));
                return new StructuredExtractionOutput(shouldExtract, results);
            }
            addJsonMemory(results, root);
            return new StructuredExtractionOutput(shouldExtract, results);
        }
        if (root.isArray()) {
            root.forEach(node -> addJsonMemory(results, node));
        }
        return new StructuredExtractionOutput(null, results);
    }

    private void addJsonMemory(List<CandidateMemory> results, JsonNode node) {
        if (node == null || !node.isObject()) {
            return;
        }
        String type = getJsonText(node, "type");
        String text = getJsonText(node, "text");
        Float importance = parseJsonImportance(node.get("importance"));
        List<String> tags = parseJsonTags(node.get("tags"));
        Map<String, Object> data = null;
        JsonNode dataNode = node.get("data");
        if (dataNode != null && dataNode.isObject()) {
            data = objectMapper.convertValue(dataNode, new TypeReference<Map<String, Object>>() {
            });
        } else if (dataNode != null && dataNode.isTextual()) {
            data = parseJsonMap(dataNode.asText());
        }

        CandidateMemory candidate = buildCandidateMemory(type, text, importance, tags, data);
        if (candidate != null) {
            results.add(candidate);
        }
    }

    private List<CandidateMemory> tryRegexParseJsonMemories(String rawOutput) {
        String target = extractJsonPayload(rawOutput);
        if (!StringUtils.hasText(target)) {
            target = rawOutput;
        }
        Matcher matcher = JSON_MEMORY_PATTERN.matcher(target);
        List<CandidateMemory> results = new ArrayList<>();
        while (matcher.find()) {
            CandidateMemory candidate = buildCandidateMemory(unescapeJson(matcher.group(1)),
                    unescapeJson(matcher.group(2)), parseImportance(matcher.group(3)),
                    parseRegexTags(matcher.group(4)), null);
            if (candidate != null) {
                results.add(candidate);
            }
        }
        return results;
    }

    private String normalizeModelOutput(String rawOutput) {
        if (!StringUtils.hasText(rawOutput)) {
            return "";
        }
        String normalized = CONTROL_CHAR_PATTERN.matcher(rawOutput).replaceAll("").trim();
        Matcher jsonCodeBlock = JSON_CODE_BLOCK_PATTERN.matcher(normalized);
        if (jsonCodeBlock.find()) {
            return jsonCodeBlock.group(1).trim();
        }
        return normalized.replace("```json", "").replace("```JSON", "").replace("```", "").trim();
    }

    private String extractJsonPayload(String rawOutput) {
        if (!StringUtils.hasText(rawOutput)) {
            return null;
        }
        Matcher codeBlockMatcher = JSON_CODE_BLOCK_PATTERN.matcher(rawOutput);
        if (codeBlockMatcher.find()) {
            return codeBlockMatcher.group(1).trim();
        }
        String normalized = rawOutput.trim();
        if ((normalized.startsWith("{") && normalized.endsWith("}"))
                || (normalized.startsWith("[") && normalized.endsWith("]"))) {
            return normalized;
        }
        int objectStart = normalized.indexOf('{');
        int objectEnd = normalized.lastIndexOf('}');
        if (objectStart >= 0 && objectEnd > objectStart) {
            return normalized.substring(objectStart, objectEnd + 1);
        }
        int arrayStart = normalized.indexOf('[');
        int arrayEnd = normalized.lastIndexOf(']');
        if (arrayStart >= 0 && arrayEnd > arrayStart) {
            return normalized.substring(arrayStart, arrayEnd + 1);
        }
        return null;
    }

    private List<String> parseRegexTags(String block) {
        if (!StringUtils.hasText(block)) {
            return Collections.emptyList();
        }
        Matcher matcher = JSON_TAG_PATTERN.matcher(block);
        List<String> tags = new ArrayList<>();
        while (matcher.find()) {
            if (StringUtils.hasText(matcher.group(1))) {
                tags.add(unescapeJson(matcher.group(1)).trim());
            }
        }
        return tags;
    }

    private CandidateMemory buildCandidateMemory(String type, String text, Float importance, List<String> tags,
            Map<String, Object> data) {
        MemoryType memoryType = MemoryType.ofNullable(type);
        String sanitizedText = MemoryContentSupport.sanitizeText(text);
        if (memoryType == null || !StringUtils.hasText(sanitizedText)) {
            return null;
        }
        CandidateMemory cm = new CandidateMemory();
        cm.setType(memoryType);
        cm.setText(sanitizedText);
        cm.setImportance(safeImportance(importance));
        cm.setTags(MemoryContentSupport.sanitizeTags(tags, memoryExtractProperties.getMaxTagsPerMemory()));
        cm.setData(data);
        return cm;
    }

    private MemoryExtractionOutcome postProcessCandidates(StructuredExtractionOutput output) {
        if (output == null || CollectionUtils.isEmpty(output.candidates())) {
            return MemoryExtractionOutcome.empty(Boolean.TRUE.equals(output == null ? null : output.shouldExtract()));
        }
        Map<String, CandidateMemory> deduped = new LinkedHashMap<>();
        for (CandidateMemory candidate : output.candidates()) {
            CandidateMemory sanitized = sanitizeCandidate(candidate);
            if (sanitized == null || !passesBaseCandidateChecks(sanitized)) {
                continue;
            }
            String dedupeKey = MemoryContentSupport.normalizeDedupeKey(sanitized.getText());
            if (!StringUtils.hasText(dedupeKey)) {
                continue;
            }
            deduped.merge(dedupeKey, sanitized, this::pickBetterCandidate);
        }
        List<CandidateMemory> sortedCandidates = deduped.values().stream().sorted(candidateComparator()).toList();
        int limit = Math.max(1, memoryExtractProperties.getMaxMemoriesPerBatch());
        List<CandidateMemory> acceptedCandidates = sortedCandidates.stream().filter(this::isQualifiedCandidate)
                .limit(limit).toList();
        List<CandidateMemory> pendingCandidates = sortedCandidates.stream()
                .filter(candidate -> !isQualifiedCandidate(candidate) && isPendingCandidate(candidate)).limit(limit)
                .toList();
        boolean shouldExtract = resolveShouldExtract(output.shouldExtract(), acceptedCandidates, pendingCandidates);
        if (!shouldExtract) {
            return MemoryExtractionOutcome.empty(false);
        }
        return new MemoryExtractionOutcome(true, new ArrayList<>(acceptedCandidates), new ArrayList<>(pendingCandidates));
    }

    private CandidateMemory sanitizeCandidate(CandidateMemory candidate) {
        if (candidate == null || candidate.getType() == null) {
            return null;
        }
        String sanitizedText = MemoryContentSupport.sanitizeText(candidate.getText());
        if (!StringUtils.hasText(sanitizedText)) {
            return null;
        }
        candidate.setText(sanitizedText);
        candidate.setImportance(safeImportance(candidate.getImportance()));
        candidate.setTags(
                MemoryContentSupport.sanitizeTags(candidate.getTags(), memoryExtractProperties.getMaxTagsPerMemory()));
        return candidate;
    }

    private boolean isQualifiedCandidate(CandidateMemory candidate) {
        if (!passesBaseCandidateChecks(candidate)) {
            return false;
        }
        float importance = safeImportance(candidate.getImportance());
        return importance >= resolveDirectImportanceThreshold(candidate);
    }

    private boolean isPendingCandidate(CandidateMemory candidate) {
        if (!passesBaseCandidateChecks(candidate)) {
            return false;
        }
        float importance = safeImportance(candidate.getImportance());
        float directThreshold = resolveDirectImportanceThreshold(candidate);
        float pendingThreshold = resolvePendingImportanceThreshold(candidate);
        return importance >= pendingThreshold && importance < directThreshold;
    }

    private boolean passesBaseCandidateChecks(CandidateMemory candidate) {
        if (candidate == null || candidate.getType() == null || !StringUtils.hasText(candidate.getText())) {
            return false;
        }
        if (MemoryContentSupport.isLowInformationText(candidate.getText(),
                memoryExtractProperties.getMinMemoryTextCodePoints())) {
            return false;
        }
        if (MemoryContentSupport.isLikelySensitiveText(candidate.getText())
                || MemoryContentSupport.isLikelyOperationalText(candidate.getText())
                || MemoryContentSupport.isLikelyMetaDialogueText(candidate.getText())) {
            return false;
        }
        return true;
    }

    private float resolveDirectImportanceThreshold(CandidateMemory candidate) {
        if (candidate == null || candidate.getType() == null) {
            return safeImportance(memoryExtractProperties.getMinImportance());
        }
        return candidate.getType() == MemoryType.EPISODIC ? safeImportance(memoryExtractProperties.getEpisodicMinImportance())
                : safeImportance(memoryExtractProperties.getMinImportance());
    }

    private float resolvePendingImportanceThreshold(CandidateMemory candidate) {
        return Math.min(resolveDirectImportanceThreshold(candidate),
                safeImportance(memoryExtractProperties.getPendingMinImportance()));
    }

    private boolean resolveShouldExtract(Boolean shouldExtract, List<CandidateMemory> acceptedCandidates,
            List<CandidateMemory> pendingCandidates) {
        if (shouldExtract != null) {
            return shouldExtract;
        }
        return !CollectionUtils.isEmpty(acceptedCandidates) || !CollectionUtils.isEmpty(pendingCandidates);
    }

    private CandidateMemory pickBetterCandidate(CandidateMemory current, CandidateMemory incoming) {
        return candidateComparator().compare(current, incoming) <= 0 ? current : incoming;
    }

    private Comparator<CandidateMemory> candidateComparator() {
        return Comparator.comparing((CandidateMemory candidate) -> safeImportance(candidate.getImportance()),
                Comparator.reverseOrder())
                .thenComparing(candidate -> typePriority(candidate.getType()), Comparator.reverseOrder())
                .thenComparing(candidate -> MemoryContentSupport.codePointLength(candidate.getText()),
                        Comparator.reverseOrder());
    }

    private int typePriority(MemoryType type) {
        MemoryType safeType = type == null ? MemoryType.FACT : type;
        return switch (safeType) {
            case PROFILE -> 4;
            case TASK -> 3;
            case FACT -> 2;
            case EPISODIC -> 1;
        };
    }

    private Float parseImportance(String value) {
        Double imp = asDouble(value);
        return imp == null ? 0.5f : imp.floatValue();
    }

    private Float parseJsonImportance(JsonNode node) {
        if (node == null || node.isNull()) {
            return 0.5f;
        }
        if (node.isNumber()) {
            return (float) node.asDouble();
        }
        return parseImportance(node.asText());
    }

    private List<String> parseJsonTags(JsonNode node) {
        if (node == null || node.isNull()) {
            return new ArrayList<>();
        }
        List<String> tags = new ArrayList<>();
        if (node.isArray()) {
            node.forEach(tagNode -> {
                if (tagNode != null && !tagNode.isNull() && StringUtils.hasText(tagNode.asText())) {
                    tags.add(tagNode.asText().trim());
                }
            });
        } else if (node.isTextual()) {
            String raw = node.asText().trim();
            if (raw.contains(",")) {
                for (String part : raw.split(",")) {
                    if (StringUtils.hasText(part)) {
                        tags.add(part.trim());
                    }
                }
            } else if (StringUtils.hasText(raw)) {
                tags.add(raw);
            }
        }
        return tags;
    }

    private String getJsonText(JsonNode node, String fieldName) {
        if (node == null || !node.has(fieldName) || node.get(fieldName).isNull()) {
            return null;
        }
        String value = node.get(fieldName).asText();
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private Boolean getJsonBoolean(JsonNode node, String fieldName) {
        if (node == null || !node.has(fieldName) || node.get(fieldName).isNull()) {
            return null;
        }
        JsonNode valueNode = node.get(fieldName);
        if (valueNode.isBoolean()) {
            return valueNode.asBoolean();
        }
        if (valueNode.isTextual()) {
            String raw = valueNode.asText();
            if (!StringUtils.hasText(raw)) {
                return null;
            }
            return Boolean.parseBoolean(raw.trim());
        }
        return null;
    }

    private Map<String, Object> parseJsonMap(String rawJson) {
        if (!StringUtils.hasText(rawJson)) {
            return null;
        }
        try {
            return objectMapper.readValue(rawJson, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception ignore) {
            return null;
        }
    }

    private String unescapeJson(String value) {
        if (value == null) {
            return null;
        }
        return value.replace("\\\"", "\"").replace("\\\\", "\\").replace("\\n", "\n").replace("\\r", "\r")
                .replace("\\t", "\t");
    }

    private void enqueueTurn(String userId, String sessionId, String scopeAgentId, MemoryExtractionTurn turn) {
        cleanupPendingBatchesIfNeeded();
        String batchKey = buildBatchKey(userId, sessionId);
        PendingBatch batch = pendingBatches.computeIfAbsent(batchKey,
                ignored -> new PendingBatch(userId, sessionId, scopeAgentId));
        PendingBatchSnapshot snapshot = null;
        int turnTokens = estimateTurnTokens(turn);

        synchronized (batch) {
            if (!batch.turns.isEmpty()
                    && batch.estimatedTokens + turnTokens > Math.max(1, memoryExtractProperties.getBatchMaxTokens())) {
                snapshot = batch.drain("token-budget");
                pendingBatches.remove(batchKey, batch);
            }
        }

        if (snapshot != null) {
            persistSnapshot(snapshot);
            batch = pendingBatches.computeIfAbsent(batchKey,
                    ignored -> new PendingBatch(userId, sessionId, scopeAgentId));
        }

        synchronized (batch) {
            if (!StringUtils.hasText(batch.scopeAgentId) && StringUtils.hasText(scopeAgentId)) {
                batch.scopeAgentId = scopeAgentId;
            }
            batch.turns.add(turn);
            batch.estimatedTokens += turnTokens;
            batch.lastUpdatedAt = System.currentTimeMillis();
            if (batch.scheduledFlush != null) {
                batch.scheduledFlush.cancel(false);
            }

            if (shouldFlushBatchImmediately(turn)) {
                snapshot = batch.drain("explicit-memory-signal");
                pendingBatches.remove(batchKey, batch);
            } else if (batch.turns.size() >= Math.max(1, memoryExtractProperties.getBatchTurnThreshold())) {
                snapshot = batch.drain("threshold");
                pendingBatches.remove(batchKey, batch);
            } else {
                PendingBatch scheduledBatch = batch;
                batch.scheduledFlush = batchFlushTaskScheduler.schedule(
                        () -> submitFlush(batchKey, scheduledBatch, "idle"),
                        Instant.now().plusMillis(Math.max(1000L, memoryExtractProperties.getIdleFlushDelayMillis())));
            }
        }

        if (snapshot != null) {
            persistSnapshot(snapshot);
        }
    }

    private void cleanupPendingBatchesIfNeeded() {
        long now = System.currentTimeMillis();
        int maxBatches = Math.max(1, memoryExtractProperties.getPendingMaxBatches());
        boolean capacityPressure = pendingBatches.size() >= maxBatches;
        if (!capacityPressure && now - lastPendingBatchCleanupAt < 30_000L) {
            return;
        }
        lastPendingBatchCleanupAt = now;

        List<PendingBatchSnapshot> snapshots = new ArrayList<>();
        long ttlMillis = Math.max(1_000L, memoryExtractProperties.getPendingBatchTtlMillis());
        pendingBatches.forEach((key, batch) -> {
            if (batch != null && batch.lastUpdatedAt > 0 && now - batch.lastUpdatedAt >= ttlMillis) {
                PendingBatchSnapshot snapshot = drainPendingBatch(key, batch, "ttl-expired");
                if (snapshot != null) {
                    snapshots.add(snapshot);
                }
            }
        });

        int evictAttempts = 0;
        while (pendingBatches.size() >= maxBatches && evictAttempts++ < 64) {
            Map.Entry<String, PendingBatch> oldestEntry = findOldestPendingBatchEntry();
            if (oldestEntry == null) {
                break;
            }
            PendingBatchSnapshot snapshot = drainPendingBatch(oldestEntry.getKey(), oldestEntry.getValue(),
                    "capacity-pressure");
            if (snapshot != null) {
                snapshots.add(snapshot);
            } else {
                pendingBatches.remove(oldestEntry.getKey(), oldestEntry.getValue());
            }
        }

        for (PendingBatchSnapshot snapshot : snapshots) {
            persistSnapshot(snapshot);
        }
    }

    private Map.Entry<String, PendingBatch> findOldestPendingBatchEntry() {
        return pendingBatches.entrySet().stream()
                .min(Comparator.comparingLong(entry -> entry.getValue() == null ? Long.MAX_VALUE
                        : entry.getValue().lastUpdatedAt <= 0 ? Long.MAX_VALUE : entry.getValue().lastUpdatedAt))
                .orElse(null);
    }

    private PendingBatchSnapshot drainPendingBatch(String batchKey, PendingBatch expectedBatch, String reason) {
        PendingBatch batch = pendingBatches.get(batchKey);
        if (batch == null || batch != expectedBatch) {
            return null;
        }
        synchronized (batch) {
            PendingBatchSnapshot snapshot = batch.drain(reason);
            pendingBatches.remove(batchKey, batch);
            return snapshot;
        }
    }

    private void submitFlush(String batchKey, PendingBatch expectedBatch, String reason) {
        try {
            memoryTaskExecutor.execute(() -> flushPendingBatch(batchKey, expectedBatch, reason));
        } catch (RejectedExecutionException e) {
            log.warn("记忆批量 flush 队列已满，延后到下一次触发: batchKey={}, reason={}", batchKey, reason);
            batchFlushTaskScheduler.schedule(() -> submitFlush(batchKey, expectedBatch, "retry-after-rejected"),
                    Instant.now().plusSeconds(5));
        }
    }

    private void flushPendingBatch(String batchKey, PendingBatch expectedBatch, String reason) {
        PendingBatch batch = pendingBatches.get(batchKey);
        if (batch == null || batch != expectedBatch) {
            return;
        }

        PendingBatchSnapshot snapshot;
        synchronized (batch) {
            snapshot = batch.drain(reason);
            if (snapshot == null) {
                return;
            }
            pendingBatches.remove(batchKey, batch);
        }
        persistSnapshot(snapshot);
    }

    private void persistSnapshot(PendingBatchSnapshot snapshot) {
        try {
            MemoryExtractionOutcome outcome = extractBatch(snapshot.userId(), snapshot.sessionId(), snapshot.turns());
            int directCount = outcome.acceptedCandidates().size();
            int pendingCount = outcome.pendingCandidates().size();
            if (directCount > 0 || pendingCount > 0) {
                memoryDomainService.saveExtractionCandidates(snapshot.userId(), snapshot.sessionId(),
                        snapshot.scopeAgentId(), outcome.acceptedCandidates(), outcome.pendingCandidates());
                log.debug("记忆批量抽取处理完成，userId={}, sessionId={}, turns={}, reason={}, 直接写入候选={}, 待确认候选={}",
                        snapshot.userId(), snapshot.sessionId(), snapshot.turns().size(), snapshot.reason(),
                        directCount, pendingCount);
            } else {
                log.debug("记忆批量抽取无候选，userId={}, sessionId={}, turns={}, reason={}, shouldExtract={}",
                        snapshot.userId(), snapshot.sessionId(), snapshot.turns().size(), snapshot.reason(),
                        outcome.shouldExtract());
            }
        } catch (Exception e) {
            log.warn("memory batch extract&persist failed userId={}, sessionId={}, reason={}, err={}", snapshot.userId(),
                    snapshot.sessionId(), snapshot.reason(), e.getMessage());
        }
    }

    private boolean shouldSkipTurn(MemoryExtractionTurn turn) {
        String userMessage = turn.userMessage();
        if (!StringUtils.hasText(userMessage)) {
            return true;
        }

        String normalized = userMessage.trim();
        if (isOnlyEmojiOrPunctuation(normalized)) {
            return true;
        }
        if (isShortAckMessage(normalized)) {
            return true;
        }
        return codePointLength(normalized) < Math.max(1,
                Math.min(3, memoryExtractProperties.getShortMessageMinCodePoints()))
                && !StringUtils.hasText(turn.assistantReply());
    }

    private boolean isOnlyEmojiOrPunctuation(String text) {
        return text.codePoints().allMatch(codePoint -> {
            if (Character.isWhitespace(codePoint)) {
                return true;
            }
            int type = Character.getType(codePoint);
            return type == Character.OTHER_SYMBOL || type == Character.MATH_SYMBOL
                    || type == Character.CURRENCY_SYMBOL || type == Character.MODIFIER_SYMBOL
                    || type == Character.DASH_PUNCTUATION || type == Character.START_PUNCTUATION
                    || type == Character.END_PUNCTUATION || type == Character.CONNECTOR_PUNCTUATION
                    || type == Character.OTHER_PUNCTUATION || type == Character.INITIAL_QUOTE_PUNCTUATION
                    || type == Character.FINAL_QUOTE_PUNCTUATION;
        });
    }

    private boolean isShortAckMessage(String text) {
        if (!StringUtils.hasText(text)) {
            return false;
        }
        String normalized = text.trim().toLowerCase(Locale.ROOT);
        return ackMessages().contains(normalized)
                && codePointLength(normalized) <= Math.max(8, memoryExtractProperties.getShortMessageMinCodePoints() * 2);
    }

    private boolean containsImmediateFlushSignal(String text) {
        if (!StringUtils.hasText(text)) {
            return false;
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        for (String keyword : immediateFlushSignalKeywords()) {
            if (normalized.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private int codePointLength(String text) {
        return text == null ? 0 : text.codePointCount(0, text.length());
    }

    private float safeImportance(Float importance) {
        if (importance == null || importance.isNaN() || importance.isInfinite()) {
            return 0.5f;
        }
        return Math.max(0f, Math.min(1f, importance));
    }

    private String buildExtractionPayload(List<MemoryExtractionTurn> turns) {
        StringBuilder payload = new StringBuilder();
        payload.append("<conversation_batch>");
        for (int i = 0; i < turns.size(); i++) {
            MemoryExtractionTurn turn = turns.get(i);
            if (turn == null) {
                continue;
            }
            payload.append("<turn index=\"").append(i + 1).append("\">");
            if (!CollectionUtils.isEmpty(turn.recentHistory())) {
                payload.append("<recent_history>");
                for (String history : turn.recentHistory()) {
                    if (StringUtils.hasText(history)) {
                        payload.append("<message>").append(escapeXml(clipExtractionText(history,
                                memoryExtractProperties.getMaxRecentHistoryChars(), "recent_history")))
                                .append("</message>");
                    }
                }
                payload.append("</recent_history>");
            }
            payload.append("<user>").append(escapeXml(clipExtractionText(turn.userMessage(),
                    memoryExtractProperties.getMaxUserMessageChars(), "user"))).append("</user>");
            if (StringUtils.hasText(turn.assistantReply())) {
                payload.append("<assistant>").append(escapeXml(clipExtractionText(turn.assistantReply(),
                        memoryExtractProperties.getMaxAssistantReplyChars(), "assistant"))).append("</assistant>");
            }
            payload.append("</turn>");
        }
        payload.append("</conversation_batch>");
        return payload.toString();
    }

    private String buildExtractPrompt() {
        return EXTRACT_PROMPT_TEMPLATE.formatted(
                formatImportanceThreshold(memoryExtractProperties.getPendingMinImportance()),
                formatImportanceThreshold(memoryExtractProperties.getMinImportance()),
                formatImportanceThreshold(memoryExtractProperties.getEpisodicMinImportance()),
                formatImportanceThreshold(memoryExtractProperties.getPendingMinImportance()),
                formatImportanceThreshold(memoryExtractProperties.getMinImportance()),
                formatImportanceThreshold(memoryExtractProperties.getPendingMinImportance()),
                formatImportanceThreshold(memoryExtractProperties.getEpisodicMinImportance()));
    }

    private String formatImportanceThreshold(float threshold) {
        return String.format(Locale.ROOT, "%.2f", safeImportance(threshold));
    }

    private String clipExtractionText(String text, int maxChars, String label) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        int limit = Math.max(120, maxChars);
        String normalized = text.trim();
        if (normalized.codePointCount(0, normalized.length()) <= limit) {
            return normalized;
        }
        String clipped = normalized.codePoints().limit(Math.max(1, limit - 18))
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
        return clipped + "\n[" + label + " 已截断]";
    }

    private String escapeXml(String text) {
        return PromptXmlUtils.escapeXml(text);
    }

    private String buildBatchKey(String userId, String sessionId) {
        return userId + ":" + sessionId;
    }

    private String abbreviate(String value, int maxLength) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String normalized = value.replace("\r", " ").replace("\n", " ").trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    private int estimateTurnTokens(MemoryExtractionTurn turn) {
        StringBuilder content = new StringBuilder();
        if (turn != null) {
            content.append(StringUtils.hasText(turn.userMessage())
                    ? clipExtractionText(turn.userMessage(), memoryExtractProperties.getMaxUserMessageChars(), "user")
                    : "");
            content.append('\n');
            content.append(StringUtils.hasText(turn.assistantReply())
                    ? clipExtractionText(turn.assistantReply(), memoryExtractProperties.getMaxAssistantReplyChars(),
                            "assistant")
                    : "");
            if (!CollectionUtils.isEmpty(turn.recentHistory())) {
                for (String history : turn.recentHistory()) {
                    content.append('\n').append(clipExtractionText(history,
                            memoryExtractProperties.getMaxRecentHistoryChars(), "recent_history"));
                }
            }
        }
        return tokenEstimatorService.estimateTextTokenCount(content.toString(), null);
    }

    private Set<String> ackMessages() {
        return new LinkedHashSet<>(memoryExtractProperties.getFilter().getAckMessages().stream()
                .filter(StringUtils::hasText).map(value -> value.trim().toLowerCase()).toList());
    }

    private List<String> immediateFlushSignalKeywords() {
        return memoryExtractProperties.getFilter().getImmediateFlushSignalKeywords().stream()
                .filter(StringUtils::hasText).map(value -> value.trim().toLowerCase()).toList();
    }

    private boolean shouldFlushBatchImmediately(MemoryExtractionTurn turn) {
        if (turn == null) {
            return false;
        }
        return containsImmediateFlushSignal(turn.userMessage()) || containsImmediateFlushSignal(turn.assistantReply());
    }

    private record MemoryExtractionTurn(String userMessage, String assistantReply, List<String> recentHistory) {
        private static MemoryExtractionTurn of(String userMessage, String assistantReply, List<String> recentHistory) {
            List<String> sanitizedHistory = recentHistory == null ? Collections.emptyList()
                    : recentHistory.stream().filter(StringUtils::hasText).map(String::trim)
                            .collect(java.util.stream.Collectors.collectingAndThen(java.util.stream.Collectors.toCollection(LinkedHashSet::new),
                                    ArrayList::new));
            return new MemoryExtractionTurn(StringUtils.hasText(userMessage) ? userMessage.trim() : "",
                    StringUtils.hasText(assistantReply) ? assistantReply.trim() : "", sanitizedHistory);
        }
    }

    private record JsonParseResult(boolean parsed, StructuredExtractionOutput output) {
        private static JsonParseResult parsed(StructuredExtractionOutput output) {
            return new JsonParseResult(true, output == null ? StructuredExtractionOutput.empty() : output);
        }

        private static JsonParseResult notParsed() {
            return new JsonParseResult(false, StructuredExtractionOutput.empty());
        }
    }

    private record StructuredExtractionOutput(Boolean shouldExtract, List<CandidateMemory> candidates) {
        private static StructuredExtractionOutput empty() {
            return new StructuredExtractionOutput(null, Collections.emptyList());
        }
    }

    private record MemoryExtractionOutcome(boolean shouldExtract, List<CandidateMemory> acceptedCandidates,
            List<CandidateMemory> pendingCandidates) {
        private static MemoryExtractionOutcome empty(boolean shouldExtract) {
            return new MemoryExtractionOutcome(shouldExtract, new ArrayList<>(), new ArrayList<>());
        }
    }

    private static final class PendingBatch {
        private final String userId;
        private final String sessionId;
        private String scopeAgentId;
        private final List<MemoryExtractionTurn> turns = new ArrayList<>();
        private ScheduledFuture<?> scheduledFlush;
        private long lastUpdatedAt;
        private int estimatedTokens;

        private PendingBatch(String userId, String sessionId, String scopeAgentId) {
            this.userId = userId;
            this.sessionId = sessionId;
            this.scopeAgentId = scopeAgentId;
            this.lastUpdatedAt = System.currentTimeMillis();
        }

        private PendingBatchSnapshot drain(String reason) {
            if (turns.isEmpty()) {
                return null;
            }
            if (scheduledFlush != null) {
                scheduledFlush.cancel(false);
                scheduledFlush = null;
            }
            List<MemoryExtractionTurn> snapshotTurns = new ArrayList<>(turns);
            turns.clear();
            int snapshotEstimatedTokens = estimatedTokens;
            estimatedTokens = 0;
            return new PendingBatchSnapshot(userId, sessionId, scopeAgentId, snapshotTurns, reason, lastUpdatedAt,
                    snapshotEstimatedTokens);
        }
    }

    private record PendingBatchSnapshot(String userId, String sessionId, String scopeAgentId,
            List<MemoryExtractionTurn> turns, String reason, long lastUpdatedAt, int estimatedTokens) {
    }
}
