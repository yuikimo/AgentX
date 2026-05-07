package com.example.agentx.application.conversation.service.message;

import cn.hutool.core.collection.CollectionUtil;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.*;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.tool.ToolExecution;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderResult;
import org.apache.commons.lang3.StringUtils;
import com.example.agentx.application.conversation.dto.AgentChatResponse;
import com.example.agentx.application.conversation.config.ChatContextProperties;
import com.example.agentx.application.conversation.config.ChatSseProperties;
import com.example.agentx.application.conversation.config.ChatToolProperties;
import com.example.agentx.application.conversation.service.ConversationAttachmentService;
import com.example.agentx.application.conversation.service.handler.context.ChatContext;
import com.example.agentx.application.conversation.service.message.ProviderErrorClassifier.ProviderErrorType;
import com.example.agentx.application.conversation.service.message.agent.ManagedMcpToolProvider;
import com.example.agentx.application.conversation.service.message.rag.RagQueryRewriter;
import com.example.agentx.application.conversation.service.message.builtin.BuiltInToolRegistry;
import com.example.agentx.application.conversation.service.ChatSessionManager;
import com.example.agentx.application.conversation.util.ConversationHistoryUtils;
import com.example.agentx.application.conversation.util.ConversationPromptContextUtils;
import com.example.agentx.application.conversation.util.ChatErrorResponseFactory;
import com.example.agentx.domain.agent.model.AgentEntity;
import com.example.agentx.domain.agent.model.LLMModelConfig;
import com.example.agentx.domain.conversation.constant.MessageType;
import com.example.agentx.domain.conversation.constant.Role;
import com.example.agentx.domain.conversation.model.ConversationAttachment;
import com.example.agentx.domain.conversation.model.ContextEntity;
import com.example.agentx.domain.conversation.model.MessageEntity;
import com.example.agentx.domain.conversation.model.SessionEntity;
import com.example.agentx.domain.conversation.service.MessageDomainService;
import com.example.agentx.domain.conversation.service.SessionDomainService;
import com.example.agentx.domain.llm.model.HighAvailabilityResult;
import com.example.agentx.domain.llm.model.ModelEntity;
import com.example.agentx.domain.llm.model.ProviderEntity;
import com.example.agentx.domain.llm.service.HighAvailabilityDomainService;
import com.example.agentx.domain.llm.service.LLMDomainService;
import com.example.agentx.domain.user.service.UserSettingsDomainService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import com.example.agentx.domain.memory.service.MemoryDomainService;
import com.example.agentx.domain.memory.service.MemoryExtractorService;
import com.example.agentx.domain.memory.config.MemoryRecallProperties;
import org.springframework.core.task.TaskExecutor;
import com.example.agentx.application.conversation.service.message.rag.RagChatContext;
import com.example.agentx.domain.memory.model.MemoryResult;
import com.example.agentx.domain.memory.model.MemoryType;
import com.example.agentx.domain.user.service.AccountDomainService;
import com.example.agentx.infrastructure.exception.BusinessException;
import com.example.agentx.infrastructure.llm.ChatResponseTokenUsageUtils;
import com.example.agentx.infrastructure.llm.LLMServiceFactory;
import com.example.agentx.infrastructure.llm.config.LlmChatProperties;
import com.example.agentx.infrastructure.transport.MessageTransport;
import com.example.agentx.infrastructure.transport.SseEmitterUtils;
import com.example.agentx.application.billing.service.BillingService;
import com.example.agentx.application.billing.dto.RuleContext;
import com.example.agentx.infrastructure.exception.InsufficientBalanceException;
import com.example.agentx.domain.product.constant.BillingType;
import com.example.agentx.domain.product.constant.UsageDataKeys;
import com.example.agentx.domain.user.model.AccountEntity;
import com.example.agentx.domain.trace.constant.ExecutionPhase;
import com.example.agentx.domain.trace.model.ModelCallInfo;
import com.example.agentx.domain.trace.model.ToolCallInfo;
import com.example.agentx.domain.prompt.ConversationPromptTemplates;
import com.example.agentx.domain.prompt.PromptXmlUtils;
import com.example.agentx.domain.token.service.TokenEstimatorService;
import com.example.agentx.infrastructure.llm.config.ProviderConfig;
import com.example.agentx.infrastructure.llm.config.ProviderConfigFactory;
import com.example.agentx.infrastructure.rag.service.UserModelConfigResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import com.example.agentx.infrastructure.mq.core.MessageHeaders;
import com.example.agentx.infrastructure.utils.JsonUtils;

