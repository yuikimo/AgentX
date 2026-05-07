package com.example.agentx.application.conversation.service.message.rag;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.tool.ToolProvider;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;
import com.example.agentx.application.conversation.config.ChatContextProperties;
import com.example.agentx.application.conversation.dto.AgentChatResponse;
import com.example.agentx.application.conversation.util.ChatErrorResponseFactory;
import com.example.agentx.application.conversation.service.message.Agent;
import com.example.agentx.application.conversation.util.ConversationMetadataUtils;
import com.example.agentx.application.rag.dto.DocumentUnitDTO;
import com.example.agentx.domain.conversation.constant.MessageType;
import com.example.agentx.domain.conversation.model.MessageEntity;
import com.example.agentx.domain.conversation.service.MessageDomainService;
import com.example.agentx.domain.llm.service.HighAvailabilityDomainService;
import com.example.agentx.domain.prompt.RagPromptTemplates;
import com.example.agentx.infrastructure.llm.ChatResponseTokenUsageUtils;
import com.example.agentx.infrastructure.llm.LLMServiceFactory;
import com.example.agentx.infrastructure.transport.MessageTransport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Locale;

@Component
public class RagAnswerGenerator {

    private static final Logger logger = LoggerFactory.getLogger(RagAnswerGenerator.class);

    private static final Pattern[] DOC_CITATION_PATTERNS = new Pattern[] {
            Pattern.compile("\\[\\^?([A-Za-z0-9_-]{2,32})]"),
            Pattern.compile("[（(]([A-Za-z0-9_-]{2,32})[）)]")
    };

    private final LLMServiceFactory llmServiceFactory;
    private final MessageDomainService messageDomainService;
    private final HighAvailabilityDomainService highAvailabilityDomainService;
    private final RagAnswerPromptBuilder ragAnswerPromptBuilder;
    private final ChatContextProperties chatContextProperties;
    private final TaskExecutor persistenceTaskExecutor;

    public RagAnswerGenerator(LLMServiceFactory llmServiceFactory, MessageDomainService messageDomainService,
            HighAvailabilityDomainService highAvailabilityDomainService,
            RagAnswerPromptBuilder ragAnswerPromptBuilder, ChatContextProperties chatContextProperties,
            @Qualifier("applicationEventTaskExecutor") TaskExecutor persistenceTaskExecutor) {
        this.llmServiceFactory = llmServiceFactory;
        this.messageDomainService = messageDomainService;
        this.highAvailabilityDomainService = highAvailabilityDomainService;
        this.ragAnswerPromptBuilder = ragAnswerPromptBuilder;
        this.chatContextProperties = chatContextProperties;
        this.persistenceTaskExecutor = persistenceTaskExecutor;
    }

    public <T> void generateAnswer(RagChatContext ragContext, RagRetrievalResult retrievalResult, T connection,
            MessageTransport<T> transport, MessageEntity userEntity, MessageEntity llmEntity,
            MessageWindowChatMemory memory, ToolProvider toolProvider, RuntimeSupport runtimeSupport) {

        transport.sendMessage(connection, AgentChatResponse.build(RagPromptTemplates.answerStartMessage(),
                MessageType.RAG_ANSWER_START));
        CompletableFuture<Void> userPersistFuture = persistUserMessageAsync(ragContext, userEntity, retrievalResult,
                runtimeSupport);

        StreamingChatModel streamingClient = llmServiceFactory.getStreamingClient(ragContext.getProvider(),
                ragContext.getModel());
        Agent agent = runtimeSupport.buildStreamingAgent(streamingClient, memory, toolProvider, ragContext);
        String ragPrompt = ragAnswerPromptBuilder.buildUserPrompt(ragContext.getUserMessage(),
                retrievalResult.getRetrievedDocuments(), ragContext, retrievalResult);
        processRagChat(agent, connection, transport, ragContext, userEntity, llmEntity, ragPrompt, retrievalResult,
                runtimeSupport, userPersistFuture);
    }

    public void persistUserMessage(RagChatContext ragContext, MessageEntity userEntity,
            RagRetrievalResult retrievalResult, RuntimeSupport runtimeSupport) {
        if (userEntity == null) {
            return;
        }
        attachUserMetadata(userEntity, retrievalResult);
        int estimatedUserTokens = userEntity.getBodyTokenCount() != null && userEntity.getBodyTokenCount() > 0
                ? userEntity.getBodyTokenCount()
                : runtimeSupport.estimateMessageBodyTokens(ragContext, userEntity.getContent());
        userEntity.setBodyTokenCount(estimatedUserTokens);
        userEntity.setTokenCount(estimatedUserTokens);
        if (StringUtils.isBlank(userEntity.getId())) {
            messageDomainService.saveMessageAndUpdateContext(Collections.singletonList(userEntity),
                    ragContext.getContextEntity());
            return;
        }
        messageDomainService.updateMessage(userEntity);
    }

