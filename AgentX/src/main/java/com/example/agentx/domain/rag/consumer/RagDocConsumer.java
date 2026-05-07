package com.example.agentx.domain.rag.consumer;

import static com.example.agentx.infrastructure.mq.core.MessageHeaders.TRACE_ID;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import com.example.agentx.infrastructure.mq.core.MessageEnvelope;
import com.example.agentx.infrastructure.mq.core.MessagePublisher;
import org.springframework.stereotype.Component;
import com.example.agentx.domain.rag.config.RagProperties;
import com.example.agentx.domain.rag.message.RagDocMessage;
import com.example.agentx.domain.rag.message.RagDocSyncStorageMessage;
import com.example.agentx.domain.rag.model.DocumentUnitEntity;
import com.example.agentx.domain.rag.model.FileDetailEntity;
import com.example.agentx.domain.rag.repository.DocumentUnitRepository;
import com.example.agentx.domain.rag.service.FileDetailDomainService;
import com.example.agentx.domain.rag.strategy.DocumentProcessingStrategy;
import com.example.agentx.domain.rag.strategy.context.DocumentProcessingFactory;
import com.example.agentx.infrastructure.exception.BusinessException;
import com.example.agentx.infrastructure.mq.enums.EventType;
import com.example.agentx.infrastructure.mq.events.RagDocSyncOcrEvent;
import com.example.agentx.infrastructure.mq.events.RagDocSyncStorageEvent;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.rabbitmq.client.Channel;
import com.example.agentx.infrastructure.rag.service.DatasetEmbeddingConfigResolver;

/** document预处理消费者
 * @author zang
 * @date 2025-01-10 */
@RabbitListener(bindings = @QueueBinding(value = @Queue(RagDocSyncOcrEvent.QUEUE_NAME), exchange = @Exchange(value = RagDocSyncOcrEvent.EXCHANGE_NAME, type = ExchangeTypes.TOPIC), key = RagDocSyncOcrEvent.ROUTE_KEY))
@Component
public class RagDocConsumer {

    private static final Logger log = LoggerFactory.getLogger(RagDocConsumer.class);
    private final DocumentProcessingFactory documentProcessingFactory;
    private final FileDetailDomainService fileDetailDomainService;
    private final DocumentUnitRepository documentUnitRepository;
    private final MessagePublisher messagePublisher;
    private final DatasetEmbeddingConfigResolver datasetEmbeddingConfigResolver;
    private final ObjectMapper objectMapper;
    private final RagProperties ragProperties;