import javax.annotation.Nullable;
import java.math.BigDecimal;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public abstract class AbstractMessageHandler {

    /** 日志记录器 */
    private static final Logger logger = LoggerFactory.getLogger(AbstractMessageHandler.class);
    private static final int MEMORY_RECALL_CACHE_MAX_SIZE = 4096;

    /** 连接超时时间（毫秒） */
    protected static final long DEFAULT_CONNECTION_TIMEOUT = 300000L;

    protected final LLMServiceFactory llmServiceFactory;
    protected final MessageDomainService messageDomainService;
    protected final HighAvailabilityDomainService highAvailabilityDomainService;
    protected final SessionDomainService sessionDomainService;
    protected final UserSettingsDomainService userSettingsDomainService;
    protected final LLMDomainService llmDomainService;
    protected final BuiltInToolRegistry builtInToolRegistry;
    protected final BillingService billingService;
    protected final AccountDomainService accountDomainService;
    protected final ChatSessionManager chatSessionManager;
    @Autowired
    protected MemoryDomainService memoryDomainService;
    @Autowired
    protected MemoryExtractorService memoryExtractorService;
    @Autowired
    protected UserModelConfigResolver userModelConfigResolver;
    @Autowired
    protected TokenEstimatorService tokenEstimatorService;
    @Autowired
    protected ConversationAttachmentService conversationAttachmentService;
    @Autowired
    protected ProviderErrorClassifier providerErrorClassifier;
    @Autowired
    protected ChatMemoryAssembler chatMemoryAssembler;
    @Autowired
    protected ToolContextPromptAssembler toolContextPromptAssembler;
    @Autowired
    protected ToolExecutionSupport toolExecutionSupport;
    @Autowired
    protected ChatSseProperties chatSseProperties;
    @Autowired
    protected ChatToolProperties chatToolProperties;
    @Autowired
    protected LlmChatProperties llmChatProperties;
    @Autowired
    protected MemoryRecallProperties memoryRecallProperties;
    @Autowired
    protected SyncChatExecutor syncChatExecutor;
    @Autowired
    protected StreamingChatExecutor streamingChatExecutor;
    @Autowired
    protected AttachmentFallbackSupport attachmentFallbackSupport;
    @Autowired
    protected RagQueryRewriter ragQueryRewriter;
    @Autowired
    @Qualifier("sessionRenameTaskExecutor")
    protected TaskExecutor sessionRenameTaskExecutor;
    @Autowired
    @Qualifier("memoryRecallTaskExecutor")
    protected TaskExecutor memoryTaskExecutor;
    protected final ProviderConfigFactory providerConfigFactory;
    protected final ChatContextProperties chatContextProperties;
    private static final EnumSet<MemoryType> STABLE_MEMORY_TYPES = EnumSet.of(MemoryType.PROFILE, MemoryType.FACT);
    private final Set<String> renamingSessions = ConcurrentHashMap.newKeySet();
    private final Cache<String, CachedMemorySections> memoryRecallCache = CacheBuilder.newBuilder()
            .maximumSize(MEMORY_RECALL_CACHE_MAX_SIZE).build();
    private final Map<String, CachedToolCatalog> toolCatalogCache = new ConcurrentHashMap<>();
    private final ThreadLocal<List<CapturedToolExecution>> streamingToolExecutionCapture = new ThreadLocal<>();
    public AbstractMessageHandler(LLMServiceFactory llmServiceFactory, MessageDomainService messageDomainService,
            HighAvailabilityDomainService highAvailabilityDomainService, SessionDomainService sessionDomainService,
            UserSettingsDomainService userSettingsDomainService, LLMDomainService llmDomainService,
            BuiltInToolRegistry builtInToolRegistry, BillingService billingService,
            AccountDomainService accountDomainService, ChatSessionManager chatSessionManager,
            ProviderConfigFactory providerConfigFactory, ChatContextProperties chatContextProperties) {
        this.llmServiceFactory = llmServiceFactory;
        this.messageDomainService = messageDomainService;
        this.highAvailabilityDomainService = highAvailabilityDomainService;
        this.sessionDomainService = sessionDomainService;
        this.userSettingsDomainService = userSettingsDomainService;
        this.llmDomainService = llmDomainService;
        this.builtInToolRegistry = builtInToolRegistry;
        this.billingService = billingService;
        this.accountDomainService = accountDomainService;
        this.chatSessionManager = chatSessionManager;
        this.providerConfigFactory = providerConfigFactory;
        this.chatContextProperties = chatContextProperties;
    }

    protected long resolveConnectionTimeoutMs() {
        return chatSseProperties != null ? chatSseProperties.getTimeoutMs() : DEFAULT_CONNECTION_TIMEOUT;
    }

    protected int resolveMaxToolCallsPerTurn() {
        return chatToolProperties != null ? chatToolProperties.getMaxCalls() : 10;
    }

    /** 处理对话的模板方法
     *
     * @param chatContext 对话环境
     * @param transport 消息传输实现
     * @return 连接对象
     * @param <T> 连接类型 */
    public <T> T chat(ChatContext chatContext, MessageTransport<T> transport) {
        Map<String, String> previousMdc = MDC.getCopyOfContextMap();
        applyChatMdc(chatContext);
        // 1. 创建连接
        T connection = transport.createConnection(resolveConnectionTimeoutMs());
        ChatSessionManager.RegistrationStatus registrationStatus = registerSseSessionIfNecessary(chatContext, connection);
        if (registrationStatus == ChatSessionManager.RegistrationStatus.DUPLICATE_REJECTED) {
            return connection;
        }
        boolean registeredSseSession = registrationStatus == ChatSessionManager.RegistrationStatus.REGISTERED;

        try {
            // 2. 调用对话开始钩子
            onChatStart(chatContext);

            // 3. 检查用户余额是否足够
            checkBalanceBeforeChat(chatContext.getUserId(), transport, connection);

            // 3.1 预处理当前轮附件：文档先转摘要，图片保留多模态或后续降级
            prepareCurrentTurnAttachments(chatContext);

            // 4. 创建消息实体
            MessageEntity llmMessageEntity = createLlmMessage(chatContext);
            MessageEntity userMessageEntity = createUserMessage(chatContext);
            populateUserMessageTokenEstimate(chatContext, userMessageEntity);

            // 5. 调用用户消息处理完成钩子
            onUserMessageProcessed(chatContext, userMessageEntity);

            // 6. 异步预拉长期记忆，尽量与内存/工具初始化并行
            CompletableFuture<ChatMemoryAssembler.MemoryPromptSections> memorySectionFuture = prefetchMemorySections(chatContext);

            // 7. 初始化聊天内存
            MessageWindowChatMemory memory = initMemory(chatContext);

            // 8. 根据子类决定是否需要工具
            ToolProvider toolProvider = provideTools(chatContext);
            registerToolProviderResource(chatContext, toolProvider);
            chatContext.setToolCatalogPrompt(buildToolCatalogPrompt(chatContext, toolProvider));
            emitToolAvailabilityNotice(chatContext, connection, transport);

            // 9. 构建历史消息
            buildHistoryMessage(chatContext, memory, memorySectionFuture);

            // 10. 根据是否流式选择不同的处理方式
            if (chatContext.isStreaming()) {
                processStreamingChat(chatContext, connection, transport, userMessageEntity, llmMessageEntity, memory,
                        toolProvider);
            } else {
                processSyncChat(chatContext, connection, transport, userMessageEntity, llmMessageEntity, memory,
                        toolProvider);
            }

            return connection;
        } catch (RuntimeException e) {
            closeChatResources(chatContext);
            if (registeredSseSession) {
                chatSessionManager.removeSession(chatContext.getSessionId());
            }
            throw e;
        } finally {
            restoreMdc(previousMdc);
        }
    }

    private void applyChatMdc(ChatContext chatContext) {
        if (chatContext == null) {
            return;
        }
        String sessionId = chatContext.getSessionId();
        String traceId = StringUtils.isNotBlank(sessionId) ? sessionId : UUID.randomUUID().toString();
        MDC.put(MessageHeaders.TRACE_ID, traceId);
        if (StringUtils.isNotBlank(chatContext.getUserId())) {
            MDC.put("userId", chatContext.getUserId());
        }
        if (chatContext.getAgent() != null && StringUtils.isNotBlank(chatContext.getAgent().getId())) {
            MDC.put("agentId", chatContext.getAgent().getId());
        }
    }

    private void restoreMdc(Map<String, String> previousMdc) {
        MDC.clear();
        if (previousMdc != null) {
            MDC.setContextMap(previousMdc);
        }
    }

    protected void runWithMdc(Map<String, String> contextMap, Runnable runnable) {
        Map<String, String> previousMdc = MDC.getCopyOfContextMap();
        try {
            setMdcContext(contextMap);
            runnable.run();
        } finally {
            restoreMdc(previousMdc);
        }
    }

    private <R> R callWithMdc(Map<String, String> contextMap, Supplier<R> supplier) {
        Map<String, String> previousMdc = MDC.getCopyOfContextMap();
        try {
            setMdcContext(contextMap);
            return supplier.get();
        } finally {
            restoreMdc(previousMdc);
        }
    }

    private void setMdcContext(Map<String, String> contextMap) {
        MDC.clear();
        if (contextMap != null) {
            MDC.setContextMap(contextMap);
        }
    }

    private <T> ChatSessionManager.RegistrationStatus registerSseSessionIfNecessary(ChatContext chatContext, T connection) {
        if (!(connection instanceof SseEmitter emitter) || !chatContext.isStreaming()
                || StringUtils.isBlank(chatContext.getSessionId())) {
            return ChatSessionManager.RegistrationStatus.NOT_REQUIRED;
        }
        return chatSessionManager.registerSession(chatContext.getSessionId(), chatContext.getTurnId(), emitter);
    }

    protected boolean isChatInterrupted(ChatContext chatContext) {
        return chatContext != null && chatContext.isStreaming() && StringUtils.isNotBlank(chatContext.getSessionId())
                && chatSessionManager.isSessionInterrupted(chatContext.getSessionId(), chatContext.getTurnId());
    }

    protected <T> void completeInterruptedChat(ChatContext chatContext, T connection, MessageTransport<T> transport) {
        logger.info("跳过已中断对话的后续处理: sessionId={}, userId={}", chatContext.getSessionId(),
                chatContext.getUserId());
        onChatCompleted(chatContext, false, "interrupted");
        transport.completeConnection(connection);
    }

    /** 追踪钩子方法 - 对话开始时调用 子类可以覆盖此方法实现追踪逻辑
     * 
     * @param chatContext 对话上下文 */
    protected void onChatStart(ChatContext chatContext) {
        // 默认空实现，子类可选择性覆盖
    }

    /** 追踪钩子方法 - 用户消息处理完成时调用
     * 
     * @param chatContext 对话上下文
     * @param userMessage 用户消息实体 */
    protected void onUserMessageProcessed(ChatContext chatContext, MessageEntity userMessage) {
        // 默认空实现，子类可选择性覆盖
    }

    /** 追踪钩子方法 - 模型调用完成时调用
     * 
     * @param chatContext 对话上下文
     * @param chatResponse 模型响应
     * @param modelCallInfo 模型调用信息 */
    protected void onModelCallCompleted(ChatContext chatContext, ChatResponse chatResponse,
            ModelCallInfo modelCallInfo) {
        // 默认空实现，子类可选择性覆盖
    }

    /** 追踪钩子方法 - 工具调用完成时调用
     * 
     * @param chatContext 对话上下文
     * @param toolCallInfo 工具调用信息 */
    protected void onToolCallCompleted(ChatContext chatContext, ToolCallInfo toolCallInfo) {
        // 默认空实现，子类可选择性覆盖
    }

    /** 追踪钩子方法 - 对话完成时调用
     * 
     * @param chatContext 对话上下文
     * @param success 是否成功
     * @param errorMessage 错误信息（成功时为null） */
    protected void onChatCompleted(ChatContext chatContext, boolean success, String errorMessage) {
        closeChatResources(chatContext);
        // 对话完成钩子：成功时进行记忆抽取（异步）；RAG/公开访问跳过
        if (!success || chatContext == null)
            return;
        if (chatContext.isPublicAccess())
            return;
        if (chatContext instanceof RagChatContext)
            return;

        String userId = chatContext.getUserId();
        String sessionId = chatContext.getSessionId();
        String userText = buildMemoryExtractionUserText(chatContext);
        String assistantText = StringUtils.defaultString(chatContext.getCurrentAssistantReply(), "").trim();
        if (StringUtils.isBlank(userId) || StringUtils.isBlank(sessionId) || StringUtils.isBlank(userText))
            return;

        // 直接调用异步方法，避免阻塞主流程
        try {
            String scopeAgentId = chatContext.getAgent() != null ? chatContext.getAgent().getId() : null;
            memoryExtractorService.extractAndPersistAsync(userId, sessionId, scopeAgentId, userText, assistantText,
                    buildMemoryExtractionRecentHistory(chatContext));
        } catch (Exception ignore) {
            // 异步任务调度异常不影响主流程
        }
    }

    private String buildMemoryExtractionUserText(ChatContext chatContext) {
        if (chatContext == null) {
            return "";
        }
        String userText = StringUtils.defaultString(chatContext.getUserMessage(), "").trim();
        String attachmentContext = conversationAttachmentService
                .buildCurrentTurnAttachmentText(chatContext.getAttachments(), true);
        if (StringUtils.isBlank(attachmentContext)) {
            return userText;
        }
        if (StringUtils.isBlank(userText)) {
            return attachmentContext.trim();
        }
        return userText + "\n\n" + attachmentContext.trim();
    }

    /** 追踪钩子方法 - 发生异常时调用
     * 
     * @param chatContext 对话上下文
     * @param errorPhase 错误阶段
     * @param throwable 异常信息 */
    protected void onChatError(ChatContext chatContext, ExecutionPhase errorPhase, Throwable throwable) {
        closeChatResources(chatContext);
        // 默认空实现，子类可选择性覆盖
    }

    /** 子类可以覆盖这个方法提供工具 */
    protected ToolProvider provideTools(ChatContext chatContext) {
        return null; // 默认不提供工具
    }

    /** 流式聊天处理 */
    protected <T> void processStreamingChat(ChatContext chatContext, T connection, MessageTransport<T> transport,
            MessageEntity userEntity, MessageEntity llmEntity, MessageWindowChatMemory memory,
            ToolProvider toolProvider) {

        // 获取流式LLM客户端
        StreamingChatModel streamingClient = llmServiceFactory.getStreamingClient(chatContext.getProvider(),
                chatContext.getModel());

        List<CapturedToolExecution> capturedToolExecutions = toolExecutionSupport.newCaptureBuffer();
        ToolProgressStreamState toolProgressStreamState = new ToolProgressStreamState();
        ToolExecutionProgressListener progressListener = buildToolExecutionProgressListener(connection, transport,
                chatContext, toolProgressStreamState);
        Agent agent = buildStreamingAgent(streamingClient, memory, toolProvider, chatContext.getAgent(),
                capturedToolExecutions, progressListener);

        streamingToolExecutionCapture.set(capturedToolExecutions);
        try {
            // 使用现有的流式处理逻辑
            processChat(agent, connection, transport, chatContext, userEntity, llmEntity, memory, toolProvider,
                    toolProgressStreamState);
        } finally {
            streamingToolExecutionCapture.remove();
        }
    }

    /** 同步聊天处理 */
    protected <T> void processSyncChat(ChatContext chatContext, T connection, MessageTransport<T> transport,
            MessageEntity userEntity, MessageEntity llmEntity, MessageWindowChatMemory memory,
            ToolProvider toolProvider) {
        syncChatExecutor.execute(this, chatContext, connection, transport, userEntity, llmEntity, memory,
                toolProvider);
    }

    /** 保存用户、摘要消息记录，延迟到本轮结束时再刷新上下文
     * @param chatContext 对话环境
     * @param userEntity 此次的用户消息 */
    protected void saveUserMessage(ChatContext chatContext, MessageEntity userEntity) {
        ContextEntity contextEntity = chatContext.getContextEntity();
        MessageEntity summary = ConversationHistoryUtils.getSummaryFromHistory(chatContext.getMessageHistory());
        if (summary != null) {
            // 不重置 created_at 字段
            messageDomainService.saveMessage(Collections.singletonList(summary));
        }
        contextEntity.setActiveWindowStartMessageId(resolveHistoricalWindowStartMessageId(chatContext, userEntity));
        contextEntity.setActiveMessages(null);
        messageDomainService.saveMessage(Collections.singletonList(userEntity));
        conversationAttachmentService.bindImageSummaryPersistence(chatContext.getUserId(), userEntity);
    }

    /** 子类实现具体的聊天处理逻辑 */
    protected <T> void processChat(Agent agent, T connection, MessageTransport<T> transport, ChatContext chatContext,
            MessageEntity userEntity, MessageEntity llmEntity, MessageWindowChatMemory memory, ToolProvider toolProvider,
            ToolProgressStreamState toolProgressStreamState) {
        streamingChatExecutor.executeTokenStream(this, agent, connection, transport, chatContext, userEntity,
                llmEntity, memory, toolProvider, toolProgressStreamState);
    }

    protected <T> boolean tryRecoverSyncWithAttachmentFallback(ChatContext chatContext, T connection,
            MessageTransport<T> transport, MessageEntity userEntity, MessageEntity llmEntity,
            MessageWindowChatMemory memory, Exception exception, long startTime) {
        return attachmentFallbackSupport.tryRecoverSync(this, chatContext, connection, transport, userEntity, llmEntity,
                memory, exception, startTime);
    }

    protected <T> boolean tryRecoverStreamingWithAttachmentFallback(ChatContext chatContext, T connection,
            MessageTransport<T> transport, MessageEntity userEntity, MessageEntity llmEntity,
            MessageWindowChatMemory memory, Throwable throwable, long startTime) {
        return attachmentFallbackSupport.tryRecoverStreaming(this, chatContext, connection, transport, userEntity,
                llmEntity, memory, throwable, startTime);
    }

    protected ChatResponse executeSyncFallbackChat(ChatContext chatContext, MessageWindowChatMemory memory) {
        return attachmentFallbackSupport.executeSyncFallbackChat(this, chatContext, memory);
    }

    protected boolean isEmptyStreamingResponse(ChatResponse chatResponse, StringBuilder streamedText) {
        if (streamedText != null && StringUtils.isNotBlank(streamedText.toString())) {
            return false;
        }
        return isBlankAssistantResponse(chatResponse);
    }

    private boolean isBlankAssistantResponse(ChatResponse chatResponse) {
        return chatResponse == null || chatResponse.aiMessage() == null
                || StringUtils.isBlank(chatResponse.aiMessage().text());
    }

    protected <T> void completeFallbackStreamingResponse(ChatContext chatContext, T connection,
            MessageTransport<T> transport, MessageEntity userEntity, MessageEntity llmEntity, ChatResponse chatResponse,
            long startTime) {
        attachmentFallbackSupport.completeFallbackStreamingResponse(this, chatContext, connection, transport, userEntity,
                llmEntity, chatResponse, startTime);
    }

    protected ChatResponse invokeSyncChatWithFriendlyError(ChatContext chatContext, ChatModel syncClient,
            List<ChatMessage> messages) {
        try {
            return syncClient.chat(messages);
        } catch (Exception e) {
            throw normalizeModelCallException(chatContext, e);
        }
    }

    protected Throwable normalizeModelCallThrowable(ChatContext chatContext, Throwable throwable) {
        return attachmentFallbackSupport.normalizeModelCallThrowable(this, chatContext, throwable);
    }

    protected RuntimeException normalizeModelCallException(ChatContext chatContext, Exception exception) {
        return attachmentFallbackSupport.normalizeModelCallException(this, chatContext, exception);
    }

    /** 根据历史消息的本体token算出本次消息的本体token
     * @param historyMessages 历史消息列表
     * @param userEntity 用户请求消息实体
     * @param llmEntity llm回复消息实体
     * @param chatResponse llm响应 */
    protected void setMessageTokenCount(ChatContext chatContext, List<MessageEntity> historyMessages,
            MessageEntity userEntity, MessageEntity llmEntity, ChatResponse chatResponse) {
        String aiText = chatResponse.aiMessage() != null ? chatResponse.aiMessage().text() : "";
        llmEntity.setContent(aiText);
        int estimatedOutputTokens = estimateMessageBodyTokens(chatContext, aiText);
        Integer outputTokens = ChatResponseTokenUsageUtils.outputTokenCount(chatResponse);
        int resolvedOutputTokens = outputTokens != null && outputTokens > 0 ? outputTokens : estimatedOutputTokens;
        llmEntity.setBodyTokenCount(resolvedOutputTokens);
        llmEntity.setTokenCount(resolvedOutputTokens);
        int estimatedUserBodyTokens = userEntity.getBodyTokenCount() != null && userEntity.getBodyTokenCount() > 0
                ? userEntity.getBodyTokenCount()
                : estimateMessageBodyTokens(chatContext, userEntity.getContent());
        userEntity.setBodyTokenCount(estimatedUserBodyTokens);
        userEntity.setTokenCount(estimatedUserBodyTokens);
    }

    protected void updateCurrentAssistantReply(ChatContext chatContext, ChatResponse chatResponse) {
        if (chatContext == null) {
            return;
        }
        String aiText = chatResponse != null && chatResponse.aiMessage() != null ? chatResponse.aiMessage().text() : "";
        chatContext.setCurrentAssistantReply(aiText);
    }

    private List<String> buildMemoryExtractionRecentHistory(ChatContext chatContext) {
        if (chatContext == null) {
            return Collections.emptyList();
        }

        List<String> recentHistory = ConversationHistoryUtils.stripSummaryMessages(chatContext.getMessageHistory()).stream()
                .filter(Objects::nonNull)
                .filter(this::shouldUseForMemoryExtractionHistory)
                .filter(message -> message.getRole() == Role.USER || message.getRole() == Role.ASSISTANT)
                .map(this::formatMemoryHistoryMessage)
                .filter(StringUtils::isNotBlank)
                .reduce(new ArrayDeque<String>(), (deque, item) -> {
                    if (deque.size() == 3) {
                        deque.pollFirst();
                    }
                    deque.addLast(item);
                    return deque;
                }, (left, right) -> {
                    right.forEach(item -> {
                        if (left.size() == 3) {
                            left.pollFirst();
                        }
                        left.addLast(item);
                    });
                    return left;
                }).stream().toList();
        String summary = resolveConversationSummaryText(chatContext);
        if (StringUtils.isBlank(summary)) {
            return recentHistory;
        }
        List<String> combinedHistory = new ArrayList<>(recentHistory.size() + 1);
        combinedHistory.add("summary: " + abbreviatePromptText(summary, 180));
        combinedHistory.addAll(recentHistory);
        return combinedHistory;
    }

    private boolean shouldUseForMemoryExtractionHistory(MessageEntity messageEntity) {
        if (messageEntity == null || messageEntity.isSummaryMessage()) {
            return false;
        }
        MessageType messageType = messageEntity.getMessageType();
        return messageType == null || messageType == MessageType.TEXT;
    }

    private String formatMemoryHistoryMessage(MessageEntity message) {
        if (message == null || StringUtils.isBlank(message.getContent())) {
            return null;
        }
        String roleLabel = switch (message.getRole()) {
            case USER -> "user";
            case ASSISTANT -> "assistant";
            default -> null;
        };
        if (roleLabel == null) {
            return null;
        }
        String content = message.getContent().replace("\r", " ").replace("\n", " ").trim();
        if (content.length() > 160) {
            content = content.substring(0, 157) + "...";
        }
        return roleLabel + ": " + content;
    }

    /** 初始化内存 */
    protected MessageWindowChatMemory initMemory(ChatContext chatContext) {
        return chatMemoryAssembler.initMemory(chatContext);
    }

    /** 构建流式Agent */
    protected Agent buildStreamingAgent(StreamingChatModel model, MessageWindowChatMemory memory,
            ToolProvider toolProvider, AgentEntity agent, List<CapturedToolExecution> capturedToolExecutions,
            ToolExecutionProgressListener progressListener) {
        ToolExecutionSupport.ToolingBundle toolingBundle = toolExecutionSupport.prepareTooling(agent, toolProvider,
                capturedToolExecutions, progressListener);
        Map<ToolSpecification, ToolExecutor> builtInTools = toolingBundle.builtInTools();

        AiServices<Agent> agentService = AiServices.builder(Agent.class).streamingChatModel(model).chatMemory(memory);
        agentService.maxSequentialToolsInvocations(Math.max(1, resolveMaxToolCallsPerTurn()));

        // 添加内置工具（如RAG等）
        if (builtInTools != null && !builtInTools.isEmpty()) {
            agentService.tools(builtInTools);
        }

        // 添加外部工具提供者
        if (toolingBundle.toolProvider() != null) {
            agentService.toolProvider(toolingBundle.toolProvider());
        }

        return agentService.build();
    }

    protected SyncAgent buildSyncAgent(ChatModel model, MessageWindowChatMemory memory, ToolProvider toolProvider,
            AgentEntity agent, List<CapturedToolExecution> capturedToolExecutions) {
        ToolExecutionSupport.ToolingBundle toolingBundle = toolExecutionSupport.prepareTooling(agent, toolProvider,
                capturedToolExecutions);
        Map<ToolSpecification, ToolExecutor> builtInTools = toolingBundle.builtInTools();

        AiServices<SyncAgent> agentService = AiServices.builder(SyncAgent.class).chatModel(model).chatMemory(memory)
                .maxSequentialToolsInvocations(Math.max(1, resolveMaxToolCallsPerTurn()));

        if (!builtInTools.isEmpty()) {
            agentService.tools(builtInTools);
        }

        if (toolingBundle.toolProvider() != null) {
            agentService.toolProvider(toolingBundle.toolProvider());
        }
        return agentService.build();
    }

    protected boolean hasAvailableTools(ChatContext chatContext, ToolProvider toolProvider) {
        return toolProvider != null || (chatContext != null && chatContext.getAgent() != null
                && builtInToolRegistry.hasToolsForAgent(chatContext.getAgent()));
    }

    protected List<MessageEntity> buildToolMessagesFromExecutions(ChatContext chatContext,
            List<CapturedToolExecution> capturedToolExecutions) {
        return toolExecutionSupport.buildToolMessagesFromExecutions(chatContext, capturedToolExecutions,
                () -> createLlmMessage(chatContext),
                toolMessage -> populateAssistantMessageTokenEstimate(chatContext, toolMessage));
    }

    protected ChatResponse buildSyntheticChatResponse(String answer) {
        return ChatResponse.builder().aiMessage(new AiMessage(StringUtils.defaultString(answer))).build();
    }

    /** 创建用户消息实体 */
    protected MessageEntity createUserMessage(ChatContext environment) {
        MessageEntity messageEntity = new MessageEntity();
        messageEntity.setRole(Role.USER);
        messageEntity.setContent(environment.getUserMessage());
        messageEntity.setSessionId(environment.getSessionId());
        applyAttachmentsToMessage(messageEntity, environment.getAttachments());
        return messageEntity;
    }

    private void prepareCurrentTurnAttachments(ChatContext chatContext) {
        if (chatContext == null) {
            return;
        }
        if (!isAttachmentInputEnabled(chatContext)) {
            chatContext.setPreferImageOcrContext(false);
            chatContext.setAttachments(Collections.emptyList());
            chatContext.setFileUrls(Collections.emptyList());
            return;
        }
        boolean preferImageOcrContext = conversationAttachmentService.shouldPreferImageOcrContext(
                chatContext.getUserMessage(), chatContext.getAttachments());
        List<ConversationAttachment> prepared = conversationAttachmentService.prepareCurrentTurnAttachments(
                chatContext.getUserId(),
                chatContext.getUserMessage(),
                preferImageOcrContext,
                conversationAttachmentService.normalizeAttachments(chatContext.getAttachments(), chatContext.getFileUrls()));
        chatContext.setPreferImageOcrContext(preferImageOcrContext);
        chatContext.setAttachments(prepared);
        chatContext.setFileUrls(extractAttachmentUrls(prepared));
    }

    private boolean isAttachmentInputEnabled(ChatContext chatContext) {
        if (chatContext == null || chatContext.getAgent() == null) {
            return true;
        }
        return Boolean.TRUE.equals(chatContext.getAgent().getMultiModal());
    }

    protected void applyAttachmentsToMessage(MessageEntity messageEntity, List<ConversationAttachment> attachments) {
        List<ConversationAttachment> normalized = conversationAttachmentService.normalizeAttachments(attachments, null);
        messageEntity.setAttachments(normalized);
        messageEntity.setFileUrls(extractAttachmentUrls(normalized));
    }

    private List<ConversationAttachment> resolveAttachments(MessageEntity messageEntity) {
        if (messageEntity == null) {
            return Collections.emptyList();
        }
        return conversationAttachmentService.normalizeAttachments(messageEntity.getAttachments(), messageEntity.getFileUrls());
    }

    protected List<String> extractAttachmentUrls(List<ConversationAttachment> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return Collections.emptyList();
        }
        return attachments.stream().map(ConversationAttachment::getUrl).filter(StringUtils::isNotBlank).toList();
    }

    private void populateUserMessageTokenEstimate(ChatContext chatContext, MessageEntity userEntity) {
        int estimatedTokens = estimateMessageBodyTokens(chatContext, userEntity.getContent());
        userEntity.setBodyTokenCount(estimatedTokens);
        userEntity.setTokenCount(estimatedTokens);
    }

    protected void populateAssistantMessageTokenEstimate(ChatContext chatContext, MessageEntity assistantEntity) {
        int estimatedTokens = assistantEntity != null && assistantEntity.getMessageType() == MessageType.TOOL_CALL
                ? tokenEstimatorService
                        .estimateTextTokenCountHeuristically(assistantEntity.getContent())
                : estimateMessageBodyTokens(chatContext, assistantEntity.getContent());
        assistantEntity.setBodyTokenCount(estimatedTokens);
        assistantEntity.setTokenCount(estimatedTokens);
    }

    protected List<MessageEntity> drainBufferedMessages(List<MessageEntity> bufferedMessages) {
        synchronized (bufferedMessages) {
            List<MessageEntity> drained = new ArrayList<>(bufferedMessages);
            bufferedMessages.clear();
            return drained;
        }
    }

    protected void persistPartialStreamingMessagesOnError(ChatContext chatContext, MessageEntity userEntity,
            StringBuilder messageBuilder, List<MessageEntity> bufferedMessages) {
        try {
            List<MessageEntity> messagesToSave = drainBufferedMessages(bufferedMessages);
            String partialContent = messageBuilder != null ? messageBuilder.toString() : "";
            if (StringUtils.isNotBlank(partialContent)) {
                MessageEntity partialAssistant = createLlmMessage(chatContext);
                partialAssistant.setContent(partialContent);
                partialAssistant.setMessageType(MessageType.ERROR);
                populateAssistantMessageTokenEstimate(chatContext, partialAssistant);
                messagesToSave.add(partialAssistant);
            }
            if (messagesToSave.isEmpty()) {
                prepareContextForCurrentTurn(chatContext, userEntity);
                messageDomainService.finalizeTurn(chatContext.getContextEntity(), Collections.singletonList(userEntity),
                        Collections.emptyList());
                return;
            }
            prepareContextForCurrentTurn(chatContext, userEntity);
            messageDomainService.finalizeTurn(chatContext.getContextEntity(), Collections.singletonList(userEntity),
                    messagesToSave);
        } catch (Exception e) {
            logger.error("保存流式失败时的部分响应失败: sessionId={}, userId={}",
                    chatContext != null ? chatContext.getSessionId() : null,
                    chatContext != null ? chatContext.getUserId() : null, e);
        }
    }

    protected int estimateMessageBodyTokens(ChatContext chatContext, String content) {
        return tokenEstimatorService.estimateTextTokenCount(content, buildProviderConfig(chatContext));
    }

    protected ProviderConfig buildProviderConfig(ChatContext chatContext) {
        if (chatContext == null) {
            return null;
        }
        ProviderConfig cached = chatContext.getResolvedProviderConfig();
        if (cached != null) {
            return cached;
        }
        ProviderConfig resolved = providerConfigFactory.fromChatContext(chatContext);
        chatContext.setResolvedProviderConfig(resolved);
        return resolved;
    }

    protected void beginStreamingToolCapture(List<CapturedToolExecution> capturedToolExecutions) {
        streamingToolExecutionCapture.set(capturedToolExecutions);
    }

    protected List<CapturedToolExecution> currentStreamingToolCapture() {
        return streamingToolExecutionCapture.get();
    }

    protected void clearStreamingToolCapture() {
        streamingToolExecutionCapture.remove();
    }

    protected void prepareContextForCurrentTurn(ChatContext chatContext, MessageEntity userEntity) {
        if (chatContext == null || chatContext.getContextEntity() == null) {
            return;
        }
        ContextEntity contextEntity = chatContext.getContextEntity();
        if (!contextEntity.hasActiveWindowStartMessageId()) {
            contextEntity.setActiveWindowStartMessageId(resolveHistoricalWindowStartMessageId(chatContext, userEntity));
        }
        contextEntity.setActiveMessages(null);
    }

    private String resolveHistoricalWindowStartMessageId(ChatContext chatContext, MessageEntity fallbackMessage) {
        if (chatContext != null && chatContext.getContextEntity() != null
                && chatContext.getContextEntity().hasActiveWindowStartMessageId()) {
            return chatContext.getContextEntity().getActiveWindowStartMessageId();
        }
        if (chatContext != null && chatContext.getMessageHistory() != null) {
            Optional<String> firstHistoryMessageId = chatContext.getMessageHistory().stream().filter(Objects::nonNull)
                    .filter(message -> StringUtils.isNotBlank(message.getId()))
                    .sorted(Comparator.comparing(MessageEntity::getCreatedAt))
                    .map(MessageEntity::getId)
                    .findFirst();
            if (firstHistoryMessageId.isPresent()) {
                return firstHistoryMessageId.get();
            }
        }
        return fallbackMessage != null ? fallbackMessage.getId() : null;
    }

    /** 创建LLM消息实体 */
    protected MessageEntity createLlmMessage(ChatContext environment) {
        MessageEntity messageEntity = new MessageEntity();
        messageEntity.setRole(Role.ASSISTANT);
        messageEntity.setSessionId(environment.getSessionId());
        messageEntity.setModel(environment.getModel().getModelId());
        messageEntity.setProvider(environment.getProvider().getId());
        messageEntity.setOriginalProviderId(environment.getOriginalProvider() != null
                ? environment.getOriginalProvider().getId()
                : environment.getProvider().getId());
        return messageEntity;
    }

    /** 构建历史消息到内存中 */
    protected void buildHistoryMessage(ChatContext chatContext, MessageWindowChatMemory memory) {
        buildHistoryMessage(chatContext, memory, null);
    }

    protected void buildHistoryMessage(ChatContext chatContext, MessageWindowChatMemory memory,
            CompletableFuture<ChatMemoryAssembler.MemoryPromptSections> memorySectionFuture) {
        chatMemoryAssembler.buildHistoryMessage(chatContext, memory,
                resolveMemorySections(chatContext, memorySectionFuture),
                toolContextPromptAssembler.buildRecentToolContextSection(chatContext),
                resolveMaxToolCallsPerTurn());
    }

    private CompletableFuture<ChatMemoryAssembler.MemoryPromptSections> prefetchMemorySections(ChatContext chatContext) {
        if (chatContext == null || memoryDomainService == null || memoryTaskExecutor == null) {
            return CompletableFuture.completedFuture(ChatMemoryAssembler.MemoryPromptSections.empty());
        }
        try {
            Map<String, String> taskMdc = MDC.getCopyOfContextMap();
            return CompletableFuture
                    .supplyAsync(() -> callWithMdc(taskMdc, () -> buildMemoryPromptSections(chatContext)),
                    memoryTaskExecutor);
        } catch (RuntimeException e) {
            logger.warn("提交记忆召回预拉任务失败: sessionId={}, userId={}, err={}", chatContext.getSessionId(),
                    chatContext.getUserId(), e.getMessage());
            return CompletableFuture.completedFuture(ChatMemoryAssembler.MemoryPromptSections.empty());
        }
    }

    private ChatMemoryAssembler.MemoryPromptSections resolveMemorySections(ChatContext chatContext,
            CompletableFuture<ChatMemoryAssembler.MemoryPromptSections> memorySectionFuture) {
        if (memorySectionFuture == null) {
            return buildMemoryPromptSections(chatContext);
        }
        try {
            long timeoutMs = Math.max(100, memoryRecallProperties.getTimeoutMs());
            ChatMemoryAssembler.MemoryPromptSections sections = memorySectionFuture.get(timeoutMs, TimeUnit.MILLISECONDS);
            return sections == null ? ChatMemoryAssembler.MemoryPromptSections.empty() : sections;
        } catch (TimeoutException e) {
            logger.warn("记忆召回超时，跳过本轮注入: sessionId={}, userId={}, timeoutMs={}",
                    chatContext.getSessionId(), chatContext.getUserId(), memoryRecallProperties.getTimeoutMs());
            memorySectionFuture.cancel(true);
            return getCachedMemorySections(chatContext).orElse(ChatMemoryAssembler.MemoryPromptSections.empty());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return getCachedMemorySections(chatContext).orElse(ChatMemoryAssembler.MemoryPromptSections.empty());
        } catch (CompletionException e) {
            logger.warn("记忆召回预拉失败: sessionId={}, userId={}, err={}", chatContext.getSessionId(),
                    chatContext.getUserId(), e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
            return getCachedMemorySections(chatContext).orElse(ChatMemoryAssembler.MemoryPromptSections.empty());
        } catch (Exception e) {
            logger.warn("记忆召回预拉失败: sessionId={}, userId={}, err={}", chatContext.getSessionId(),
                    chatContext.getUserId(), e.getMessage());
            return getCachedMemorySections(chatContext).orElse(ChatMemoryAssembler.MemoryPromptSections.empty());
        }
    }

    protected UserMessage buildCurrentUserMessage(ChatContext chatContext) {
        return chatMemoryAssembler.buildCurrentUserMessage(chatContext);
    }

    protected UserMessage buildUserMessage(String text, List<ConversationAttachment> attachments,
            boolean allowImageInputs) {
        return chatMemoryAssembler.buildUserMessage(text, attachments, allowImageInputs);
    }

    protected Duration resolveSyncModelTimeout(ChatContext chatContext) {
        long baseSeconds = Math.max(1L, llmServiceFactory.getDefaultChatTimeoutSeconds());
        if (!hasAttachmentInputs(chatContext)) {
            return Duration.ofSeconds(baseSeconds);
        }
        return Duration.ofSeconds(baseSeconds + Math.max(0L, llmChatProperties.getAttachmentExtraTimeoutSeconds()));
    }

    protected Duration resolveStreamingModelTimeout(ChatContext chatContext) {
        long baseSeconds = Math.max(1L, llmServiceFactory.getDefaultStreamingTimeoutSeconds());
        if (!hasAttachmentInputs(chatContext)) {
            return Duration.ofSeconds(baseSeconds);
        }
        return Duration.ofSeconds(baseSeconds + Math.max(0L, llmChatProperties.getAttachmentExtraStreamTimeoutSeconds()));
    }

    protected boolean hasAttachmentInputs(ChatContext chatContext) {
        return chatContext != null && CollectionUtil.isNotEmpty(chatContext.getAttachments());
    }

    private String buildToolCatalogPrompt(ChatContext chatContext, @Nullable ToolProvider toolProvider) {
        if (!chatToolProperties.isIncludeCatalogPrompt()) {
            return "";
        }
        if (chatContext == null || chatContext.getAgent() == null) {
            return "";
        }

        Set<String> presetEnabledToolNames = resolvePresetEnabledToolNames(chatContext.getAgent().getToolPresetParams());
        String cacheKey = buildToolCatalogCacheKey(chatContext, presetEnabledToolNames);
        Optional<String> cachedCatalog = getCachedToolCatalog(cacheKey);
        if (cachedCatalog.isPresent()) {
            return cachedCatalog.get();
        }

        LinkedHashMap<String, ConversationPromptTemplates.ToolCatalogItem> catalogItems = new LinkedHashMap<>();

        Map<ToolSpecification, ToolExecutor> builtInTools = builtInToolRegistry.createToolsForAgent(chatContext.getAgent());
        builtInTools.keySet().stream().sorted(Comparator.comparing(ToolSpecification::name, String.CASE_INSENSITIVE_ORDER))
                .forEach(toolSpecification -> mergeToolCatalogItem(catalogItems, toolSpecification,
                        presetEnabledToolNames.contains(toolSpecification.name()), "内置"));

        if (toolProvider != null) {
            Optional<ToolProviderResult> cachedExternalTools = getCachedExternalTools(toolProvider);
            if (cachedExternalTools.isPresent()) {
                ToolProviderResult externalTools = cachedExternalTools.get();
                if (externalTools != null && externalTools.tools() != null && !externalTools.tools().isEmpty()) {
                    externalTools.tools().keySet().stream()
                            .sorted(Comparator.comparing(ToolSpecification::name, String.CASE_INSENSITIVE_ORDER))
                            .forEach(toolSpecification -> {
                                if (!catalogItems.containsKey(toolSpecification.name())) {
                                    mergeToolCatalogItem(catalogItems, toolSpecification,
                                            presetEnabledToolNames.contains(toolSpecification.name()), "外部");
                                }
                            });
                }
            }
        }

        String catalogPrompt = ConversationPromptTemplates.generateToolCatalogPrompt(new ArrayList<>(catalogItems.values()));
        cacheToolCatalog(cacheKey, catalogPrompt);
        return catalogPrompt;
    }

    private Optional<ToolProviderResult> getCachedExternalTools(ToolProvider toolProvider) {
        if (toolProvider instanceof ManagedMcpToolProvider managedMcpToolProvider) {
            return managedMcpToolProvider.getCachedToolProviderResult();
        }
        return Optional.empty();
    }

    private Optional<String> getCachedToolCatalog(String cacheKey) {
        if (StringUtils.isBlank(cacheKey)) {
            return Optional.empty();
        }
        CachedToolCatalog cached = toolCatalogCache.get(cacheKey);
        if (cached == null) {
            return Optional.empty();
        }
        if (cached.expireAtMillis() < System.currentTimeMillis()) {
            toolCatalogCache.remove(cacheKey, cached);
            return Optional.empty();
        }
        return Optional.ofNullable(cached.catalogPrompt());
    }

    private void cacheToolCatalog(String cacheKey, String catalogPrompt) {
        if (StringUtils.isBlank(cacheKey)) {
            return;
        }
        long ttlMs = Math.max(1000L, chatToolProperties.getCatalogCacheTtlMs());
        toolCatalogCache.put(cacheKey, new CachedToolCatalog(StringUtils.defaultString(catalogPrompt),
                System.currentTimeMillis() + ttlMs));
    }

    private String buildToolCatalogCacheKey(ChatContext chatContext, Set<String> presetEnabledToolNames) {
        if (chatContext == null || chatContext.getAgent() == null) {
            return null;
        }
        AgentEntity agent = chatContext.getAgent();
        List<String> toolIds = agent.getToolIds() == null ? Collections.emptyList() : new ArrayList<>(agent.getToolIds());
        Collections.sort(toolIds);
        List<String> mcpServerNames = chatContext.getMcpServerNames() == null ? Collections.emptyList()
                : new ArrayList<>(chatContext.getMcpServerNames());
        Collections.sort(mcpServerNames);
        List<String> presetNames = presetEnabledToolNames == null ? Collections.emptyList()
                : new ArrayList<>(presetEnabledToolNames);
        Collections.sort(presetNames);
        return String.join("|",
                StringUtils.defaultString(chatContext.getUserId()),
                StringUtils.defaultString(agent.getId()),
                "tools=" + Integer.toHexString(toolIds.toString().hashCode()),
                "mcp=" + Integer.toHexString(mcpServerNames.toString().hashCode()),
                "preset=" + Integer.toHexString(presetNames.toString().hashCode()));
    }

    private String abbreviatePromptText(String value, int limit) {
        return ConversationPromptContextUtils.abbreviatePromptText(value, limit);
    }

    private void mergeToolCatalogItem(
            Map<String, ConversationPromptTemplates.ToolCatalogItem> catalogItems,
            ToolSpecification toolSpecification, boolean presetEnabled, String source) {
        if (catalogItems == null || toolSpecification == null || StringUtils.isBlank(toolSpecification.name())) {
            return;
        }
        catalogItems.putIfAbsent(toolSpecification.name(),
                new ConversationPromptTemplates.ToolCatalogItem(toolSpecification.name(),
                        StringUtils.defaultIfBlank(toolSpecification.description(), "未提供描述"), presetEnabled, source));
    }

    private Set<String> resolvePresetEnabledToolNames(
            Map<String, Map<String, Map<String, String>>> toolPresetParams) {
        if (toolPresetParams == null || toolPresetParams.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> toolNames = new LinkedHashSet<>();
        for (Map<String, Map<String, String>> presetGroup : toolPresetParams.values()) {
            if (presetGroup == null || presetGroup.isEmpty()) {
                continue;
            }
            presetGroup.keySet().stream().filter(StringUtils::isNotBlank).map(String::trim).forEach(toolNames::add);
        }
        return toolNames;
    }

    private String buildHistoricalImageReference(List<String> fileUrls) {
        if (CollectionUtil.isEmpty(fileUrls)) {
            return "";
        }

        int maxReferencedImages = chatContextProperties.getHistory().getHistoricalImageReferenceLimit();
        long validImageCount = fileUrls.stream().filter(StringUtils::isNotBlank).count();
        if (validImageCount == 0) {
            return "";
        }

        StringBuilder reference = new StringBuilder();
        reference.append("<historical_images>\n");
        reference.append("该轮用户上传了 ").append(validImageCount)
                .append(" 张图片。为控制上下文体积，历史图片不再重新作为多模态输入注入，只保留轻量引用。\n");

        int referencedCount = 0;
        for (String fileUrl : fileUrls) {
            if (StringUtils.isBlank(fileUrl)) {
                continue;
            }
            if (referencedCount >= maxReferencedImages) {
                break;
            }
            reference.append("- image_url: ").append(fileUrl).append("\n");
            referencedCount++;
        }

        long remaining = validImageCount - referencedCount;
        if (remaining > 0) {
            reference.append("- 其余 ").append(remaining).append(" 张图片已省略\n");
        }
        reference.append("</historical_images>");
        return reference.toString();
    }

    /** 构造“稳定记忆/动态记忆”片段，分别合入 system 稳定段和动态段 */
    private ChatMemoryAssembler.MemoryPromptSections buildMemoryPromptSections(ChatContext chatContext) {
        try {
            // 必须有用户消息和 userId 才进行召回
            if (chatContext == null || !StringUtils.isNotBlank(chatContext.getUserId())
                    || !StringUtils.isNotBlank(chatContext.getUserMessage())) {
                logger.debug("跳过记忆召回：上下文不完整，sessionId={}",
                        chatContext != null ? chatContext.getSessionId() : "null");
                return ChatMemoryAssembler.MemoryPromptSections.empty();
            }
            int topK = resolveMemoryTopK(chatContext.getLlmModelConfig());
            int memoryBudgetTokens = resolveMemoryBudgetTokens(chatContext.getLlmModelConfig());
            String memoryRecallQuery = buildMemoryRecallQuery(chatContext);
            var results = memoryDomainService.searchRelevant(chatContext.getUserId(), memoryRecallQuery, topK,
                    com.example.agentx.domain.memory.model.MemorySearchFilter.forAgent(
                            chatContext.getAgent() != null ? chatContext.getAgent().getId() : null));
            if (results == null || results.isEmpty()) {
                logger.debug("记忆召回无命中，userId={}, sessionId={}, recallQuery={}", chatContext.getUserId(),
                        chatContext.getSessionId(), StringUtils.abbreviate(memoryRecallQuery, 120));
                return ChatMemoryAssembler.MemoryPromptSections.empty();
            }
            List<MemoryResult> stableCandidates = results.stream().filter(Objects::nonNull)
                    .filter(result -> STABLE_MEMORY_TYPES.contains(resolveMemoryType(result)))
                    .sorted(Comparator.comparing((MemoryResult result) -> result.getScore() == null ? 0D : result.getScore())
                            .reversed()
                            .thenComparing(result -> result.getImportance() == null ? 0F : result.getImportance(),
                                    Comparator.reverseOrder())
                            .thenComparing((MemoryResult result) -> resolveMemoryType(result).name())
                            .thenComparing(result -> StringUtils.defaultString(result.getItemId()))
                            .thenComparing(result -> StringUtils.defaultString(result.getText())))
                    .collect(Collectors.toList());
            List<MemoryResult> rawDynamicCandidates = results.stream().filter(Objects::nonNull)
                    .filter(result -> !STABLE_MEMORY_TYPES.contains(resolveMemoryType(result)))
                    .sorted(Comparator.comparing((MemoryResult result) -> result.getScore() == null ? 0D : result.getScore())
                            .reversed()
                            .thenComparing(result -> result.getImportance() == null ? 0F : result.getImportance(),
                                    Comparator.reverseOrder())
                            .thenComparing(result -> StringUtils.defaultString(result.getItemId())))
                    .collect(Collectors.toList());
            List<MemoryResult> dynamicCandidates = filterDynamicMemoryCandidates(chatContext, rawDynamicCandidates);
            if (rawDynamicCandidates.size() != dynamicCandidates.size()) {
                logger.debug("动态记忆跨会话过滤生效: sessionId={}, userId={}, before={}, after={}",
                        chatContext.getSessionId(), chatContext.getUserId(), rawDynamicCandidates.size(),
                        dynamicCandidates.size());
            }

            List<String> summaryDedupSeeds = buildMemoryPromptDedupSeeds(chatContext);

            int stableBudgetTokens = resolveStableMemoryBudgetTokens(memoryBudgetTokens, stableCandidates,
                    dynamicCandidates);
            int dynamicBudgetTokens = Math.max(0, memoryBudgetTokens - stableBudgetTokens);

            SectionBuildResult stableBuild = buildMemorySectionFragment(chatContext, stableCandidates, stableBudgetTokens,
                    "stable", summaryDedupSeeds);
            List<String> dynamicDedupSeeds = new ArrayList<>(summaryDedupSeeds);
            dynamicDedupSeeds.addAll(stableBuild.dedupSeeds());
            SectionBuildResult dynamicBuild = buildMemorySectionFragment(chatContext, dynamicCandidates, dynamicBudgetTokens,
                    "dynamic", dynamicDedupSeeds);
            int injectedCount = stableBuild.itemCount() + dynamicBuild.itemCount();
            if (injectedCount == 0) {
                logger.debug("记忆召回命中但未注入，userId={}, sessionId={}, budgetTokens={}", chatContext.getUserId(),
                        chatContext.getSessionId(), memoryBudgetTokens);
                return ChatMemoryAssembler.MemoryPromptSections.empty();
            }
            logger.debug("记忆召回命中 {} 条，实际注入 {} 条，userId={}, sessionId={}, budgetTokens={}, usedTokens={}",
                    results.size(), injectedCount, chatContext.getUserId(), chatContext.getSessionId(), memoryBudgetTokens,
                    stableBuild.usedTokens() + dynamicBuild.usedTokens());
            String stableSection = StringUtils.isBlank(stableBuild.content()) ? ""
                    : ConversationPromptTemplates.wrapStableMemorySection(stableBuild.content());
            String dynamicSection = StringUtils.isBlank(dynamicBuild.content()) ? ""
                    : ConversationPromptTemplates.wrapDynamicMemorySection(dynamicBuild.content());
            ChatMemoryAssembler.MemoryPromptSections sections = new ChatMemoryAssembler.MemoryPromptSections(
                    stableSection, dynamicSection, injectedCount);
            cacheMemorySections(chatContext, sections);
            return sections;
        } catch (Exception e) {
            // 召回异常不影响主流程
            logger.warn("记忆召回异常，sessionId={}, userId={}, err={}",
                    chatContext != null ? chatContext.getSessionId() : "null",
                    chatContext != null ? chatContext.getUserId() : "null", e.getMessage());
            return ChatMemoryAssembler.MemoryPromptSections.empty();
        }
    }

    private SectionBuildResult buildMemorySectionFragment(ChatContext chatContext, List<MemoryResult> candidates,
            int budgetTokens, String sectionType, Collection<String> dedupSeeds) {
        if (budgetTokens <= 0 || candidates == null || candidates.isEmpty()) {
            return SectionBuildResult.empty();
        }
        StringBuilder sb = new StringBuilder();
        int usedTokens = 0;
        int idx = 0;
        int maxItems = resolveSectionMaxItems(sectionType);
        double topScore = candidates.stream().filter(Objects::nonNull).map(MemoryResult::getScore).filter(Objects::nonNull)
                .findFirst().orElse(0D);
        LinkedHashSet<String> seenKeys = new LinkedHashSet<>();
        List<String> selectedSeeds = new ArrayList<>();
        for (MemoryResult result : candidates) {
            if (result == null || result.getText() == null) {
                continue;
            }
            if (idx >= maxItems || !shouldInjectMemoryCandidate(result, topScore)) {
                continue;
            }
            int remainingTokens = budgetTokens - usedTokens - chatContextProperties.getMemory().getItemOverheadTokens();
            if (remainingTokens <= 0) {
                break;
            }
            String text = result.getText().replaceAll("\n+", " ").trim();
            String clippedText = clipTextToTokenBudget(chatContext, text, remainingTokens);
            if (StringUtils.isBlank(clippedText)) {
                continue;
            }
            if (shouldSkipMemoryForPrompt(clippedText, dedupSeeds, seenKeys)) {
                logger.debug("记忆注入去重跳过: sessionId={}, userId={}, section={}, itemId={}, text={}",
                        chatContext.getSessionId(), chatContext.getUserId(), sectionType, result.getItemId(),
                        StringUtils.abbreviate(clippedText, 120));
                continue;
            }
            int itemTokens = estimateMessageBodyTokens(chatContext, clippedText)
                    + chatContextProperties.getMemory().getItemOverheadTokens();
            if (itemTokens > budgetTokens - usedTokens) {
                break;
            }
            sb.append("<memory id=\"M").append(idx + 1).append("\" type=\"")
                    .append(resolveMemoryType(result).name()).append("\">")
                    .append(escapeXml(clippedText)).append("</memory>\n");
            usedTokens += itemTokens;
            idx++;
            logger.debug(
                    "记忆注入明细: sessionId={}, userId={}, section={}, idx={}, itemId={}, type={}, score={}, importance={}, tags={}, usedTokens={}, budgetTokens={}, text={}",
                    chatContext.getSessionId(), chatContext.getUserId(), sectionType, idx, result.getItemId(),
                    resolveMemoryType(result).name(), result.getScore(), result.getImportance(), result.getTags(),
                    usedTokens, budgetTokens, StringUtils.abbreviate(clippedText, 120));
            selectedSeeds.add(normalizeMemoryDedupText(clippedText));
        }
        return new SectionBuildResult(sb.toString().trim(), usedTokens, idx, selectedSeeds);
    }

    private List<String> buildMemoryPromptDedupSeeds(ChatContext chatContext) {
        if (chatContext == null) {
            return Collections.emptyList();
        }
        LinkedHashSet<String> seeds = new LinkedHashSet<>();
        addMemoryDedupSeeds(seeds, resolveConversationSummaryText(chatContext), 12);
        addMemoryDedupSeeds(seeds, chatContext.getUserMessage(), 8);
        for (String history : buildMemoryRecallRecentHistory(chatContext)) {
            addMemoryDedupSeeds(seeds, history, 4);
            if (seeds.size() >= 20) {
                break;
            }
        }
        return new ArrayList<>(seeds);
    }

    private void addMemoryDedupSeeds(Set<String> target, String rawText, int maxAdditional) {
        if (target == null || maxAdditional <= 0) {
            return;
        }
        int beforeSize = target.size();
        for (String seed : buildMemoryDedupSeeds(rawText)) {
            target.add(seed);
            if (target.size() - beforeSize >= maxAdditional || target.size() >= 20) {
                break;
            }
        }
    }

    private List<String> buildMemoryDedupSeeds(String rawText) {
        if (StringUtils.isBlank(rawText)) {
            return Collections.emptyList();
        }
        List<String> seeds = Arrays.stream(rawText.split("[\\r\\n。！？；;.!?]+"))
                .map(this::normalizeMemoryDedupText)
                .filter(StringUtils::isNotBlank)
                .filter(seed -> seed.length() >= 6)
                .distinct()
                .limit(12)
                .collect(Collectors.toCollection(ArrayList::new));
        if (seeds.isEmpty()) {
            String normalized = normalizeMemoryDedupText(rawText);
            return StringUtils.isNotBlank(normalized) ? List.of(normalized) : Collections.emptyList();
        }
        return seeds;
    }

    private boolean shouldSkipMemoryForPrompt(String text, Collection<String> dedupSeeds, Set<String> seenKeys) {
        String normalized = normalizeMemoryDedupText(text);
        if (StringUtils.isBlank(normalized)) {
            return true;
        }
        if (!seenKeys.add(normalized)) {
            return true;
        }
        if (dedupSeeds == null || dedupSeeds.isEmpty()) {
            return false;
        }
        for (String seed : dedupSeeds) {
            if (isMemoryOverlap(normalized, seed)) {
                return true;
            }
        }
        return false;
    }

    private boolean isMemoryOverlap(String normalizedText, String normalizedSeed) {
        if (StringUtils.isBlank(normalizedText) || StringUtils.isBlank(normalizedSeed)) {
            return false;
        }
        if (normalizedText.equals(normalizedSeed)) {
            return true;
        }
        int minLength = Math.min(normalizedText.length(), normalizedSeed.length());
        if (minLength < 8) {
            return false;
        }
        return normalizedText.contains(normalizedSeed) || normalizedSeed.contains(normalizedText);
    }

    private String normalizeMemoryDedupText(String text) {
        if (StringUtils.isBlank(text)) {
            return "";
        }
        return text.toLowerCase(Locale.ROOT)
                .replaceFirst("^(user|assistant|用户|助手)\\s*[:：-]\\s*", "")
                .replaceAll("[\\p{Punct}，。！？；：“”‘’（）【】、《》]+", " ")
                .replaceAll("\\s+", " ").trim();
    }

    private boolean shouldInjectMemoryCandidate(MemoryResult result, double topScore) {
        if (result == null) {
            return false;
        }
        double score = result.getScore() == null ? 0D : Math.max(0D, Math.min(1D, result.getScore()));
        ChatContextProperties.Memory memoryProps = chatContextProperties.getMemory();
        if (score < Math.max(0D, Math.min(1D, memoryProps.getMinInjectScore()))) {
            return false;
        }
        double allowedDrop = Math.max(0D, Math.min(1D, memoryProps.getMaxScoreDropFromTop()));
        return topScore <= 0D || score >= Math.max(0D, topScore - allowedDrop);
    }

    private int resolveSectionMaxItems(String sectionType) {
        ChatContextProperties.Memory memoryProps = chatContextProperties.getMemory();
        if ("dynamic".equalsIgnoreCase(sectionType)) {
            return Math.max(1, memoryProps.getMaxDynamicItems());
        }
        return Math.max(1, memoryProps.getMaxStableItems());
    }

    private int resolveStableMemoryBudgetTokens(int memoryBudgetTokens, List<MemoryResult> stableCandidates,
            List<MemoryResult> dynamicCandidates) {
        if (memoryBudgetTokens <= 0 || CollectionUtil.isEmpty(stableCandidates)) {
            return 0;
        }
        if (CollectionUtil.isEmpty(dynamicCandidates)) {
            return memoryBudgetTokens;
        }

        double stableWeight = stableCandidates.stream().mapToDouble(this::memoryTypeAdjustedWeight).sum();
        double dynamicWeight = dynamicCandidates.stream().mapToDouble(this::memoryTypeAdjustedWeight).sum();
        double distributionRatio = stableWeight + dynamicWeight <= 0 ? 0.5 : stableWeight / (stableWeight + dynamicWeight);
        double configuredRatio = Math.max(0.1,
                Math.min(0.9, chatContextProperties.getMemory().getStableBudgetRatio() / 100.0));
        double blendedRatio = configuredRatio * 0.35 + distributionRatio * 0.65;
        int minStableBudget = Math.min(memoryBudgetTokens, Math.max(1,
                chatContextProperties.getMemory().getMinBudgetTokens() / 2));
        int resolvedBudget = (int) Math.round(memoryBudgetTokens * Math.max(0.15, Math.min(0.85, blendedRatio)));
        return Math.max(minStableBudget, Math.min(memoryBudgetTokens, resolvedBudget));
    }

    private double memoryTypeAdjustedWeight(MemoryResult result) {
        if (result == null) {
            return 0;
        }
        double score = result.getScore() == null ? 0.5 : Math.max(0, Math.min(1, result.getScore()));
        double importance = result.getImportance() == null ? 0.5 : Math.max(0, Math.min(1, result.getImportance()));
        double typeWeight = switch (resolveMemoryType(result)) {
            case PROFILE -> 1.25;
            case FACT -> 1.10;
            case TASK -> 0.95;
            case EPISODIC -> 0.75;
        };
        return Math.max(0.1, score * 0.65 + importance * 0.35) * typeWeight;
    }

    private MemoryType resolveMemoryType(MemoryResult result) {
        return result == null ? MemoryType.FACT : (result.getType() != null ? result.getType() : MemoryType.FACT);
    }

    private List<MemoryResult> filterDynamicMemoryCandidates(ChatContext chatContext, List<MemoryResult> dynamicCandidates) {
        if (CollectionUtil.isEmpty(dynamicCandidates)) {
            return Collections.emptyList();
        }
        if (chatContextProperties.getMemory().isIncludeCrossSessionDynamicMemories()) {
            return dynamicCandidates.stream().filter(Objects::nonNull)
                    .filter(result -> isCurrentSessionMemory(chatContext, result)
                            || isCrossSessionDynamicMemoryVisible(result))
                    .collect(Collectors.toList());
        }
        String currentSessionId = chatContext != null ? StringUtils.trimToNull(chatContext.getSessionId()) : null;
        if (StringUtils.isBlank(currentSessionId)) {
            return Collections.emptyList();
        }
        return dynamicCandidates.stream().filter(Objects::nonNull)
                .filter(result -> isCurrentSessionMemory(chatContext, result))
                .collect(Collectors.toList());
    }

    private boolean isCurrentSessionMemory(ChatContext chatContext, MemoryResult result) {
        String currentSessionId = chatContext != null ? StringUtils.trimToNull(chatContext.getSessionId()) : null;
        return StringUtils.isNotBlank(currentSessionId)
                && currentSessionId.equals(StringUtils.trimToNull(result != null ? result.getSourceSessionId() : null));
    }

    private boolean isCrossSessionDynamicMemoryVisible(MemoryResult result) {
        if (result == null) {
            return false;
        }
        MemoryType type = resolveMemoryType(result);
        int visibleDays = switch (type) {
            case TASK -> chatContextProperties.getMemory().getCrossSessionTaskVisibleDays();
            case EPISODIC -> chatContextProperties.getMemory().getCrossSessionEpisodicVisibleDays();
            default -> 0;
        };
        if (visibleDays <= 0) {
            return false;
        }
        LocalDateTime updatedAt = result.getUpdatedAt();
        if (updatedAt == null) {
            return false;
        }
        return !updatedAt.isBefore(LocalDateTime.now().minusDays(visibleDays));
    }

    private int resolveMemoryTopK(LLMModelConfig llmModelConfig) {
        int defaultTopK = chatContextProperties.getMemory().getTopK();
        if (llmModelConfig == null || llmModelConfig.getTopK() == null) {
            return defaultTopK;
        }
        int configuredTopK = llmModelConfig.getTopK();
        if (configuredTopK <= 0 || configuredTopK > defaultTopK) {
            return defaultTopK;
        }
        return configuredTopK;
    }

    private int resolveMemoryBudgetTokens(LLMModelConfig llmModelConfig) {
        ChatContextProperties.Memory props = chatContextProperties.getMemory();
        if (llmModelConfig == null || llmModelConfig.getMaxTokens() == null) {
            return props.getDefaultBudgetTokens();
        }
        int maxTokens = llmModelConfig.getMaxTokens();
        double reserveRatio = llmModelConfig.getReserveRatio() != null ? llmModelConfig.getReserveRatio() : 0.25;
        int availableTokens = maxTokens - (int) Math.floor(maxTokens * reserveRatio);
        int budgetTokens = Math.max(props.getMinBudgetTokens(), availableTokens / 10);
        return Math.min(props.getMaxBudgetTokens(), budgetTokens);
    }

    private String buildMemoryRecallQuery(ChatContext chatContext) {
        if (chatContext == null) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        String originalUserMessage = StringUtils.defaultString(chatContext.getUserMessage()).trim();
        String recalledUserMessage = rewriteMemoryRecallQuery(chatContext, originalUserMessage);
        if (StringUtils.isNotBlank(recalledUserMessage)) {
            parts.add("当前用户问题：" + abbreviatePromptText(recalledUserMessage, 260));
        }
        if (StringUtils.isNotBlank(originalUserMessage) && !StringUtils.equals(originalUserMessage, recalledUserMessage)) {
            parts.add("用户原始追问：" + abbreviatePromptText(originalUserMessage, 120));
        }

        String summary = resolveConversationSummaryText(chatContext);
        if (StringUtils.isNotBlank(summary)) {
            parts.add("会话目标/长期约束：" + abbreviatePromptText(summary, 120));
        }

        List<String> recentHistory = buildMemoryRecallRecentHistory(chatContext);
        if (!recentHistory.isEmpty()) {
            parts.add("最近上下文：" + recentHistory.stream()
                    .map(history -> abbreviatePromptText(history, 90))
                    .limit(2)
                    .collect(Collectors.joining(" | ")));
        }

        return String.join("\n", parts).trim();
    }

    private String rewriteMemoryRecallQuery(ChatContext chatContext, String originalUserMessage) {
        if (chatContext == null || ragQueryRewriter == null || StringUtils.isBlank(originalUserMessage)) {
            return originalUserMessage;
        }
        try {
            if (!ragQueryRewriter.shouldRewriteQuestion(chatContext)) {
                return originalUserMessage;
            }
            return StringUtils.defaultIfBlank(ragQueryRewriter.rewriteQuestion(chatContext), originalUserMessage);
        } catch (Exception e) {
            logger.debug("记忆召回 query 改写失败，回退原问题: sessionId={}, userId={}, err={}",
                    chatContext.getSessionId(), chatContext.getUserId(), e.getMessage());
            return originalUserMessage;
        }
    }

    private String resolveConversationSummaryText(ChatContext chatContext) {
        return chatContext == null ? ""
                : ConversationPromptContextUtils.resolveNormalizedSummary(chatContext.getMessageHistory(),
                        chatContext.getContextEntity());
    }

    private List<String> buildMemoryRecallRecentHistory(ChatContext chatContext) {
        return chatContext == null ? Collections.emptyList()
                : ConversationPromptContextUtils.buildRecentTextHistory(chatContext.getMessageHistory(), 2, 90);
    }

    private String clipTextToTokenBudget(ChatContext chatContext, String text, int maxTokens) {
        String normalized = StringUtils.defaultString(text).trim();
        if (StringUtils.isBlank(normalized) || maxTokens <= 0) {
            return "";
        }
        if (estimateMessageBodyTokens(chatContext, normalized) <= maxTokens) {
            return normalized;
        }
        int left = 1;
        int right = normalized.length();
        String best = "";
        while (left <= right) {
            int mid = (left + right) >>> 1;
            String candidate = normalized.substring(0, mid).trim();
            if (candidate.isEmpty()) {
                left = mid + 1;
                continue;
            }
            int tokenCount = estimateMessageBodyTokens(chatContext, candidate);
            if (tokenCount <= maxTokens) {
                best = candidate;
                left = mid + 1;
            } else {
                right = mid - 1;
            }
        }
        if (StringUtils.isBlank(best)) {
            return normalized.substring(0, 1);
        }
        if (best.length() >= normalized.length()) {
            return best;
        }
        String natural = trimToNaturalBoundary(best);
        if (StringUtils.isNotBlank(natural) && estimateMessageBodyTokens(chatContext, natural) <= maxTokens) {
            return natural + (natural.length() < normalized.length() ? "…" : "");
        }
        return best + "…";
    }

    private String trimToNaturalBoundary(String text) {
        if (StringUtils.isBlank(text)) {
            return "";
        }
        String trimmed = text.trim();
        int boundary = Math.max(
                Math.max(trimmed.lastIndexOf('。'), trimmed.lastIndexOf('；')),
                Math.max(Math.max(trimmed.lastIndexOf('，'), trimmed.lastIndexOf(',')),
                        Math.max(trimmed.lastIndexOf(' '), trimmed.lastIndexOf('、'))));
        if (boundary <= 0) {
            return trimmed;
        }
        int minAcceptableLength = Math.max(12, (int) Math.floor(trimmed.length() * 0.6));
        if (boundary + 1 < minAcceptableLength) {
            return trimmed;
        }
        return trimmed.substring(0, boundary + 1).trim();
    }

    private String escapeXml(String text) {
        return PromptXmlUtils.escapeXml(text);
    }

    private String escapeXmlAttribute(String text) {
        return escapeXml(StringUtils.defaultString(text));
    }

    private Set<String> resolveRecentMultimodalHistoryMessageIds(List<MessageEntity> messageHistory) {
        if (CollectionUtil.isEmpty(messageHistory)) {
            return Collections.emptySet();
        }
        int preserveTurns = chatContextProperties.getHistory().getRecentMultimodalTurns();
        if (preserveTurns <= 0) {
            return Collections.emptySet();
        }
        LinkedHashSet<String> preservedIds = new LinkedHashSet<>();
        int remainingTurns = preserveTurns;
        for (int index = messageHistory.size() - 1; index >= 0 && remainingTurns > 0; index--) {
            MessageEntity messageEntity = messageHistory.get(index);
            if (messageEntity == null || !messageEntity.isUserMessage() || CollectionUtil.isEmpty(messageEntity.getFileUrls())
                    || StringUtils.isBlank(messageEntity.getId())) {
                continue;
            }
            preservedIds.add(messageEntity.getId());
            remainingTurns--;
        }
        return preservedIds;
    }

    private Optional<ChatMemoryAssembler.MemoryPromptSections> getCachedMemorySections(ChatContext chatContext) {
        String cacheKey = buildMemoryCacheKey(chatContext);
        if (cacheKey == null) {
            return Optional.empty();
        }
        CachedMemorySections cached = memoryRecallCache.getIfPresent(cacheKey);
        if (cached == null) {
            return Optional.empty();
        }
        if (cached.expireAtMillis() < System.currentTimeMillis()) {
            memoryRecallCache.invalidate(cacheKey);
            return Optional.empty();
        }
        long currentVersion = resolveCurrentMemoryCacheVersion(chatContext);
        if (cached.version() != currentVersion) {
            memoryRecallCache.invalidate(cacheKey);
            return Optional.empty();
        }
        return Optional.of(cached.sections());
    }

    private void cacheMemorySections(ChatContext chatContext, ChatMemoryAssembler.MemoryPromptSections sections) {
        if (chatContext == null || sections == null || !sections.hasAny()) {
            return;
        }
        String cacheKey = buildMemoryCacheKey(chatContext);
        if (cacheKey == null) {
            return;
        }
        long ttlMs = Math.max(1000L, chatContextProperties.getMemory().getRecallCacheTtlMs());
        memoryRecallCache.put(cacheKey, new CachedMemorySections(sections, System.currentTimeMillis() + ttlMs,
                resolveCurrentMemoryCacheVersion(chatContext)));
    }

    private String buildMemoryCacheKey(ChatContext chatContext) {
        if (chatContext == null || StringUtils.isBlank(chatContext.getUserId())
                || StringUtils.isBlank(chatContext.getUserMessage())) {
            return null;
        }
        String recallQuery = buildMemoryRecallQuery(chatContext);
        if (StringUtils.isBlank(recallQuery)) {
            return null;
        }
        String sessionId = StringUtils.defaultIfBlank(chatContext.getSessionId(), "no-session");
        String summary = resolveConversationSummaryText(chatContext);
        long memoryVersion = memoryDomainService != null ? memoryDomainService.getRecallCacheVersion(chatContext.getUserId())
                : 0L;
        return chatContext.getUserId() + "|" + sessionId + "|rq=" + Integer.toHexString(recallQuery.hashCode())
                + "|sm=" + Integer.toHexString(StringUtils.defaultString(summary).hashCode())
                + "|mv=" + memoryVersion;
    }

    private long resolveCurrentMemoryCacheVersion(ChatContext chatContext) {
        return chatContext != null && memoryDomainService != null && StringUtils.isNotBlank(chatContext.getUserId())
                ? memoryDomainService.getRecallCacheVersion(chatContext.getUserId())
                : 0L;
    }

    private record SectionBuildResult(String content, int usedTokens, int itemCount, List<String> dedupSeeds) {
        static SectionBuildResult empty() {
            return new SectionBuildResult("", 0, 0, Collections.emptyList());
        }
    }

    private record CachedMemorySections(ChatMemoryAssembler.MemoryPromptSections sections, long expireAtMillis,
            long version) {
    }

    private record CachedToolCatalog(String catalogPrompt, long expireAtMillis) {
    }

    // 智能重命名会话
    protected void smartRenameSession(ChatContext chatContext) {
        if (chatContext == null || StringUtils.isBlank(chatContext.getSessionId())) {
            return;
        }
        String sessionId = chatContext.getSessionId();
        if (!renamingSessions.add(sessionId)) {
            return;
        }

        String userId = chatContext.getUserId();
        String userMessage = chatContext.getUserMessage();
        ModelEntity originalModel = chatContext.getOriginalModel();
        ModelEntity selectedModel = chatContext.getModel();

        Runnable renameTask = () -> {
            try {
                boolean isFirstConversation = messageDomainService.isFirstConversation(sessionId);
                if (!isFirstConversation) {
                    return;
                }
                if (!sessionDomainService.shouldAutoRename(sessionId, userId)) {
                    return;
                }
                ModelEntity model = originalModel != null ? originalModel : selectedModel;
                if (model == null) {
                    String preferredModelId = userModelConfigResolver.resolvePreferredChatModelId(userId, sessionId);
                    model = llmDomainService.getModelById(preferredModelId);
                }
                List<String> fallbackChain = userSettingsDomainService.getUserFallbackChain(userId);
                HighAvailabilityResult result = highAvailabilityDomainService.selectBestProvider(model, userId,
                        sessionId, fallbackChain);
                ProviderEntity provider = result.getProvider();
                ModelEntity resolvedModel = result.getModel();
                ChatModel strandClient = llmServiceFactory.getStrandClient(provider, resolvedModel,
                        Duration.ofSeconds(15));
                ArrayList<ChatMessage> chatMessages = new ArrayList<>();
                chatMessages.add(new SystemMessage(ConversationPromptTemplates.getStartConversationPrompt()));
                chatMessages.add(new UserMessage(userMessage));
                ChatResponse chat = invokeSyncChatWithFriendlyError(chatContext, strandClient, chatMessages);
                String rawTitle = chat.aiMessage() == null ? null : chat.aiMessage().text();
                String sessionTitle = SessionEntity.normalizeSmartTitle(rawTitle);
                sessionDomainService.updateSession(sessionId, userId, sessionTitle);
            } catch (Exception e) {
                logger.warn("智能重命名失败: sessionId={}, userId={}, err={}", sessionId, userId, e.getMessage());
            } finally {
                renamingSessions.remove(sessionId);
            }
        };
        try {
            sessionRenameTaskExecutor.execute(renameTask);
        } catch (RuntimeException ex) {
            renamingSessions.remove(sessionId);
            throw ex;
        }
    }

    /** 创建计费上下文
     *
     * @param chatContext 聊天上下文
     * @param inputTokens 输入Token数量
     * @param outputTokens 输出Token数量
     * @return 计费上下文 */
    private RuleContext createBillingContext(ChatContext chatContext, Integer inputTokens, Integer outputTokens) {
        String requestId = generateRequestId(chatContext.getSessionId(), chatContext.getUserId());

        return RuleContext.builder().type(BillingType.MODEL_USAGE.getCode())
                .serviceId(chatContext.getModel().getId().toString()) // 使用模型表主键ID
                .usageData(Map.of(UsageDataKeys.INPUT_TOKENS, inputTokens != null ? inputTokens : 0,
                        UsageDataKeys.OUTPUT_TOKENS, outputTokens != null ? outputTokens : 0))
                .requestId(requestId).userId(chatContext.getUserId()) // 添加用户ID
                .build();
    }

    /** 生成幂等性请求ID
     *
     * @param sessionId 会话ID
     * @param userId 用户ID
     * @return 请求ID */
    private String generateRequestId(String sessionId, String userId) {
        long timestamp = System.currentTimeMillis();
        return String.format("billing_%s_%s_%d", sessionId, userId, timestamp);
    }

    /** 执行计费并处理异常
     *
     * @param chatContext 聊天上下文
     * @param inputTokens 输入Token数
     * @param outputTokens 输出Token数
     * @param transport 消息传输
     * @param connection 连接对象 */
    protected <T> void performBillingWithErrorHandling(ChatContext chatContext, Integer inputTokens,
            Integer outputTokens, MessageTransport<T> transport, T connection) {
        try {
            // 创建计费上下文
            RuleContext billingContext = createBillingContext(chatContext, inputTokens, outputTokens);

            // 执行计费
            billingService.charge(billingContext);

            logger.info("模型调用计费成功 - 用户: {}, 模型: {}, 输入Token: {}, 输出Token: {}, 费用已扣除", chatContext.getUserId(),
                    chatContext.getModel().getId(), inputTokens, outputTokens);

        } catch (InsufficientBalanceException e) {
            // 余额不足异常处理
            logger.warn("用户余额不足 - 用户: {}, 模型: {}, 错误: {}", chatContext.getUserId(), chatContext.getModel().getId(),
                    e.getMessage());

            // 发送余额不足提示消息
            AgentChatResponse balanceWarning = new AgentChatResponse("⚠️ 账户余额不足，请及时充值以继续使用服务", false);
            balanceWarning.setMessageType(MessageType.TEXT);
            transport.sendMessage(connection, balanceWarning);

        } catch (BusinessException e) {
            // 业务异常：记录日志但不影响对话
            logger.error("计费业务异常 - 用户: {}, 模型: {}, 错误: {}", chatContext.getUserId(), chatContext.getModel().getId(),
                    e.getMessage(), e);

        } catch (Exception e) {
            // 系统异常：记录日志但不影响对话
            logger.error("计费系统异常 - 用户: {}, 模型: {}, 错误: {}", chatContext.getUserId(), chatContext.getModel().getId(),
                    e.getMessage(), e);
        }
    }

    /** 检查用户余额是否足够开始对话
     *
     * @param userId 用户ID
     * @param transport 消息传输
     * @param connection 连接对象
     * @param <T> 连接类型
     * @throws InsufficientBalanceException 余额不足时抛出 */
    protected <T> void checkBalanceBeforeChat(String userId, MessageTransport<T> transport, T connection) {
        try {
            AccountEntity account = accountDomainService.getOrCreateAccount(userId);
            if (account.getBalance().compareTo(BigDecimal.ZERO) < 0) {
                // 余额不足：发送错误消息（余额检查在对话开始前，不需要检查中断状态）
                AgentChatResponse errorResponse = ChatErrorResponseFactory.buildInsufficientBalance(account.getBalance());
                transport.sendMessage(connection, errorResponse);

                logger.warn("用户余额不足被拒绝对话 - 用户: {}, 当前余额: {}", userId, account.getBalance());
                throw new InsufficientBalanceException("账户余额不足，请充值后继续使用");
            }

            logger.debug("用户余额检查通过 - 用户: {}, 当前余额: {}", userId, account.getBalance());
        } catch (InsufficientBalanceException e) {
            // 重新抛出余额不足异常
            throw e;
        } catch (Exception e) {
            logger.error("余额检查异常 - 用户: {}, 错误: {}", userId, e.getMessage(), e);
            // 余额检查异常时，为了不影响用户体验，允许继续对话
            logger.warn("余额检查服务异常，允许用户继续对话 - 用户: {}", userId);
        }
    }

    /** 构建模型调用信息
     * 
     * @param chatContext 对话上下文
     * @param chatResponse 模型响应
     * @param callTime 调用耗时（毫秒）
     * @param success 是否成功
     * @return 模型调用信息 */
    protected ModelCallInfo buildModelCallInfo(ChatContext chatContext, ChatResponse chatResponse, long callTime,
            boolean success) {
        // 检查是否发生了模型切换
        boolean modelSwitched = chatContext.getOriginalModel() != null
                && !chatContext.getOriginalModel().getId().equals(chatContext.getModel().getId());
        String aiText = chatResponse != null && chatResponse.aiMessage() != null ? chatResponse.aiMessage().text() : "";
        Integer resolvedInputTokens = Optional.ofNullable(ChatResponseTokenUsageUtils.inputTokenCount(chatResponse))
                .orElseGet(() -> estimateMessageBodyTokens(chatContext,
                        chatContext != null ? chatContext.getUserMessage() : null));
        Integer resolvedOutputTokens = Optional.ofNullable(ChatResponseTokenUsageUtils.outputTokenCount(chatResponse))
                .orElseGet(() -> estimateMessageBodyTokens(chatContext, aiText));

        return ModelCallInfo.builder().modelEndpoint(chatContext.getModel().getModelEndpoint())
                .providerName(
                        chatContext.getProvider().getName() + (chatContext.getProvider().getIsOfficial() ? "(官方)" : ""))
                .inputTokens(resolvedInputTokens)
                .outputTokens(resolvedOutputTokens).callTime((int) callTime)
                .success(success)
                .fallbackUsed(modelSwitched)
                .originalEndpoint(modelSwitched ? chatContext.getOriginalModel().getModelEndpoint() : null)
                .originalProviderName(modelSwitched
                        ? chatContext.getOriginalProvider().getName()
                                + (chatContext.getOriginalProvider().getIsOfficial() ? "(官方)" : "")
                        : null)
                .build();
    }

    /** 构建工具调用信息
     * 
     * @param toolExecution 工具执行信息
     * @return 工具调用信息 */
    protected ToolCallInfo buildToolCallInfo(ToolExecution toolExecution) {
        return buildToolCallInfo(toolExecution, null);
    }

    protected ToolCallInfo buildToolCallInfo(ToolExecution toolExecution, Integer executionTime) {
        String result = toolExecution != null ? toolExecution.result() : null;
        return ToolCallInfo.builder().toolName(toolExecution != null && toolExecution.request() != null
                ? toolExecution.request().name()
                : null).requestArgs(toolExecution != null && toolExecution.request() != null
                        ? ToolPayloadUtils.sanitizeArgumentsForTrace(toolExecution.request().arguments())
                        : null).success(isToolExecutionSuccessful(result))
                .executionTime(executionTime)
                .responseData(ToolPayloadUtils.sanitizeResultForTrace(result))
                .errorMessage(isToolExecutionSuccessful(result) ? null : ToolPayloadUtils.sanitizeResultForTrace(result))
                .build();
    }

    protected ToolCallInfo buildToolCallInfo(CapturedToolExecution toolExecution) {
        String result = toolExecution != null ? toolExecution.result() : null;
        return ToolCallInfo.builder().toolName(toolExecution != null && toolExecution.request() != null
                ? toolExecution.request().name()
                : null).requestArgs(toolExecution != null && toolExecution.request() != null
                        ? ToolPayloadUtils.sanitizeArgumentsForTrace(toolExecution.request().arguments())
                        : null).responseData(ToolPayloadUtils.sanitizeResultForTrace(result))
                .executionTime(toolExecution != null ? toolExecution.executionTime() : null)
                .success(isToolExecutionSuccessful(result))
                .errorMessage(isToolExecutionSuccessful(result) ? null : ToolPayloadUtils.sanitizeResultForTrace(result))
                .build();
    }

    protected boolean isToolExecutionSuccessful(String result) {
        return ToolPayloadUtils.isToolExecutionSuccessful(result);
    }

    protected AgentChatResponse buildToolExecutionResponse(String message, String arguments, String result) {
        return buildToolExecutionResponse(message, arguments, result, null);
    }

    protected AgentChatResponse buildToolExecutionResponse(String message, String arguments, String result,
            Integer durationMs) {
        AgentChatResponse response = AgentChatResponse.buildEndMessage(message, MessageType.TOOL_CALL);
        response.setPayload(buildToolExecutionPayload(arguments, result, durationMs));
        return response;
    }

    protected AgentChatResponse buildPendingToolExecutionResponse(String toolName, String arguments, long elapsedMs) {
        String message = "执行工具：" + StringUtils.defaultIfBlank(toolName, "unknown");
        AgentChatResponse response = AgentChatResponse.build(message, MessageType.TOOL_CALL);
        response.setPayload(buildPendingToolExecutionPayload(toolName, arguments, elapsedMs));
        return response;
    }

    protected String buildToolExecutionPayload(String arguments, String result) {
        return buildToolExecutionPayload(arguments, result, null);
    }

    protected String buildToolExecutionPayload(String arguments, String result, Integer durationMs) {
        return ToolPayloadUtils.buildSingleToolPayload(arguments, result, durationMs);
    }

    protected String buildPendingToolExecutionPayload(String toolName, String arguments, long elapsedMs) {
        int durationMs = (int) Math.max(0L, Math.min(Integer.MAX_VALUE, elapsedMs));
        return ToolPayloadUtils.buildSinglePendingToolPayload(toolName, arguments, durationMs);
    }

    protected <T> ToolExecutionProgressListener buildToolExecutionProgressListener(T connection,
            MessageTransport<T> transport, ChatContext chatContext, ToolProgressStreamState toolProgressStreamState) {
        if (connection == null || transport == null || chatContext == null || toolProgressStreamState == null) {
            return null;
        }
        Map<String, String> callbackMdc = MDC.getCopyOfContextMap();
        return new ToolExecutionProgressListener() {
            @Override
            public void onStarted(ToolExecutionRequest request) {
                emitProgress(request, 0L, true);
            }

            @Override
            public void onProgress(ToolExecutionRequest request, long elapsedMs) {
                emitProgress(request, elapsedMs, false);
            }

            private void emitProgress(ToolExecutionRequest request, long elapsedMs, boolean flushTextSegment) {
                runWithMdc(callbackMdc, () -> {
                    if (request == null || isChatInterrupted(chatContext)) {
                        return;
                    }
                    if (flushTextSegment && toolProgressStreamState.markTextSegmentFlushed()) {
                        transport.sendMessage(connection, AgentChatResponse.buildEndMessage(MessageType.TEXT));
                    }
                    transport.sendMessage(connection,
                            buildPendingToolExecutionResponse(request.name(), request.arguments(), elapsedMs));
                });
            }
        };
    }

    protected String classifyToolError(String result) {
        return ToolPayloadUtils.classifyToolError(result);
    }

    protected String abbreviateToolPayload(String value, int limit) {
        return ToolPayloadUtils.abbreviateForPayload(value, limit);
    }

    protected <T> void emitToolAvailabilityNotice(ChatContext chatContext, T connection, MessageTransport<T> transport) {
        if (chatContext == null || transport == null) {
            return;
        }
        String notice = chatContext.getToolAvailabilityNotice();
        if (StringUtils.isBlank(notice)) {
            return;
        }
        if (notice.contains("仅用于内部决策")) {
            logger.debug("工具可用性提示仅用于内部决策，不向前端发送: sessionId={}", chatContext.getSessionId());
            return;
        }
        AgentChatResponse response = AgentChatResponse.buildEndMessage(notice, MessageType.TOOL_NOTICE);
        transport.sendMessage(connection, response);
    }

    private void registerToolProviderResource(ChatContext chatContext, ToolProvider toolProvider) {
        if (chatContext == null || !(toolProvider instanceof AutoCloseable closeable)) {
            return;
        }
        chatContext.registerCloseableResource(closeable);
    }

    protected void closeChatResources(ChatContext chatContext) {
        if (chatContext == null) {
            return;
        }
        chatContext.closeResourcesQuietly();
    }

}