    private CompletableFuture<Void> persistUserMessageAsync(RagChatContext ragContext, MessageEntity userEntity,
            RagRetrievalResult retrievalResult, RuntimeSupport runtimeSupport) {
        return CompletableFuture.runAsync(() -> persistUserMessage(ragContext, userEntity, retrievalResult,
                runtimeSupport), persistenceTaskExecutor).whenComplete((ignored, throwable) -> {
                    if (throwable != null) {
                        Throwable cause = throwable instanceof CompletionException && throwable.getCause() != null
                                ? throwable.getCause()
                                : throwable;
                        logger.error("异步保存RAG用户消息失败: sessionId={}, userId={}, err={}",
                                ragContext != null ? ragContext.getSessionId() : null,
                                ragContext != null ? ragContext.getUserId() : null, cause.getMessage(), cause);
                    }
                });
    }

    private void awaitUserMessagePersisted(CompletableFuture<Void> userPersistFuture, RagChatContext ragContext) {
        if (userPersistFuture == null) {
            return;
        }
        try {
            userPersistFuture.join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new IllegalStateException("保存RAG用户消息失败: " + cause.getMessage(), cause);
        }
    }

    public void persistEarlyAssistantMessage(RagChatContext ragContext, RagRetrievalResult retrievalResult,
            MessageEntity llmEntity, String content, MessageType messageType, RuntimeSupport runtimeSupport) {
        if (llmEntity == null || StringUtils.isBlank(content)) {
            return;
        }
        llmEntity.setContent(content);
        llmEntity.setMessageType(messageType == null ? MessageType.TEXT : messageType);
        int estimatedTokens = runtimeSupport.estimateMessageBodyTokens(ragContext, content);
        llmEntity.setBodyTokenCount(estimatedTokens);
        llmEntity.setTokenCount(estimatedTokens);
        attachAssistantMetadata(llmEntity, retrievalResult);
        messageDomainService.saveMessageAndUpdateContext(Collections.singletonList(llmEntity),
                ragContext.getContextEntity());
        if (ragContext != null) {
            ragContext.setCurrentAssistantReply(content);
        }
    }