    public RagDocConsumer(DocumentProcessingFactory ragDocSyncOcrContext,
            FileDetailDomainService fileDetailDomainService, DocumentUnitRepository documentUnitRepository,
            MessagePublisher messagePublisher, DatasetEmbeddingConfigResolver datasetEmbeddingConfigResolver,
            ObjectMapper objectMapper, RagProperties ragProperties) {
        this.documentProcessingFactory = ragDocSyncOcrContext;
        this.fileDetailDomainService = fileDetailDomainService;
        this.documentUnitRepository = documentUnitRepository;
        this.messagePublisher = messagePublisher;
        this.datasetEmbeddingConfigResolver = datasetEmbeddingConfigResolver;
        this.ragProperties = ragProperties;
        this.objectMapper = objectMapper.copy().registerModule(new JavaTimeModule())
                .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @RabbitHandler
    public void receiveMessage(java.util.Map<String, Object> payload, Message message, Channel channel)
            throws IOException {
        MessageProperties messageProperties = message.getMessageProperties();
        long deliveryTag = messageProperties.getDeliveryTag();

        RagDocMessage docMessage = null;
        try {
            // 将已由 Jackson 转换的 Map 转为强类型 Envelope
            MessageEnvelope<RagDocMessage> envelope = objectMapper.convertValue(payload, new TypeReference<>() {
            });

            MDC.put(TRACE_ID, Objects.nonNull(envelope.getTraceId()) ? envelope.getTraceId() : IdWorker.getTimeId());
            docMessage = envelope.getData();

            log.info("开始OCR处理文件: {}", docMessage.getFileId());

            // 获取文件并开始OCR处理
            FileDetailEntity fileEntity = fileDetailDomainService.getFileByIdWithoutUserCheck(docMessage.getFileId());
            boolean startSuccess = fileDetailDomainService.startFileOcrProcessing(docMessage.getFileId(),
                    fileEntity.getUserId());

            if (!startSuccess) {
                throw new BusinessException("无法开始OCR处理，文件状态不允许");
            }

            // 获取文件扩展名并选择处理策略
            String fileExt = fileDetailDomainService.getFileExtension(docMessage.getFileId());
            if (fileExt == null) {
                throw new BusinessException("文件扩展名不能为空");
            }

            DocumentProcessingStrategy strategy = documentProcessingFactory
                    .getDocumentStrategyHandler(fileExt.toUpperCase());
            if (strategy == null) {
                throw new BusinessException("不支持的文件类型: " + fileExt);
            }

            // 执行OCR处理
            strategy.handle(docMessage, fileExt.toUpperCase());

            // 完成OCR处理
            fileEntity = fileDetailDomainService.getFileByIdWithoutUserCheck(docMessage.getFileId());
            Integer totalPages = fileEntity.getFilePageSize();
            if (totalPages != null && totalPages > 0) {
                fileDetailDomainService.updateFileOcrProgress(docMessage.getFileId(), totalPages, totalPages,
                        fileEntity.getUserId());
            }

            boolean completeSuccess = fileDetailDomainService.completeFileOcrProcessing(docMessage.getFileId(),
                    fileEntity.getUserId());
            if (!completeSuccess) {
                log.warn("OCR处理完成但状态转换失败，文件ID: {}", docMessage.getFileId());
            }

            log.info("OCR处理完成，文件ID: {}", docMessage.getFileId());

            // 自动启动向量化处理
            autoStartVectorization(docMessage.getFileId(), fileEntity);

        } catch (Exception e) {
            log.error("OCR处理失败，文件ID: {}", docMessage != null ? docMessage.getFileId() : "unknown", e);
            // 处理失败
            try {
                if (docMessage != null) {
                    FileDetailEntity fileEntity = fileDetailDomainService
                            .getFileByIdWithoutUserCheck(docMessage.getFileId());
                    fileDetailDomainService.failFileOcrProcessing(docMessage.getFileId(), fileEntity.getUserId());
                }
            } catch (Exception ex) {
                log.error("更新文件状态为失败失败，文件ID: {}", docMessage != null ? docMessage.getFileId() : "unknown", ex);
            }
            if (docMessage != null) {
                scheduleRetryOrDeadLetter(docMessage, e);
            }
        } finally {
            channel.basicAck(deliveryTag, false);
        }
    }

    /** 自动启动向量化处理
     * @param fileId 文件ID
     * @param fileEntity 文件实体 */
    private void autoStartVectorization(String fileId, FileDetailEntity fileEntity) {
        try {
            log.info("自动启动向量化处理，文件ID: {}", fileId);

            // 检查是否有可用的文档单元进行向量化
            List<DocumentUnitEntity> documentUnits = documentUnitRepository
                    .selectList(Wrappers.lambdaQuery(DocumentUnitEntity.class).eq(DocumentUnitEntity::getFileId, fileId)
                            .eq(DocumentUnitEntity::getIsOcr, true).eq(DocumentUnitEntity::getIsVector, false));

            if (documentUnits.isEmpty()) {
                log.warn("未找到用于向量化的文档单元，文件ID: {}", fileId);
                return;
            }

            // 更新向量化状态
            boolean startSuccess = fileDetailDomainService.startFileEmbeddingProcessing(fileId, fileEntity.getUserId());
            if (!startSuccess) {
                log.warn("无法开始向量化处理，文件状态不允许，文件ID: {}", fileId);
                return;
            }
            var embeddingContext = datasetEmbeddingConfigResolver.resolveActive(fileEntity.getDataSetId(),
                    fileEntity.getUserId());

            // 为每个DocumentUnit发送向量化MQ消息
            for (DocumentUnitEntity documentUnit : documentUnits) {
                RagDocSyncStorageMessage storageMessage = new RagDocSyncStorageMessage();
                storageMessage.setId(documentUnit.getId());
                storageMessage.setFileId(fileId);
                storageMessage.setFileName(fileEntity.getOriginalFilename());
                storageMessage.setPage(documentUnit.getPage());
                storageMessage.setContent(documentUnit.getContent());
                storageMessage.setVector(true);
                storageMessage.setDatasetId(fileEntity.getDataSetId());
                storageMessage.setUserId(fileEntity.getUserId());
                storageMessage.setEmbeddingModelConfig(embeddingContext.modelConfig());
                storageMessage.setEmbeddingProfileId(embeddingContext.profileId());

                MessageEnvelope<RagDocSyncStorageMessage> env = MessageEnvelope.builder(storageMessage)
                        .addEventType(EventType.DOC_SYNC_RAG).description("文件自动向量化处理任务 - 页面 " + documentUnit.getPage())
                        .build();
                messagePublisher.publish(RagDocSyncStorageEvent.route(), env);
            }

            log.info("自动向量化启动完成，文件ID: {}，{}个文档单元", fileId, documentUnits.size());

        } catch (Exception e) {
            log.error("自动启动向量化失败，文件ID: {}", fileId, e);
            // 如果自动启动失败，重置向量化状态
            try {
                fileDetailDomainService.failFileEmbeddingProcessing(fileId, fileEntity.getUserId());
            } catch (Exception ex) {
                log.error("更新文件嵌入状态为失败失败，文件ID: {}", fileId, ex);
            }
        }
    }

    private void scheduleRetryOrDeadLetter(RagDocMessage docMessage, Exception exception) {
        int currentRetry = docMessage.getRetryCount() == null ? 0 : Math.max(0, docMessage.getRetryCount());
        docMessage.setLastError(truncateError(exception));
        if (currentRetry >= Math.max(0, ragProperties.getDoc().getOcr().getRetry().getMaxAttempts())) {
            log.warn("OCR消息达到最大重试次数，转入DLQ: fileId={}, retryCount={}", docMessage.getFileId(), currentRetry);
            publishAsync(RagDocSyncOcrEvent.deadLetterRoute(), buildEnvelope(docMessage, "OCR任务进入死信队列"));
            return;
        }
        docMessage.setRetryCount(currentRetry + 1);
        long delay = Math.max(0L, ragProperties.getDoc().getOcr().getRetry().getDelayMs())
                * Math.max(1, docMessage.getRetryCount());
        log.warn("OCR消息准备重试: fileId={}, retryCount={}, delayMs={}", docMessage.getFileId(),
                docMessage.getRetryCount(), delay);
        CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS)
                .execute(() -> publishAsync(RagDocSyncOcrEvent.route(), buildEnvelope(docMessage,
                        "OCR任务自动重试 #" + docMessage.getRetryCount())));
    }

    private MessageEnvelope<RagDocMessage> buildEnvelope(RagDocMessage docMessage, String description) {
        return MessageEnvelope.builder(docMessage).addEventType(EventType.DOC_SYNC_RAG).description(description).build();
    }

    private void publishAsync(com.example.agentx.infrastructure.mq.core.MessageRoute route, MessageEnvelope<RagDocMessage> envelope) {
        try {
            messagePublisher.publish(route, envelope);
        } catch (Exception publishException) {
            log.error("发布OCR重试/死信消息失败，fileId={}", envelope.getData() != null ? envelope.getData().getFileId() : "unknown",
                    publishException);
        }
    }

    private String truncateError(Exception exception) {
        String message = exception == null ? "unknown" : exception.getMessage();
        if (message == null) {
            return "unknown";
        }
        return message.length() > 300 ? message.substring(0, 300) : message;
    }
}
