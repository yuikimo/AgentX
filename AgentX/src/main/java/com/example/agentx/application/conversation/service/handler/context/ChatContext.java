package com.example.agentx.application.conversation.service.handler.context;

import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import com.example.agentx.domain.agent.model.AgentEntity;
import com.example.agentx.domain.agent.model.LLMModelConfig;
import com.example.agentx.domain.conversation.model.ConversationAttachment;
import com.example.agentx.domain.conversation.constant.Role;
import com.example.agentx.domain.conversation.model.ContextEntity;
import com.example.agentx.domain.conversation.model.MessageEntity;
import com.example.agentx.domain.llm.model.ModelEntity;
import com.example.agentx.domain.llm.model.ProviderEntity;
import com.example.agentx.domain.trace.model.TraceContext;
import com.example.agentx.infrastructure.llm.config.ProviderConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/** chat 上下文，包含对话所需的所有信息 */
public class ChatContext {
    /** 会话ID */
    private String sessionId;

    /** 当前请求轮次ID，用于区分同一会话内的不同流式调用 */
    private String turnId = UUID.randomUUID().toString();

    /** 用户ID */
    private String userId;

    /** 用户消息 */
    private String userMessage;

    /** 当前轮AI回复文本（用于记忆抽取等后处理） */
    private String currentAssistantReply;

    /** 智能体实体 */
    private AgentEntity agent;

    /** 模型实体 */
    private ModelEntity model;

    /** 原始模型实体（用于追踪模型切换） */
    private ModelEntity originalModel;

    /** 服务商实体 */
    private ProviderEntity provider;

    /** 原始服务商实体（用于追踪服务商切换） */
    private ProviderEntity originalProvider;

    /** 大模型配置 */
    private LLMModelConfig llmModelConfig;

    /** 上下文实体 */
    private ContextEntity contextEntity;

    /** 历史消息列表 */
    private List<MessageEntity> messageHistory;

    /** 使用的 mcp server name */
    private List<String> mcpServerNames;

    /** 多模态的文件 */
    private List<String> fileUrls;

    /** 附件信息 */
    private List<ConversationAttachment> attachments = new ArrayList<>();

    /** 高可用实例ID */
    private String instanceId;

    /** 是否流式响应 */
    private boolean streaming = true;

    /** 追踪上下文 */
    private TraceContext traceContext;

    /** 是否为公开访问（嵌入模式） */
    private boolean publicAccess = false;

    /** 公开访问ID（嵌入模式使用） */
    private String publicId;

    /** Prompt 观测：稳定 system sections */
    private List<String> promptStableSystemSections = new ArrayList<>();

    /** Prompt 观测：动态 system sections */
    private List<String> promptDynamicSystemSections = new ArrayList<>();

    /** Prompt 观测：注入的记忆条数 */
    private int promptMemoryItemCount;

    /** Prompt 观测：是否启用 provider prompt cache */
    private boolean promptCachingEnabled;

    /** 图片是否已退化为文本输入 */
    private boolean imageFallbackApplied;

    /** 当前轮是否优先补充图片 OCR 文本上下文 */
    private boolean preferImageOcrContext;

    /** 工具可用性提示 */
    private String toolAvailabilityNotice;

    /** 当前轮工具目录提示 */
    private String toolCatalogPrompt;

    /** 当前上下文解析出的 ProviderConfig 缓存，避免同一轮重复构建 */
    private transient ProviderConfig resolvedProviderConfig;

    /** 需要在对话结束后释放的资源 */
    private final List<AutoCloseable> closeableResources = Collections.synchronizedList(new ArrayList<>());

    /** 资源是否已关闭 */
    private final AtomicBoolean resourcesClosed = new AtomicBoolean(false);

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getTurnId() {
        return turnId;
    }