    private <T> void processRagChat(Agent agent, T connection, MessageTransport<T> transport, RagChatContext ragContext,
            MessageEntity userEntity, MessageEntity llmEntity, String ragPrompt, RagRetrievalResult retrievalResult,
            RuntimeSupport runtimeSupport, CompletableFuture<Void> userPersistFuture) {
        AtomicReference<StringBuilder> messageBuilder = new AtomicReference<>(new StringBuilder());
        UserMessage currentUserMessage = runtimeSupport.buildUserMessage(ragPrompt, ragContext);
        TokenStream tokenStream = agent.chat(currentUserMessage);

        long startTime = System.currentTimeMillis();
        ThinkingEventEmitter<T> thinkingEventEmitter = new ThinkingEventEmitter<>(transport, connection);
        ChatContextProperties.RagAnswer ragAnswer = chatContextProperties.getFragmentEmitter().getRagAnswer();
        StreamingFragmentEmitter<T> answerEmitter = new StreamingFragmentEmitter<>(transport, connection,
                MessageType.RAG_ANSWER_PROGRESS, ragAnswer.getFlushChars(), ragAnswer.getFlushIntervalMs(),
                ragAnswer.getMaxDeferChars());
        AtomicInteger reasoningChunkCount = new AtomicInteger();
        AtomicInteger reasoningCharCount = new AtomicInteger();
        AtomicReference<String> firstReasoningPreview = new AtomicReference<>();
        AtomicInteger answerChunkCount = new AtomicInteger();
        AtomicInteger answerCharCount = new AtomicInteger();

        tokenStream.onError(throwable -> {
            try {
                if (runtimeSupport.isInterrupted(ragContext)) {
                    logger.info("跳过已中断RAG对话的错误处理: sessionId={}, userId={}", ragContext.getSessionId(),
                            ragContext.getUserId());
                    runtimeSupport.onChatCompleted(ragContext, false, "interrupted");
                    return;
                }
                logger.warn(
                        "RAG流式失败: sessionId={}, userId={}, modelId={}, reasoningChunks={}, reasoningChars={}, answerChunks={}, answerChars={}, err={}",
                        ragContext.getSessionId(), ragContext.getUserId(),
                        ragContext.getModel() != null ? ragContext.getModel().getModelId() : null,
                        reasoningChunkCount.get(), reasoningCharCount.get(), answerChunkCount.get(), answerCharCount.get(),
                        throwable.getMessage());
                thinkingEventEmitter.flushProgress();
                answerEmitter.flush();
                persistPartialResponseOnError(ragContext, userEntity, llmEntity, messageBuilder.get(), runtimeSupport,
                        userPersistFuture);
                transport.sendEndMessage(connection, ChatErrorResponseFactory.fromThrowable(throwable));

                long latency = System.currentTimeMillis() - startTime;
                highAvailabilityDomainService.reportCallResult(ragContext.getInstanceId(), ragContext.getModel().getId(),
                        false, latency, throwable.getMessage());
                runtimeSupport.onChatError(ragContext, throwable);
                runtimeSupport.onChatCompleted(ragContext, false, throwable.getMessage());
            } finally {
                transport.completeConnection(connection);
            }
        });

        tokenStream.onPartialResponse(fragment -> {
            if (runtimeSupport.isInterrupted(ragContext)) {
                return;
            }
            thinkingEventEmitter.beforeAnswerFragment();
            answerChunkCount.incrementAndGet();
            answerCharCount.addAndGet(fragment == null ? 0 : fragment.length());
            messageBuilder.get().append(fragment);
            answerEmitter.onFragment(fragment);
        });

        tokenStream.onPartialReasoning(reasoning -> {
            if (runtimeSupport.isInterrupted(ragContext)) {
                return;
            }
            int currentChunk = reasoningChunkCount.incrementAndGet();
            reasoningCharCount.addAndGet(reasoning == null ? 0 : reasoning.length());
            if (currentChunk == 1) {
                firstReasoningPreview.set(abbreviateForLog(reasoning));
                logger.info("RAG收到首个思考片段: sessionId={}, userId={}, modelId={}, preview={}",
                        ragContext.getSessionId(), ragContext.getUserId(),
                        ragContext.getModel() != null ? ragContext.getModel().getModelId() : null,
                        firstReasoningPreview.get());
            }
            thinkingEventEmitter.onReasoning(reasoning);
        });

        tokenStream.onCompleteResponse(chatResponse -> {
            try {
                if (runtimeSupport.isInterrupted(ragContext)) {
                    logger.info("跳过已中断RAG对话的完成处理: sessionId={}, userId={}", ragContext.getSessionId(),
                            ragContext.getUserId());
                    runtimeSupport.onChatCompleted(ragContext, false, "interrupted");
                    return;
                }
                logger.info(
                        "RAG流式完成: sessionId={}, userId={}, modelId={}, reasoningChunks={}, reasoningChars={}, answerChunks={}, answerChars={}, firstReasoningPreview={}",
                        ragContext.getSessionId(), ragContext.getUserId(),
                        ragContext.getModel() != null ? ragContext.getModel().getModelId() : null,
                        reasoningChunkCount.get(), reasoningCharCount.get(), answerChunkCount.get(), answerCharCount.get(),
                        firstReasoningPreview.get());
                thinkingEventEmitter.finishThinkingIfNeeded();
                answerEmitter.flush();
                setMessageTokenCount(ragContext.getMessageHistory(), userEntity, llmEntity, chatResponse, runtimeSupport);
                attachAssistantMetadata(llmEntity, retrievalResult);

                awaitUserMessagePersisted(userPersistFuture, ragContext);
                messageDomainService.updateMessage(userEntity);
                messageDomainService.saveMessageAndUpdateContext(Collections.singletonList(llmEntity),
                        ragContext.getContextEntity());

                transport.sendEndMessage(connection,
                        AgentChatResponse.buildEndMessage(RagPromptTemplates.answerEndMessage(), MessageType.RAG_ANSWER_END));

                long latency = System.currentTimeMillis() - startTime;
                highAvailabilityDomainService.reportCallResult(ragContext.getInstanceId(), ragContext.getModel().getId(),
                        true, latency, null);

                runtimeSupport.performBilling(ragContext, safeInputTokenCount(userEntity, chatResponse),
                        safeOutputTokenCount(llmEntity, chatResponse), transport, connection);

                ragContext.setCurrentAssistantReply(llmEntity.getContent());
                runtimeSupport.onChatCompleted(ragContext, true, null);
                runtimeSupport.afterAnswerCompleted(ragContext);
            } catch (Exception e) {
                logger.error("RAG流式完成处理失败: sessionId={}, userId={}, err={}", ragContext.getSessionId(),
                        ragContext.getUserId(), e.getMessage(), e);
                runtimeSupport.onChatError(ragContext, e);
                runtimeSupport.onChatCompleted(ragContext, false, e.getMessage());
                transport.sendMessage(connection, ChatErrorResponseFactory.fromThrowable(e));
            } finally {
                transport.completeConnection(connection);
            }
        });

        tokenStream.start();
    }

