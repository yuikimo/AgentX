package com.example.agentx.application.conversation.service;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.apache.commons.lang3.StringUtils;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import com.example.agentx.application.conversation.config.ChatContextProperties;
import com.example.agentx.domain.conversation.constant.AttachmentKind;
import com.example.agentx.domain.conversation.model.ConversationAttachment;
import com.example.agentx.domain.conversation.model.MessageEntity;
import com.example.agentx.domain.conversation.service.MessageDomainService;
import com.example.agentx.domain.rag.model.ModelConfig;
import com.example.agentx.domain.token.service.TokenEstimatorService;
import com.example.agentx.infrastructure.rag.detector.TikaFileTypeDetector;
import com.example.agentx.infrastructure.llm.LLMProviderService;
import com.example.agentx.infrastructure.llm.config.ProviderConfigFactory;
import com.example.agentx.infrastructure.rag.service.UserModelConfigResolver;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class ConversationAttachmentService {

    private static final Logger logger = LoggerFactory.getLogger(ConversationAttachmentService.class);
    private static final int DEFAULT_MAX_FETCH_BYTES = 3 * 1024 * 1024;
    private static final int MAX_SUMMARY_CHARS = 1200;
    private static final int MIN_DOCUMENT_SUMMARY_BUDGET_TOKENS = 400;
    private static final int DEFAULT_DOCUMENT_SUMMARY_BUDGET_TOKENS = 2000;
    private static final String IMAGE_ANALYSIS_PROMPT = "请简洁但尽量完整地描述这张图片中的关键信息，重点提取可帮助后续问答的文字、对象、场景与结构。";
    private static final Pattern IMAGE_OCR_DIRECT_INTENT_PATTERN = Pattern.compile(
            "(?i)(ocr|识别|提取|抽取|读取|读出|转文字|转文本|文字版|抄写|复制文字|提炼图片文字)");
    private static final Pattern IMAGE_OCR_TEXTUAL_TARGET_PATTERN = Pattern.compile(
            "(?i)(文字|文本|字幕|报错|错误信息|错误日志|日志|命令|代码|配置|接口返回|响应内容|表格|菜单|按钮)");
    private static final Pattern IMAGE_ANALYSIS_INTENT_PATTERN = Pattern.compile(
            "(?i)(分析|看看|看下|看一下|解读|说明|介绍|总结|概括|描述)");
    private static final Pattern IMAGE_OCR_FOLLOW_UP_PATTERN = Pattern.compile(
            "(第[一二三四五六七八九十百0-9]+[行列段条页]|哪一[行列段条页]|写了什么|写的什么|图里|图片里|图中|截图里|截图中)");

    private final UserModelConfigResolver userModelConfigResolver;
    private final ProviderConfigFactory providerConfigFactory;
    private final MessageDomainService messageDomainService;
    private final TokenEstimatorService tokenEstimatorService;
    private final TaskExecutor attachmentProcessingTaskExecutor;
    private final Cache<String, String> documentSummaryCache;
    private final Cache<String, String> imageSummaryCache;
    private final ConcurrentMap<String, CompletableFuture<String>> documentSummaryTasks = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CompletableFuture<String>> imageSummaryTasks = new ConcurrentHashMap<>();
    private final int syncFullSummaryMaxBytes;
    private final int syncSampledSummaryMaxBytes;
    private final int maxFetchBytes;
    private final int documentSummaryBudgetTokens;
    private final long imageFallbackOcrWaitMs;
    private final long imagePreferredOcrWaitMs;

    public ConversationAttachmentService(UserModelConfigResolver userModelConfigResolver,
            ProviderConfigFactory providerConfigFactory,
            MessageDomainService messageDomainService,
            TokenEstimatorService tokenEstimatorService,
            @Qualifier("attachmentProcessingTaskExecutor") TaskExecutor attachmentProcessingTaskExecutor,
            ChatContextProperties chatContextProperties) {
        this.userModelConfigResolver = userModelConfigResolver;
        this.providerConfigFactory = providerConfigFactory;
        this.messageDomainService = messageDomainService;
        this.tokenEstimatorService = tokenEstimatorService;
        this.attachmentProcessingTaskExecutor = attachmentProcessingTaskExecutor;
        ChatContextProperties.Attachment attachmentProps = chatContextProperties.getAttachment();
        this.syncFullSummaryMaxBytes = (int) Math.max(8 * 1024L, attachmentProps.getSyncFullMaxBytes());
        this.syncSampledSummaryMaxBytes = (int) Math.max(this.syncFullSummaryMaxBytes,
                attachmentProps.getSyncSampledMaxBytes());
        this.maxFetchBytes = Math.max(DEFAULT_MAX_FETCH_BYTES, this.syncSampledSummaryMaxBytes);
        this.documentSummaryBudgetTokens = Math.max(MIN_DOCUMENT_SUMMARY_BUDGET_TOKENS,
                attachmentProps.getDocumentSummaryBudgetTokens() > 0
                        ? attachmentProps.getDocumentSummaryBudgetTokens()
                        : DEFAULT_DOCUMENT_SUMMARY_BUDGET_TOKENS);
        this.imageFallbackOcrWaitMs = Math.max(0L, attachmentProps.getImageFallbackOcrWaitMs());
        this.imagePreferredOcrWaitMs = Math.max(0L, attachmentProps.getImagePreferredOcrWaitMs());
        this.documentSummaryCache = CacheBuilder.newBuilder()
                .maximumSize(Math.max(1L, attachmentProps.getSummaryCacheMaxSize()))
                .expireAfterWrite(Duration.ofMillis(Math.max(1000L, attachmentProps.getSummaryCacheTtlMs())))
                .build();
        this.imageSummaryCache = CacheBuilder.newBuilder()
                .maximumSize(Math.max(1L, attachmentProps.getSummaryCacheMaxSize()))
                .expireAfterWrite(Duration.ofMillis(Math.max(1000L, attachmentProps.getSummaryCacheTtlMs())))
                .build();
    }

    public List<ConversationAttachment> normalizeAttachments(List<ConversationAttachment> attachments, List<String> fileUrls) {
        if (attachments != null && !attachments.isEmpty()) {
            return attachments.stream().filter(Objects::nonNull).map(this::normalizeAttachment).collect(Collectors.toList());
        }
        if (fileUrls == null || fileUrls.isEmpty()) {
            return Collections.emptyList();
        }
        return fileUrls.stream().filter(StringUtils::isNotBlank).map(this::fromUrl).collect(Collectors.toList());
    }

    public List<ConversationAttachment> prepareCurrentTurnAttachments(String userId, String userMessage,
            boolean preferImageOcrContext, List<ConversationAttachment> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return Collections.emptyList();
        }
        List<ConversationAttachment> prepared = attachments.stream().filter(Objects::nonNull).map(this::copyOf)
                .map(this::normalizeAttachment).collect(Collectors.toList());
        ChatModel ocrModel = resolveOcrModel(userId, false);
        for (ConversationAttachment attachment : prepared) {
            if (attachment.isDocumentLike() && StringUtils.isBlank(attachment.getSummary())) {
                String cachedSummary = getCachedSummary(documentSummaryCache, attachment.getUrl());
                if (StringUtils.isNotBlank(cachedSummary)) {
                    attachment.setSummary(cachedSummary);
                } else if (shouldEagerlyExtractDocumentSummary(attachment)) {
                    String summary = buildEagerDocumentSummary(attachment);
                    if (StringUtils.isNotBlank(summary)) {
                        attachment.setSummary(summary);
                        if (StringUtils.isNotBlank(attachment.getUrl())
                                && !summary.contains("正在后台解析")) {
                            documentSummaryCache.put(attachment.getUrl(), summary);
                        }
                    } else {
                        attachment.setSummary(buildDocumentReferenceSummary(attachment));
                        ensureDocumentSummaryAsync(attachment);
                    }
                } else {
                    attachment.setSummary(buildDocumentReferenceSummary(attachment));
                    ensureDocumentSummaryAsync(attachment);
                }
                continue;
            }
            if (attachment.isImage()) {
                hydrateImageSummary(attachment, ocrModel, preferImageOcrContext ? imagePreferredOcrWaitMs : 0L);
            }
        }
        return prepared;
    }

    public boolean shouldPreferImageOcrContext(String userMessage, List<ConversationAttachment> attachments) {
        if (!hasImageAttachments(attachments)) {
            return false;
        }
        String normalized = StringUtils.defaultString(userMessage).trim();
        if (StringUtils.isBlank(normalized)) {
            return false;
        }
        if (IMAGE_OCR_DIRECT_INTENT_PATTERN.matcher(normalized).find()) {
            return true;
        }
        if (IMAGE_OCR_FOLLOW_UP_PATTERN.matcher(normalized).find()) {
            return true;
        }
        boolean referencesImage = normalized.contains("图片") || normalized.contains("截图")
                || normalized.contains("照片") || normalized.contains("图里") || normalized.contains("图中");
        if (!referencesImage) {
            return false;
        }
        if (IMAGE_OCR_TEXTUAL_TARGET_PATTERN.matcher(normalized).find()) {
            return true;
        }
        return IMAGE_ANALYSIS_INTENT_PATTERN.matcher(normalized).find()
                && (normalized.contains("内容") || normalized.contains("信息") || normalized.contains("意思")
                        || normalized.contains("是什么") || normalized.contains("讲了什么"));
    }

    public void bindImageSummaryPersistence(String userId, MessageEntity messageEntity) {
        if (messageEntity == null || StringUtils.isBlank(messageEntity.getId())) {
            return;
        }
        List<ConversationAttachment> attachments = normalizeAttachments(messageEntity.getAttachments(),
                messageEntity.getFileUrls());
        if (!hasImageAttachments(attachments)) {
            return;
        }
        ChatModel ocrModel = resolveOcrModel(userId, true);
        for (ConversationAttachment attachment : attachments) {
            if (attachment == null || !attachment.isImage()) {
                continue;
            }
            String cachedSummary = resolveImageSummaryFromCache(attachment);
            if (StringUtils.isNotBlank(cachedSummary)) {
                refreshPersistedMessageImageSummaries(messageEntity.getId());
                continue;
            }
            CompletableFuture<String> future = ensureImageSummaryAsync(attachment, ocrModel);
            future.whenComplete((summary, throwable) -> {
                if (throwable != null || StringUtils.isBlank(summary)) {
                    return;
                }
                refreshPersistedMessageImageSummaries(messageEntity.getId());
            });
        }
    }

    public List<ConversationAttachment> buildImageFallbackSummaries(String userId, List<ConversationAttachment> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return Collections.emptyList();
        }
        List<ConversationAttachment> prepared = attachments.stream().filter(Objects::nonNull).map(this::copyOf)
                .map(this::normalizeAttachment).collect(Collectors.toList());
        ChatModel ocrModel = resolveOcrModel(userId, true);
        for (ConversationAttachment attachment : prepared) {
            if (!attachment.isImage() || StringUtils.isNotBlank(attachment.getSummary())) {
                continue;
            }
            String summary = resolveImageSummaryFromCache(attachment);
            if (StringUtils.isBlank(summary) && ocrModel != null) {
                summary = getImageFallbackSummaryWithWait(attachment, ocrModel);
            }
            if (StringUtils.isBlank(summary)) {
                summary = buildImageReferenceSummary(attachment);
            }
            attachment.setSummary(summary);
        }
        return prepared;
    }

    public boolean hasImageAttachments(List<ConversationAttachment> attachments) {
        return attachments != null && attachments.stream().filter(Objects::nonNull).anyMatch(ConversationAttachment::isImage);
    }

    public List<ConversationAttachment> imageAttachments(List<ConversationAttachment> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return Collections.emptyList();
        }
        return attachments.stream().filter(Objects::nonNull).map(this::normalizeAttachment)
                .filter(ConversationAttachment::isImage).collect(Collectors.toList());
    }

    public ImageContent buildImageContent(ConversationAttachment attachment) {
        ConversationAttachment normalized = normalizeAttachment(copyOf(attachment));
        if (normalized == null || !normalized.isImage() || StringUtils.isBlank(normalized.getUrl())) {
            return null;
        }
        FetchedAttachment fetchedAttachment = fetchBytes(normalized.getUrl());
        if (fetchedAttachment == null || fetchedAttachment.isEmpty()) {
            return null;
        }
        byte[] bytes = fetchedAttachment.bytes();
        String mimeType = resolveImageMimeType(normalized, bytes);
        if (StringUtils.isBlank(mimeType) || "未知类型".equals(mimeType)) {
            mimeType = "image/png";
        }
        return ImageContent.from(Base64.getEncoder().encodeToString(bytes), mimeType);
    }

    public String buildCurrentTurnAttachmentText(List<ConversationAttachment> attachments, boolean includeImages) {
        return buildAttachmentText(attachments, includeImages, true);
    }

    public String buildCurrentTurnAttachmentText(List<ConversationAttachment> attachments, boolean includeImages,
            boolean allowImageUrlReference) {
        return buildAttachmentText(attachments, includeImages, allowImageUrlReference);
    }

    public String buildHistoricalAttachmentText(List<ConversationAttachment> attachments) {
        return buildAttachmentText(attachments, true, false);
    }

    public String buildHistoricalAttachmentSummary(List<ConversationAttachment> attachments, int maxImageSummaries) {
        if (attachments == null || attachments.isEmpty()) {
            return "";
        }
        int imageCount = 0;
        int documentCount = 0;
        int otherCount = 0;
        List<String> imageSummaryLines = new ArrayList<>();
        for (ConversationAttachment attachment : attachments) {
            if (attachment == null) {
                continue;
            }
            ConversationAttachment normalized = normalizeAttachment(copyOf(attachment));
            if (normalized == null) {
                continue;
            }
            if (normalized.isImage()) {
                imageCount++;
                String imageSummary = resolveImageSummaryFromCache(normalized);
                if (StringUtils.isNotBlank(imageSummary)
                        && imageSummaryLines.size() < Math.max(0, maxImageSummaries)) {
                    imageSummaryLines.add("- 图片《" + resolveDisplayName(normalized) + "》摘要：" + imageSummary);
                }
            } else if (normalized.isDocumentLike()) {
                documentCount++;
            } else {
                otherCount++;
            }
        }
        int total = imageCount + documentCount + otherCount;
        if (total <= 0) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        if (imageCount > 0) {
            parts.add(imageCount + " 张图片");
        }
        if (documentCount > 0) {
            parts.add(documentCount + " 个文档");
        }
        if (otherCount > 0) {
            parts.add(otherCount + " 个其他附件");
        }
        List<String> lines = new ArrayList<>();
        lines.add("- 该轮用户曾上传 " + total + " 个附件（" + String.join("、", parts) + "）。");
        if (!imageSummaryLines.isEmpty()) {
            lines.add("- 最近图片保留了 OCR 摘要，便于后续追问衔接：");
            lines.addAll(imageSummaryLines);
        } else {
            lines.add("- 详细内容已省略。");
        }
        return "<attachments>\n" + String.join("\n", lines) + "\n</attachments>";
    }

    private String buildAttachmentText(List<ConversationAttachment> attachments, boolean includeImages, boolean allowImageUrlReference) {
        if (attachments == null || attachments.isEmpty()) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        for (ConversationAttachment attachment : attachments) {
            if (attachment == null) {
                continue;
            }
            ConversationAttachment normalized = normalizeAttachment(copyOf(attachment));
            if (normalized.isImage()) {
                if (!includeImages) {
                    continue;
                }
                String summary = resolveImageSummaryFromCache(normalized);
                if (StringUtils.isBlank(summary)) {
                    summary = allowImageUrlReference ? buildImagePromptReference(normalized) : buildImageReferenceSummary(normalized);
                }
                lines.add("- " + summary);
                continue;
            }
            String summary = StringUtils.defaultIfBlank(normalized.getSummary(), buildDocumentReferenceSummary(normalized));
            lines.add("- " + summary);
        }
        if (lines.isEmpty()) {
            return "";
        }
        return "<attachments>\n" + String.join("\n", lines) + "\n</attachments>";
    }

    private ConversationAttachment fromUrl(String url) {
        ConversationAttachment attachment = new ConversationAttachment();
        attachment.setUrl(url);
        return normalizeAttachment(attachment);
    }

    private ConversationAttachment normalizeAttachment(ConversationAttachment attachment) {
        if (attachment == null) {
            return null;
        }
        if (StringUtils.isBlank(attachment.getName())) {
            attachment.setName(resolveName(attachment.getUrl()));
        }
        if (StringUtils.isBlank(attachment.getContentType())) {
            attachment.setContentType(resolveContentType(attachment.getName(), attachment.getUrl()));
        }
        if (attachment.getKind() == null || attachment.getKind() == AttachmentKind.OTHER) {
            attachment.setKind(resolveKind(attachment.getContentType(), attachment.getName(), attachment.getUrl()));
        }
        return attachment;
    }

    private ConversationAttachment copyOf(ConversationAttachment attachment) {
        ConversationAttachment copied = new ConversationAttachment();
        copied.setUrl(attachment.getUrl());
        copied.setName(attachment.getName());
        copied.setContentType(attachment.getContentType());
        copied.setKind(attachment.getKind());
        copied.setSummary(attachment.getSummary());
        return copied;
    }

    private String buildDocumentSummary(ConversationAttachment attachment) {
        FetchedAttachment fetchedAttachment = fetchBytes(attachment.getUrl());
        String extractedText = extractDocumentText(attachment, fetchedAttachment);
        if (StringUtils.isBlank(extractedText)) {
            return buildDocumentReferenceSummary(attachment);
        }
        String excerpt = buildDocumentExcerpt(extractedText, false);
        return "文档《" + resolveDisplayName(attachment) + "》摘录：" + excerpt;
    }

    private String buildEagerDocumentSummary(ConversationAttachment attachment) {
        FetchedAttachment fetchedAttachment = fetchBytes(attachment.getUrl());
        if (fetchedAttachment == null || fetchedAttachment.isEmpty()) {
            return buildDocumentReferenceSummary(attachment);
        }
        if (shouldDeferLargeDocumentSummary(fetchedAttachment)) {
            ensureDocumentSummaryAsync(attachment);
            return buildLargeDocumentProcessingSummary(attachment);
        }
        String extractedText = extractDocumentText(attachment, fetchedAttachment);
        if (StringUtils.isBlank(extractedText)) {
            return buildDocumentReferenceSummary(attachment);
        }
        String normalized = extractedText.replace("\r", "\n").trim();
        if (StringUtils.isBlank(normalized)) {
            return buildDocumentReferenceSummary(attachment);
        }
        if (shouldSampleLargeDocumentSummary(fetchedAttachment)) {
            return "文档《" + resolveDisplayName(attachment) + "》较长，以下为抽样摘录：" + buildDocumentExcerpt(normalized, true);
        }
        return "文档《" + resolveDisplayName(attachment) + "》摘录：" + buildDocumentExcerpt(normalized, false);
    }

    private String extractDocumentText(ConversationAttachment attachment, FetchedAttachment fetchedAttachment) {
        if (fetchedAttachment == null || fetchedAttachment.isEmpty()) {
            return null;
        }
        byte[] bytes = fetchedAttachment.bytes();
        String contentType = StringUtils.defaultString(attachment.getContentType()).toLowerCase(Locale.ROOT);
        String name = StringUtils.defaultString(attachment.getName()).toLowerCase(Locale.ROOT);
        try {
            if (contentType.startsWith("text/") || endsWithAny(name, ".txt", ".md", ".csv", ".html", ".xml", ".json")) {
                return new String(bytes, StandardCharsets.UTF_8);
            }
            if (contentType.contains("pdf") || name.endsWith(".pdf")) {
                try (PDDocument document = Loader.loadPDF(bytes)) {
                    return new PDFTextStripper().getText(document);
                }
            }
            if (contentType.contains("wordprocessingml") || name.endsWith(".docx")) {
                try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes));
                        XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
                    return extractor.getText();
                }
            }
            if (contentType.contains("msword") || name.endsWith(".doc")) {
                try (WordExtractor extractor = new WordExtractor(new ByteArrayInputStream(bytes))) {
                    return extractor.getText();
                }
            }
        } catch (Exception e) {
            logger.warn("提取文档文本失败: name={}, err={}", resolveDisplayName(attachment), e.getMessage());
        }
        return null;
    }

    private FetchedAttachment fetchBytes(String url) {
        if (StringUtils.isBlank(url)) {
            return null;
        }
        try {
            URLConnection connection = URI.create(url).toURL().openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(8000);
            long contentLength = connection.getContentLengthLong();
            try (InputStream inputStream = connection.getInputStream();
                    ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int total = 0;
                int read;
                boolean truncated = false;
                while ((read = inputStream.read(buffer)) != -1) {
                    total += read;
                    if (total > maxFetchBytes) {
                        truncated = true;
                        break;
                    }
                    outputStream.write(buffer, 0, read);
                }
                return new FetchedAttachment(outputStream.toByteArray(), truncated, contentLength);
            }
        } catch (Exception e) {
            logger.warn("下载附件失败: url={}, err={}", url, e.getMessage());
            return null;
        }
    }

    private String analyzeImageWithOcr(ChatModel ocrModel, ConversationAttachment attachment) {
        try {
            ImageContent imageContent = buildImageContent(attachment);
            if (imageContent == null) {
                return null;
            }
            ChatResponse response = ocrModel.chat(UserMessage.userMessage(
                    TextContent.from(IMAGE_ANALYSIS_PROMPT),
                    imageContent));
            return response != null && response.aiMessage() != null ? abbreviate(response.aiMessage().text(), MAX_SUMMARY_CHARS) : null;
        } catch (Exception e) {
            logger.warn("OCR解析图片失败: url={}, err={}", attachment != null ? attachment.getUrl() : null, e.getMessage());
            return null;
        }
    }

    private ChatModel resolveOcrModel(String userId, boolean logFailure) {
        ModelConfig ocrModelConfig = null;
        try {
            ocrModelConfig = userModelConfigResolver.getUserOcrModelConfig(userId);
        } catch (Exception e) {
            if (logFailure) {
                logger.warn("未获取到OCR模型，图片将退化为文本引用: userId={}, err={}", userId, e.getMessage());
            } else {
                logger.debug("未获取到OCR模型，跳过图片OCR预热: userId={}, err={}", userId, e.getMessage());
            }
        }
        if (ocrModelConfig == null) {
            return null;
        }
        try {
            return LLMProviderService.getStrand(ocrModelConfig.getProtocol(),
                    providerConfigFactory.fromModelConfig(ocrModelConfig, null));
        } catch (Exception e) {
            if (logFailure) {
                logger.warn("创建OCR模型失败，图片将退化为文本引用: userId={}, err={}", userId, e.getMessage());
            } else {
                logger.debug("创建OCR模型失败，跳过图片OCR预热: userId={}, err={}", userId, e.getMessage());
            }
            return null;
        }
    }

    private void ensureDocumentSummaryAsync(ConversationAttachment attachment) {
        if (attachment == null || StringUtils.isBlank(attachment.getUrl())) {
            return;
        }
        String cacheKey = attachment.getUrl();
        if (StringUtils.isNotBlank(documentSummaryCache.getIfPresent(cacheKey))) {
            return;
        }
        documentSummaryTasks.computeIfAbsent(cacheKey, ignored -> {
            CompletableFuture<String> future = CompletableFuture
                    .supplyAsync(() -> buildDocumentSummary(attachment), attachmentProcessingTaskExecutor);
            future.whenComplete((summary, throwable) -> {
                documentSummaryTasks.remove(cacheKey, future);
                if (throwable != null) {
                    logger.warn("后台生成文档摘要失败: url={}, err={}", cacheKey, throwable.getMessage());
                    return;
                }
                if (StringUtils.isNotBlank(summary)) {
                    documentSummaryCache.put(cacheKey, summary);
                }
            });
            return future;
        });
    }

    private void hydrateImageSummary(ConversationAttachment attachment, ChatModel ocrModel, long waitMs) {
        if (attachment == null || !attachment.isImage()) {
            return;
        }
        String summary = resolveImageSummaryFromCache(attachment);
        if (StringUtils.isNotBlank(summary)) {
            attachment.setSummary(summary);
            return;
        }
        if (ocrModel == null) {
            return;
        }
        CompletableFuture<String> future = ensureImageSummaryAsync(attachment, ocrModel);
        if (waitMs <= 0L) {
            return;
        }
        try {
            String resolved = future.get(waitMs, TimeUnit.MILLISECONDS);
            if (StringUtils.isNotBlank(resolved)) {
                attachment.setSummary(resolved);
            }
        } catch (TimeoutException e) {
            logger.debug("当前轮图片OCR预热等待超时: url={}, waitMs={}", attachment.getUrl(), waitMs);
        } catch (Exception e) {
            logger.debug("当前轮图片OCR预热等待失败: url={}, err={}", attachment.getUrl(), e.getMessage());
        }
    }

    private CompletableFuture<String> ensureImageSummaryAsync(ConversationAttachment attachment, ChatModel ocrModel) {
        if (attachment == null || StringUtils.isBlank(attachment.getUrl()) || ocrModel == null) {
            return CompletableFuture.completedFuture("");
        }
        String cacheKey = attachment.getUrl();
        String cached = imageSummaryCache.getIfPresent(cacheKey);
        if (StringUtils.isNotBlank(cached)) {
            return CompletableFuture.completedFuture(cached);
        }
        return imageSummaryTasks.computeIfAbsent(cacheKey, ignored -> {
            CompletableFuture<String> future = CompletableFuture
                    .supplyAsync(() -> analyzeImageWithOcr(ocrModel, attachment), attachmentProcessingTaskExecutor);
            future.whenComplete((summary, throwable) -> {
                imageSummaryTasks.remove(cacheKey, future);
                if (throwable != null) {
                    logger.warn("后台生成图片OCR摘要失败: url={}, err={}", cacheKey, throwable.getMessage());
                    return;
                }
                if (StringUtils.isNotBlank(summary)) {
                    imageSummaryCache.put(cacheKey, summary);
                }
            });
            return future;
        });
    }

    private String getCompletedTaskResult(CompletableFuture<String> future) {
        if (future == null || !future.isDone() || future.isCompletedExceptionally() || future.isCancelled()) {
            return null;
        }
        try {
            return future.getNow(null);
        } catch (Exception e) {
            return null;
        }
    }

    private String getImageFallbackSummaryWithWait(ConversationAttachment attachment, ChatModel ocrModel) {
        CompletableFuture<String> future = ensureImageSummaryAsync(attachment, ocrModel);
        String completed = getCompletedTaskResult(future);
        if (StringUtils.isNotBlank(completed) || future == null || imageFallbackOcrWaitMs <= 0L) {
            return completed;
        }
        try {
            String summary = future.get(imageFallbackOcrWaitMs, TimeUnit.MILLISECONDS);
            return StringUtils.isNotBlank(summary) ? summary : null;
        } catch (TimeoutException e) {
            logger.debug("图片降级等待OCR摘要超时: url={}, waitMs={}", attachment != null ? attachment.getUrl() : null,
                    imageFallbackOcrWaitMs);
            return null;
        } catch (Exception e) {
            logger.warn("图片降级等待OCR摘要失败: url={}, err={}", attachment != null ? attachment.getUrl() : null,
                    e.getMessage());
            return null;
        }
    }

    private String getCachedSummary(Cache<String, String> cache, String cacheKey) {
        if (cache == null || StringUtils.isBlank(cacheKey)) {
            return null;
        }
        return cache.getIfPresent(cacheKey);
    }

    private String resolveImageSummaryFromCache(ConversationAttachment attachment) {
        if (attachment == null || !attachment.isImage()) {
            return null;
        }
        String summary = StringUtils.defaultString(attachment.getSummary()).trim();
        if (StringUtils.isNotBlank(summary)) {
            return summary;
        }
        return getCachedSummary(imageSummaryCache, attachment.getUrl());
    }

    private void refreshPersistedMessageImageSummaries(String messageId) {
        if (StringUtils.isBlank(messageId)) {
            return;
        }
        MessageEntity persisted = messageDomainService.getById(messageId);
        if (persisted == null) {
            return;
        }
        List<ConversationAttachment> attachments = normalizeAttachments(persisted.getAttachments(), persisted.getFileUrls());
        if (attachments.isEmpty()) {
            return;
        }
        boolean changed = false;
        for (ConversationAttachment attachment : attachments) {
            if (attachment == null || !attachment.isImage()) {
                continue;
            }
            String summary = resolveImageSummaryFromCache(attachment);
            if (StringUtils.isBlank(summary) || StringUtils.equals(summary, attachment.getSummary())) {
                continue;
            }
            attachment.setSummary(summary);
            changed = true;
        }
        if (!changed) {
            return;
        }
        persisted.setAttachments(attachments);
        persisted.setFileUrls(attachments.stream().map(ConversationAttachment::getUrl)
                .filter(StringUtils::isNotBlank).collect(Collectors.toList()));
        messageDomainService.updateMessage(persisted);
    }

    private AttachmentKind resolveKind(String contentType, String name, String url) {
        String lowerContentType = StringUtils.defaultString(contentType).toLowerCase(Locale.ROOT);
        String lowerName = (StringUtils.defaultIfBlank(name, url)).toLowerCase(Locale.ROOT);
        if (lowerContentType.startsWith("image/") || endsWithAny(lowerName, ".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp", ".svg")) {
            return AttachmentKind.IMAGE;
        }
        if (lowerContentType.startsWith("text/") || endsWithAny(lowerName, ".txt", ".md", ".csv", ".html", ".xml", ".json")) {
            return AttachmentKind.TEXT;
        }
        if (lowerContentType.contains("pdf") || lowerContentType.contains("word")
                || endsWithAny(lowerName, ".pdf", ".doc", ".docx")) {
            return AttachmentKind.DOCUMENT;
        }
        return AttachmentKind.OTHER;
    }

    private String resolveContentType(String name, String url) {
        String lower = StringUtils.defaultIfBlank(name, url).toLowerCase(Locale.ROOT);
        if (endsWithAny(lower, ".png")) {
            return "image/png";
        }
        if (endsWithAny(lower, ".jpg", ".jpeg")) {
            return "image/jpeg";
        }
        if (endsWithAny(lower, ".gif")) {
            return "image/gif";
        }
        if (endsWithAny(lower, ".webp")) {
            return "image/webp";
        }
        if (endsWithAny(lower, ".svg")) {
            return "image/svg+xml";
        }
        if (endsWithAny(lower, ".txt")) {
            return "text/plain";
        }
        if (endsWithAny(lower, ".md")) {
            return "text/markdown";
        }
        if (endsWithAny(lower, ".pdf")) {
            return "application/pdf";
        }
        if (endsWithAny(lower, ".doc")) {
            return "application/msword";
        }
        if (endsWithAny(lower, ".docx")) {
            return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        }
        return "application/octet-stream";
    }

    private String resolveImageMimeType(ConversationAttachment attachment, byte[] bytes) {
        String contentType = StringUtils.defaultString(attachment != null ? attachment.getContentType() : null).trim();
        if (contentType.startsWith("image/")) {
            return contentType;
        }
        String detected = TikaFileTypeDetector.detectFileType(bytes);
        if (StringUtils.isNotBlank(detected) && detected.startsWith("image/")) {
            return detected;
        }
        return resolveContentType(attachment != null ? attachment.getName() : null,
                attachment != null ? attachment.getUrl() : null);
    }

    private String resolveName(String url) {
        if (StringUtils.isBlank(url)) {
            return "unknown";
        }
        try {
            String path = URI.create(url).getPath();
            if (StringUtils.isBlank(path)) {
                return "unknown";
            }
            int index = path.lastIndexOf('/');
            return index >= 0 ? path.substring(index + 1) : path;
        } catch (Exception e) {
            return "unknown";
        }
    }

    private String buildDocumentReferenceSummary(ConversationAttachment attachment) {
        return "用户上传了文档《" + resolveDisplayName(attachment) + "》";
    }

    private String buildLargeDocumentProcessingSummary(ConversationAttachment attachment) {
        return "用户上传了文档《" + resolveDisplayName(attachment) + "》，文档较大，系统正在后台解析摘要。";
    }

    private boolean shouldEagerlyExtractDocumentSummary(ConversationAttachment attachment) {
        if (attachment == null || !attachment.isDocumentLike()) {
            return false;
        }
        String contentType = StringUtils.defaultString(attachment.getContentType()).toLowerCase(Locale.ROOT);
        String name = StringUtils.defaultString(attachment.getName()).toLowerCase(Locale.ROOT);
        return contentType.startsWith("text/")
                || endsWithAny(name, ".txt", ".md", ".csv", ".json", ".xml", ".html", ".htm");
    }

    private boolean shouldSampleLargeDocumentSummary(FetchedAttachment fetchedAttachment) {
        return fetchedAttachment != null && fetchedAttachment.bytes().length > syncFullSummaryMaxBytes;
    }

    private boolean shouldDeferLargeDocumentSummary(FetchedAttachment fetchedAttachment) {
        if (fetchedAttachment == null) {
            return false;
        }
        if (fetchedAttachment.truncated()) {
            return true;
        }
        return fetchedAttachment.contentLength() > syncSampledSummaryMaxBytes;
    }

    private String buildDocumentExcerpt(String text, boolean sampled) {
        if (StringUtils.isBlank(text)) {
            return "";
        }
        List<String> paragraphs = splitDocumentParagraphs(text);
        if (paragraphs.isEmpty()) {
            return "";
        }
        if (!sampled && estimateDocumentTokens(String.join("\n\n", paragraphs)) <= documentSummaryBudgetTokens) {
            return String.join("\n\n", paragraphs);
        }
        List<String> candidates = sampled ? selectSampledParagraphs(paragraphs) : paragraphs;
        String excerpt = joinParagraphsWithinTokenBudget(candidates, documentSummaryBudgetTokens);
        if (StringUtils.isNotBlank(excerpt)) {
            return excerpt;
        }
        return clipParagraphToTokenBudget(paragraphs.get(0), documentSummaryBudgetTokens);
    }

    private List<String> splitDocumentParagraphs(String text) {
        String normalized = StringUtils.defaultString(text).replace("\r\n", "\n").replace('\r', '\n').trim();
        if (StringUtils.isBlank(normalized)) {
            return Collections.emptyList();
        }
        List<String> paragraphs = new ArrayList<>();
        for (String block : normalized.split("\\n\\s*\\n+")) {
            String paragraph = normalizeParagraph(block);
            if (StringUtils.isNotBlank(paragraph)) {
                paragraphs.add(paragraph);
            }
        }
        if (paragraphs.size() > 1) {
            return paragraphs;
        }
        paragraphs.clear();
        for (String line : normalized.split("\\n+")) {
            String paragraph = normalizeParagraph(line);
            if (StringUtils.isNotBlank(paragraph)) {
                paragraphs.add(paragraph);
            }
        }
        if (!paragraphs.isEmpty()) {
            return paragraphs;
        }
        return List.of(normalizeParagraph(normalized));
    }

    private String normalizeParagraph(String value) {
        return StringUtils.defaultString(value).replaceAll("\\s+", " ").trim();
    }

    private List<String> selectSampledParagraphs(List<String> paragraphs) {
        if (paragraphs.size() <= 6) {
            return paragraphs;
        }
        List<String> selected = new ArrayList<>();
        addParagraphRange(selected, paragraphs, 0, Math.min(3, paragraphs.size()));
        int middleStart = Math.max(3, paragraphs.size() / 2 - 1);
        addParagraphRange(selected, paragraphs, middleStart, Math.min(paragraphs.size(), middleStart + 2));
        int tailStart = Math.max(0, paragraphs.size() - 3);
        addParagraphRange(selected, paragraphs, tailStart, paragraphs.size());
        return selected;
    }

    private void addParagraphRange(List<String> selected, List<String> paragraphs, int startInclusive, int endExclusive) {
        for (int index = startInclusive; index < endExclusive; index++) {
            String paragraph = paragraphs.get(index);
            if (!selected.contains(paragraph)) {
                selected.add(paragraph);
            }
        }
    }

    private String joinParagraphsWithinTokenBudget(List<String> paragraphs, int budgetTokens) {
        StringBuilder builder = new StringBuilder();
        int usedTokens = 0;
        for (String paragraph : paragraphs) {
            int paragraphTokens = estimateDocumentTokens(paragraph);
            String selectedParagraph = paragraph;
            if (paragraphTokens > budgetTokens) {
                selectedParagraph = clipParagraphToTokenBudget(paragraph, Math.max(1, budgetTokens - usedTokens));
                paragraphTokens = estimateDocumentTokens(selectedParagraph);
            }
            if (StringUtils.isBlank(selectedParagraph)) {
                continue;
            }
            if (usedTokens > 0 && usedTokens + paragraphTokens > budgetTokens) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append("\n\n");
            }
            builder.append(selectedParagraph);
            usedTokens += paragraphTokens;
            if (usedTokens >= budgetTokens) {
                break;
            }
        }
        return builder.toString();
    }

    private String clipParagraphToTokenBudget(String paragraph, int budgetTokens) {
        String normalized = normalizeParagraph(paragraph);
        if (StringUtils.isBlank(normalized) || budgetTokens <= 0) {
            return "";
        }
        int tokens = estimateDocumentTokens(normalized);
        if (tokens <= budgetTokens) {
            return normalized;
        }
        int targetLength = Math.max(1, (int) Math.floor(normalized.length() * (budgetTokens / (double) tokens) * 0.96D));
        return abbreviate(normalized, targetLength);
    }

    private int estimateDocumentTokens(String text) {
        return tokenEstimatorService.estimateTextTokenCountHeuristically(text);
    }

    private String buildImageReferenceSummary(ConversationAttachment attachment) {
        return "用户上传了图片《" + resolveDisplayName(attachment) + "》";
    }

    private String buildImagePromptReference(ConversationAttachment attachment) {
        return "用户上传了图片《" + resolveDisplayName(attachment) + "》，当前回退为文本引用，地址：" + attachment.getUrl();
    }

    private String resolveDisplayName(ConversationAttachment attachment) {
        return StringUtils.defaultIfBlank(attachment.getName(), "未命名附件");
    }

    private boolean endsWithAny(String value, String... suffixes) {
        if (StringUtils.isBlank(value)) {
            return false;
        }
        for (String suffix : suffixes) {
            if (value.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    private String abbreviate(String value, int maxLength) {
        if (StringUtils.isBlank(value)) {
            return value;
        }
        String normalized = value.trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    private record FetchedAttachment(byte[] bytes, boolean truncated, long contentLength) {
        private boolean isEmpty() {
            return bytes == null || bytes.length == 0;
        }
    }
}