    public void setTurnId(String turnId) {
        this.turnId = StringUtils.isBlank(turnId) ? UUID.randomUUID().toString() : turnId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getUserMessage() {
        return userMessage;
    }

    public void setUserMessage(String userMessage) {
        this.userMessage = userMessage;
    }

    public String getCurrentAssistantReply() {
        return currentAssistantReply;
    }

    public void setCurrentAssistantReply(String currentAssistantReply) {
        this.currentAssistantReply = currentAssistantReply;
    }

    public AgentEntity getAgent() {
        return agent;
    }

    public void setAgent(AgentEntity agent) {
        this.agent = agent;
    }

    public ModelEntity getModel() {
        return model;
    }

    public void setModel(ModelEntity model) {
        this.model = model;
        clearResolvedProviderConfig();
    }

    public ModelEntity getOriginalModel() {
        return originalModel;
    }

    public void setOriginalModel(ModelEntity originalModel) {
        this.originalModel = originalModel;
        clearResolvedProviderConfig();
    }

    public ProviderEntity getProvider() {
        return provider;
    }

    public void setProvider(ProviderEntity provider) {
        this.provider = provider;
        clearResolvedProviderConfig();
    }

    public ProviderEntity getOriginalProvider() {
        return originalProvider;
    }

    public void setOriginalProvider(ProviderEntity originalProvider) {
        this.originalProvider = originalProvider;
        clearResolvedProviderConfig();
    }

    public LLMModelConfig getLlmModelConfig() {
        return llmModelConfig;
    }

    public void setLlmModelConfig(LLMModelConfig llmModelConfig) {
        this.llmModelConfig = llmModelConfig;
    }

    public ProviderConfig getResolvedProviderConfig() {
        return resolvedProviderConfig;
    }

    public void setResolvedProviderConfig(ProviderConfig resolvedProviderConfig) {
        this.resolvedProviderConfig = resolvedProviderConfig;
    }

    public void clearResolvedProviderConfig() {
        this.resolvedProviderConfig = null;
    }

    public ContextEntity getContextEntity() {
        return contextEntity;
    }

    public void setContextEntity(ContextEntity contextEntity) {
        this.contextEntity = contextEntity;
    }

    public List<MessageEntity> getMessageHistory() {
        return messageHistory;
    }

    public void setMessageHistory(List<MessageEntity> messageHistory) {
        this.messageHistory = messageHistory;
    }

    public List<String> getMcpServerNames() {
        return mcpServerNames;
    }

    public void setMcpServerNames(List<String> mcpServerNames) {
        this.mcpServerNames = mcpServerNames;
    }

    public List<String> getFileUrls() {
        return fileUrls;
    }

    public void setFileUrls(List<String> fileUrls) {
        this.fileUrls = fileUrls;
    }

    public List<ConversationAttachment> getAttachments() {
        return attachments;
    }

    public void setAttachments(List<ConversationAttachment> attachments) {
        this.attachments = safeCopyAttachments(attachments);
    }

    public String getInstanceId() {
        return instanceId;
    }

    public void setInstanceId(String instanceId) {
        this.instanceId = instanceId;
    }

    public boolean isStreaming() {
        return streaming;
    }

    public void setStreaming(boolean streaming) {
        this.streaming = streaming;
    }

    public TraceContext getTraceContext() {
        return traceContext;
    }

    public void setTraceContext(TraceContext traceContext) {
        this.traceContext = traceContext;
    }

    public boolean isPublicAccess() {
        return publicAccess;
    }

    public void setPublicAccess(boolean publicAccess) {
        this.publicAccess = publicAccess;
    }

    public String getPublicId() {
        return publicId;
    }

    public void setPublicId(String publicId) {
        this.publicId = publicId;
    }

    public List<String> getPromptStableSystemSections() {
        return promptStableSystemSections;
    }

    public void setPromptStableSystemSections(List<String> promptStableSystemSections) {
        this.promptStableSystemSections = safeCopy(promptStableSystemSections);
    }

    public List<String> getPromptDynamicSystemSections() {
        return promptDynamicSystemSections;
    }

    public void setPromptDynamicSystemSections(List<String> promptDynamicSystemSections) {
        this.promptDynamicSystemSections = safeCopy(promptDynamicSystemSections);
    }

    public int getPromptMemoryItemCount() {
        return promptMemoryItemCount;
    }

    public void setPromptMemoryItemCount(int promptMemoryItemCount) {
        this.promptMemoryItemCount = promptMemoryItemCount;
    }

    public boolean isPromptCachingEnabled() {
        return promptCachingEnabled;
    }

    public void setPromptCachingEnabled(boolean promptCachingEnabled) {
        this.promptCachingEnabled = promptCachingEnabled;
    }

    public boolean isImageFallbackApplied() {
        return imageFallbackApplied;
    }

    public void setImageFallbackApplied(boolean imageFallbackApplied) {
        this.imageFallbackApplied = imageFallbackApplied;
    }

    public boolean isPreferImageOcrContext() {
        return preferImageOcrContext;
    }

    public void setPreferImageOcrContext(boolean preferImageOcrContext) {
        this.preferImageOcrContext = preferImageOcrContext;
    }

    public String getToolAvailabilityNotice() {
        return toolAvailabilityNotice;
    }

    public void setToolAvailabilityNotice(String toolAvailabilityNotice) {
        this.toolAvailabilityNotice = toolAvailabilityNotice;
    }

    public String getToolCatalogPrompt() {
        return toolCatalogPrompt;
    }

    public void setToolCatalogPrompt(String toolCatalogPrompt) {
        this.toolCatalogPrompt = toolCatalogPrompt;
    }

    public void recordPromptAssembly(List<String> stableSections, List<String> dynamicSections, int memoryItemCount,
            boolean cacheEnabled) {
        this.promptStableSystemSections = safeCopy(stableSections);
        this.promptDynamicSystemSections = safeCopy(dynamicSections);
        this.promptMemoryItemCount = Math.max(memoryItemCount, 0);
        this.promptCachingEnabled = cacheEnabled;
    }

    public void registerCloseableResource(AutoCloseable closeable) {
        if (closeable == null || resourcesClosed.get()) {
            return;
        }
        closeableResources.add(closeable);
    }

    public void closeResourcesQuietly() {
        if (!resourcesClosed.compareAndSet(false, true)) {
            return;
        }
        synchronized (closeableResources) {
            for (AutoCloseable closeable : closeableResources) {
                if (closeable == null) {
                    continue;
                }
                try {
                    closeable.close();
                } catch (Exception ignored) {
                }
            }
            closeableResources.clear();
        }
    }

    public String toPromptSnapshot() {
        List<String> stableSections = promptStableSystemSections != null ? promptStableSystemSections : Collections.emptyList();
        List<String> dynamicSections = promptDynamicSystemSections != null ? promptDynamicSystemSections
                : Collections.emptyList();
        List<MessageEntity> history = messageHistory != null ? messageHistory : Collections.emptyList();
        String historyPreview = history.stream().limit(6)
                .map(message -> String.format("[%s] %s", message.getRole(), abbreviate(message.getContent(), 120)))
                .collect(Collectors.joining("\n"));

        StringBuilder snapshot = new StringBuilder();
        snapshot.append("sessionId=").append(nullToEmpty(sessionId)).append('\n');
        snapshot.append("userId=").append(nullToEmpty(userId)).append('\n');
        snapshot.append("agentId=").append(agent != null ? nullToEmpty(agent.getId()) : "").append('\n');
        snapshot.append("model=").append(model != null ? nullToEmpty(model.getModelId()) : "").append('\n');
        snapshot.append("provider=").append(provider != null ? nullToEmpty(provider.getId()) : "").append('\n');
        snapshot.append("streaming=").append(streaming).append('\n');
        snapshot.append("publicAccess=").append(publicAccess).append('\n');
        snapshot.append("promptCachingEnabled=").append(promptCachingEnabled).append('\n');
        snapshot.append("stableSystemSections=").append(stableSections.size()).append('\n');
        appendSectionPreview(snapshot, "stable", stableSections);
        snapshot.append("dynamicSystemSections=").append(dynamicSections.size()).append('\n');
        appendSectionPreview(snapshot, "dynamic", dynamicSections);
        snapshot.append("memoryItemCount=").append(promptMemoryItemCount).append('\n');
        snapshot.append("historyMessageCount=").append(history.size()).append('\n');
        if (!historyPreview.isEmpty()) {
            snapshot.append("historyPreview=\n").append(historyPreview).append('\n');
        }
        snapshot.append("currentUserMessage=").append(abbreviate(userMessage, 160)).append('\n');
        snapshot.append("currentAssistantReply=").append(abbreviate(currentAssistantReply, 160)).append('\n');
        snapshot.append("fileUrls=").append(fileUrls != null ? fileUrls.size() : 0).append('\n');
        snapshot.append("attachments=").append(attachments != null ? attachments.size() : 0).append('\n');
        snapshot.append("imageFallbackApplied=").append(imageFallbackApplied).append('\n');
        snapshot.append("preferImageOcrContext=").append(preferImageOcrContext).append('\n');
        snapshot.append("toolAvailabilityNotice=").append(abbreviate(toolAvailabilityNotice, 200)).append('\n');
        snapshot.append("toolCatalogPrompt=").append(abbreviate(toolCatalogPrompt, 240));
        return snapshot.toString();
    }

    private void appendSectionPreview(StringBuilder snapshot, String prefix, List<String> sections) {
        for (int i = 0; i < sections.size(); i++) {
            snapshot.append(prefix).append("[").append(i).append("]=")
                    .append(abbreviate(sections.get(i), 200)).append('\n');
        }
    }

    private List<String> safeCopy(List<String> values) {
        return values == null ? new ArrayList<>() : new ArrayList<>(values);
    }

    private List<ConversationAttachment> safeCopyAttachments(List<ConversationAttachment> values) {
        return values == null ? new ArrayList<>() : new ArrayList<>(values);
    }

    private String abbreviate(String value, int maxLength) {
        String normalized = nullToEmpty(value).replace("\r", " ").replace("\n", "\\n");
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