    private void setMessageTokenCount(List<MessageEntity> historyMessages, MessageEntity userEntity, MessageEntity llmEntity,
            ChatResponse chatResponse, RuntimeSupport runtimeSupport) {
        String aiText = chatResponse != null && chatResponse.aiMessage() != null ? chatResponse.aiMessage().text() : "";
        llmEntity.setContent(aiText);
        int estimatedOutputTokens = runtimeSupport.estimateMessageBodyTokens(null, aiText);
        Integer outputTokens = ChatResponseTokenUsageUtils.outputTokenCount(chatResponse);
        llmEntity.setTokenCount(outputTokens != null && outputTokens > 0 ? outputTokens : estimatedOutputTokens);
        llmEntity.setBodyTokenCount(outputTokens != null && outputTokens > 0 ? outputTokens : estimatedOutputTokens);

        int estimatedUserTokens = userEntity.getBodyTokenCount() != null && userEntity.getBodyTokenCount() > 0
                ? userEntity.getBodyTokenCount()
                : runtimeSupport.estimateMessageBodyTokens(null, userEntity.getContent());
        userEntity.setBodyTokenCount(estimatedUserTokens);
        userEntity.setTokenCount(estimatedUserTokens);
    }

    private void persistPartialResponseOnError(RagChatContext ragContext, MessageEntity userEntity, MessageEntity llmEntity,
            StringBuilder messageBuilder, RuntimeSupport runtimeSupport, CompletableFuture<Void> userPersistFuture) {
        try {
            awaitUserMessagePersisted(userPersistFuture, ragContext);
            String partialContent = messageBuilder != null ? messageBuilder.toString() : "";
            if (StringUtils.isBlank(partialContent)) {
                messageDomainService.updateMessage(userEntity);
                return;
            }
            llmEntity.setContent(partialContent);
            llmEntity.setMessageType(MessageType.ERROR);
            int estimatedTokens = runtimeSupport.estimateMessageBodyTokens(ragContext, partialContent);
            llmEntity.setBodyTokenCount(estimatedTokens);
            llmEntity.setTokenCount(estimatedTokens);
            messageDomainService.updateMessage(userEntity);
            messageDomainService.saveMessageAndUpdateContext(Collections.singletonList(llmEntity),
                    ragContext.getContextEntity());
        } catch (Exception e) {
            runtimeSupport.onPersistPartialFailure(ragContext, e);
        }
    }

    private Integer safeInputTokenCount(MessageEntity userEntity, ChatResponse chatResponse) {
        Integer inputTokens = ChatResponseTokenUsageUtils.inputTokenCount(chatResponse);
        if (inputTokens != null) {
            return inputTokens;
        }
        return userEntity != null && userEntity.getTokenCount() != null ? userEntity.getTokenCount() : 0;
    }

    private Integer safeOutputTokenCount(MessageEntity llmEntity, ChatResponse chatResponse) {
        Integer outputTokens = ChatResponseTokenUsageUtils.outputTokenCount(chatResponse);
        if (outputTokens != null) {
            return outputTokens;
        }
        return llmEntity != null && llmEntity.getTokenCount() != null ? llmEntity.getTokenCount() : 0;
    }

    private void attachUserMetadata(MessageEntity userEntity, RagRetrievalResult retrievalResult) {
        if (userEntity == null || retrievalResult == null) {
            return;
        }
        Map<String, Object> ragMetadata = new LinkedHashMap<>();
        ragMetadata.put("originalQuestion", retrievalResult.getOriginalQuestion());
        ragMetadata.put("retrievalQuery", retrievalResult.getEffectiveQuestion());
        ragMetadata.put("rewriteApplied", retrievalResult.isRewriteApplied());
        ragMetadata.put("retrievalTimeMs", retrievalResult.getRetrievalTime());
        ragMetadata.put("retrievedDocIds", collectRetrievedDocIds(retrievalResult.getRetrievedDocuments()));
        userEntity.setMetadata(ConversationMetadataUtils.mergeJson(userEntity.getMetadata(),
                ConversationMetadataUtils.singleton("rag", ragMetadata)));
    }

    private void attachAssistantMetadata(MessageEntity llmEntity, RagRetrievalResult retrievalResult) {
        if (llmEntity == null || retrievalResult == null) {
            return;
        }
        Map<String, String> citationMap = collectCitationMap(retrievalResult.getRetrievedDocuments());
        Set<String> validCitationIds = new LinkedHashSet<>(citationMap.keySet());
        Set<String> citedCitationIds = parseReferencedDocIds(llmEntity.getContent(), validCitationIds);
        Set<String> citedDocIds = citedCitationIds.stream().map(citationMap::get).filter(StringUtils::isNotBlank)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<String> validDocIds = new LinkedHashSet<>(citationMap.values());
        Map<String, Object> ragMetadata = new LinkedHashMap<>();
        ragMetadata.put("citedDocIds", new ArrayList<>(citedDocIds));
        ragMetadata.put("citedCitationIds", new ArrayList<>(citedCitationIds));
        ragMetadata.put("retrievedDocIds", new ArrayList<>(validDocIds));
        ragMetadata.put("citationMap", citationMap);
        ragMetadata.put("retrievalQuery", retrievalResult.getEffectiveQuestion());
        ragMetadata.put("rewriteApplied", retrievalResult.isRewriteApplied());
        llmEntity.setMetadata(ConversationMetadataUtils.mergeJson(llmEntity.getMetadata(),
                ConversationMetadataUtils.singleton("rag", ragMetadata)));
    }

    private List<String> collectRetrievedDocIds(List<DocumentUnitDTO> documents) {
        if (documents == null || documents.isEmpty()) {
            return Collections.emptyList();
        }
        return documents.stream().map(DocumentUnitDTO::getId).filter(StringUtils::isNotBlank).distinct().toList();
    }

    private Map<String, String> collectCitationMap(List<DocumentUnitDTO> documents) {
        if (documents == null || documents.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> citationMap = new LinkedHashMap<>();
        for (DocumentUnitDTO document : documents) {
            if (document == null || StringUtils.isBlank(document.getId())) {
                continue;
            }
            String citationId = StringUtils.isNotBlank(document.getCitationId()) ? document.getCitationId()
                    : document.getId();
            citationMap.putIfAbsent(citationId, document.getId());
        }
        return citationMap;
    }

    private Set<String> parseReferencedDocIds(String content, Set<String> validDocIds) {
        if (StringUtils.isBlank(content) || validDocIds == null || validDocIds.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> citedDocIds = new LinkedHashSet<>();
        Set<String> normalizedValidIds = validDocIds.stream().filter(StringUtils::isNotBlank)
                .map(value -> value.trim().toUpperCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        for (Pattern citationPattern : DOC_CITATION_PATTERNS) {
            Matcher matcher = citationPattern.matcher(content);
            while (matcher.find()) {
                String docId = StringUtils.trimToEmpty(matcher.group(1));
                if (normalizedValidIds.contains(docId.toUpperCase(Locale.ROOT))) {
                    citedDocIds.add(docId);
                }
            }
        }
        return citedDocIds;
    }

    private String abbreviateForLog(String text) {
        if (StringUtils.isBlank(text)) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 120 ? normalized : normalized.substring(0, 120) + "...";
    }

    private static final class ThinkingEventEmitter<T> {
        private final MessageTransport<T> transport;
        private final T connection;
        private final StreamingFragmentEmitter<T> reasoningEmitter;
        private boolean thinkingObserved;
        private boolean thinkingStarted;
        private boolean thinkingEnded;

        private ThinkingEventEmitter(MessageTransport<T> transport, T connection) {
            this.transport = transport;
            this.connection = connection;
            this.reasoningEmitter = new StreamingFragmentEmitter<>(transport, connection, MessageType.RAG_THINKING_PROGRESS,
                    240, 80L, 960);
        }

        private void onReasoning(String reasoning) {
            thinkingObserved = true;
            if (!thinkingStarted) {
                transport.sendMessage(connection, AgentChatResponse.build("开始思考...", MessageType.RAG_THINKING_START));
                thinkingStarted = true;
            }
            reasoningEmitter.onFragment(reasoning);
        }

        private void beforeAnswerFragment() {
            if (!thinkingStarted) {
                return;
            }
            finishThinkingIfNeeded();
        }

        private void flushProgress() {
            reasoningEmitter.flush();
        }

        private void finishThinkingIfNeeded() {
            if (!thinkingObserved || thinkingEnded) {
                return;
            }
            reasoningEmitter.flush();
            transport.sendMessage(connection, AgentChatResponse.build("思考完成", MessageType.RAG_THINKING_END));
            thinkingEnded = true;
        }
    }

    private static final class StreamingFragmentEmitter<T> {
        private final MessageTransport<T> transport;
        private final T connection;
        private final MessageType messageType;
        private final int flushChars;
        private final long flushIntervalMs;
        private final int maxDeferChars;
        private final StringBuilder buffer = new StringBuilder();
        private long lastFlushAt = System.currentTimeMillis();

        private StreamingFragmentEmitter(MessageTransport<T> transport, T connection, MessageType messageType,
                int flushChars, long flushIntervalMs, int maxDeferChars) {
            this.transport = transport;
            this.connection = connection;
            this.messageType = messageType;
            this.flushChars = Math.max(32, flushChars);
            this.flushIntervalMs = Math.max(16L, flushIntervalMs);
            this.maxDeferChars = Math.max(this.flushChars, maxDeferChars);
        }

        private void onFragment(String fragment) {
            if (fragment == null || fragment.isEmpty()) {
                return;
            }
            buffer.append(fragment);
            long now = System.currentTimeMillis();
            if (shouldFlush(now, fragment)) {
                flush();
            }
        }

        private void flush() {
            if (buffer.length() == 0) {
                return;
            }
            String content = buffer.toString();
            buffer.setLength(0);
            lastFlushAt = System.currentTimeMillis();
            transport.sendMessage(connection, AgentChatResponse.build(content, messageType));
        }

        private boolean containsLineBreak(String fragment) {
            return fragment.indexOf('\n') >= 0 || fragment.indexOf('\r') >= 0;
        }

        private boolean shouldFlush(long now, String fragment) {
            boolean thresholdReached = buffer.length() >= flushChars;
            boolean intervalReached = now - lastFlushAt >= flushIntervalMs;
            boolean lineBreakReached = containsLineBreak(fragment);
            if (!thresholdReached && !intervalReached && !lineBreakReached) {
                return false;
            }
            if (buffer.length() >= maxDeferChars) {
                return true;
            }
            return !hasUnclosedCodeFence() && !hasIncompleteMarkdownTableRow();
        }

        private boolean hasUnclosedCodeFence() {
            int fenceCount = 0;
            int startIndex = 0;
            String content = buffer.toString();
            while ((startIndex = content.indexOf("```", startIndex)) >= 0) {
                fenceCount++;
                startIndex += 3;
            }
            return fenceCount % 2 != 0;
        }

        private boolean hasIncompleteMarkdownTableRow() {
            String content = buffer.toString();
            if (content.isEmpty()) {
                return false;
            }
            int lastLineBreak = Math.max(content.lastIndexOf('\n'), content.lastIndexOf('\r'));
            String lastLine = lastLineBreak >= 0 ? content.substring(lastLineBreak + 1) : content;
            String trimmed = lastLine.trim();
            if (trimmed.isEmpty()) {
                return false;
            }
            return trimmed.contains("|") && !content.endsWith("\n") && !content.endsWith("\r");
        }
    }

    public interface RuntimeSupport {
        Agent buildStreamingAgent(StreamingChatModel model, MessageWindowChatMemory memory, ToolProvider toolProvider,
                RagChatContext ragChatContext);

        UserMessage buildUserMessage(String text, RagChatContext ragChatContext);

        void performBilling(RagChatContext ragChatContext, Integer inputTokens, Integer outputTokens,
                MessageTransport<?> transport, Object connection);

        void onChatCompleted(RagChatContext ragChatContext, boolean success, String errorMessage);

        void onChatError(RagChatContext ragChatContext, Throwable throwable);

        void afterAnswerCompleted(RagChatContext ragChatContext);

        int estimateMessageBodyTokens(RagChatContext ragChatContext, String content);

        void onPersistPartialFailure(RagChatContext ragChatContext, Exception e);

        boolean isInterrupted(RagChatContext ragChatContext);
    }
}
