package com.example.agentx.domain.rag.consumer;

import static com.example.agentx.infrastructure.mq.core.MessageHeaders.TRACE_ID;

import java.io.IOException;
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
import org.springframework.stereotype.Component;
import com.example.agentx.domain.rag.config.RagProperties;
import com.example.agentx.domain.rag.message.RagDocSyncStorageMessage;
import com.example.agentx.domain.rag.model.DocumentUnitEntity;
import com.example.agentx.domain.rag.repository.DocumentUnitRepository;
import com.example.agentx.domain.rag.service.EmbeddingDomainService;
import com.example.agentx.domain.rag.service.FileDetailDomainService;
import com.example.agentx.infrastructure.mq.core.MessageEnvelope;
import com.example.agentx.infrastructure.mq.core.MessagePublisher;
import com.example.agentx.infrastructure.mq.enums.EventType;
import com.example.agentx.infrastructure.mq.events.RagDocSyncStorageEvent;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.rabbitmq.client.Channel;

/** @author shilong.zang
 * @date 20:51 <br/>
 */
@RabbitListener(bindings = @QueueBinding(value = @Queue(RagDocSyncStorageEvent.QUEUE_NAME), exchange = @Exchange(value = RagDocSyncStorageEvent.EXCHANGE_NAME, type = ExchangeTypes.TOPIC), key = RagDocSyncStorageEvent.ROUTE_KEY))
@Component
public class RagDocStorageConsumer {

    private static final Logger log = LoggerFactory.getLogger(RagDocStorageConsumer.class);
    private final EmbeddingDomainService embeddingService;
    private final FileDetailDomainService fileDetailDomainService;
    private final DocumentUnitRepository documentUnitRepository;
    private final MessagePublisher messagePublisher;
    private final ObjectMapper objectMapper;
    private final RagProperties ragProperties;

    public RagDocStorageConsumer(EmbeddingDomainService embeddingService,
            FileDetailDomainService fileDetailDomainService, DocumentUnitRepository documentUnitRepository,
            MessagePublisher messagePublisher, ObjectMapper objectMapper, RagProperties ragProperties) {
        this.embeddingService = embeddingService;
        this.fileDetailDomainService = fileDetailDomainService;
        this.documentUnitRepository = documentUnitRepository;
        this.messagePublisher = messagePublisher;
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

        RagDocSyncStorageMessage mqRecordReqDTO = null;
        try {
            // 将已由 Jackson 转换的 Map 转为强类型 Envelope
            MessageEnvelope<RagDocSyncStorageMessage> envelope = objectMapper.convertValue(payload,
                    new TypeReference<MessageEnvelope<RagDocSyncStorageMessage>>() {
                    });

            MDC.put(TRACE_ID, Objects.nonNull(envelope.getTraceId()) ? envelope.getTraceId() : IdWorker.getTimeId());
            mqRecordReqDTO = envelope.getData();

            log.info("当前文件 {} 页面 {} ———— 开始向量化", mqRecordReqDTO.getFileName(), mqRecordReqDTO.getPage());

            // 执行向量化处理
            embeddingService.syncStorage(mqRecordReqDTO);

            // 更新向量化进度（假设每个页面向量化完成后更新）
            updateEmbeddingProgress(mqRecordReqDTO);

            log.info("当前文件 {} 第{}页 ———— 向量化完成", mqRecordReqDTO.getFileName(), mqRecordReqDTO.getPage());
        } catch (Exception e) {
            log.error("向量化过程中发生异常", e);
            if (mqRecordReqDTO != null) {
                try {
                    var fileEntity = fileDetailDomainService.getFileByIdWithoutUserCheck(mqRecordReqDTO.getFileId());
                    fileDetailDomainService.failFileEmbeddingProcessing(mqRecordReqDTO.getFileId(), fileEntity.getUserId());
                } catch (Exception statusException) {
                    log.warn("更新向量化失败状态失败，fileId={}", mqRecordReqDTO.getFileId(), statusException);
                }
                scheduleRetryOrDeadLetter(mqRecordReqDTO, e);
            }
        } finally {
            channel.basicAck(deliveryTag, false);
        }
    }

    /** 更新向量化进度
     * @param message 向量化消息 */
    private void updateEmbeddingProgress(RagDocSyncStorageMessage message) {
        try {
            String fileId = message.getFileId();
            Integer pageIndex = message.getPage(); // 这是从0开始的页面索引

            // 获取文件总页数来计算进度
            var fileEntity = fileDetailDomainService.getFileByIdWithoutUserCheck(fileId);
            Integer totalPages = fileEntity.getFilePageSize();

            if (totalPages != null && totalPages > 0) {
                // 查询已完成向量化的页面数量
                long completedVectorPages = documentUnitRepository
                        .selectCount(Wrappers.<DocumentUnitEntity>lambdaQuery()
                                .eq(DocumentUnitEntity::getFileId, fileId).eq(DocumentUnitEntity::getIsVector, true));

                // 当前页面完成后的总完成页数
                int currentCompletedPages = (int) (completedVectorPages + 1);

                // 计算百分比：已完成的页数 / 总页数 * 100
                double progress = ((double) currentCompletedPages / totalPages) * 100.0;

                fileDetailDomainService.updateFileEmbeddingProgress(fileId, currentCompletedPages, progress);
                log.debug("更新文件{}的嵌入进度: {}/{} ({}%)", fileId, currentCompletedPages, totalPages,
                        String.format("%.1f", progress));

                // 检查是否所有页面都已完成向量化
                if (currentCompletedPages >= totalPages) {
                    // 确保进度为100%
                    fileDetailDomainService.updateFileEmbeddingProgress(fileId, totalPages, 100.0);
                    // 通过状态机完成向量化处理
                    fileDetailDomainService.completeFileEmbeddingProcessing(fileId, fileEntity.getUserId());
                    log.info("文件{}的所有页面均已向量化，标记为完成", fileId);
                }
            }
        } catch (Exception e) {
            log.warn("更新文件{}的嵌入进度失败: {}", message.getFileId(), e.getMessage());
        }
    }

    private void scheduleRetryOrDeadLetter(RagDocSyncStorageMessage message, Exception exception) {
        int currentRetry = message.getRetryCount() == null ? 0 : Math.max(0, message.getRetryCount());
        message.setLastError(truncateError(exception));
        if (currentRetry >= Math.max(0, ragProperties.getDoc().getStorage().getRetry().getMaxAttempts())) {
            log.warn("向量化消息达到最大重试次数，转入DLQ: fileId={}, docId={}, retryCount={}", message.getFileId(),
                    message.getId(), currentRetry);
            publishAsync(RagDocSyncStorageEvent.deadLetterRoute(), buildEnvelope(message, "向量化任务进入死信队列"));
            return;
        }
        message.setRetryCount(currentRetry + 1);
        long delay = Math.max(0L, ragProperties.getDoc().getStorage().getRetry().getDelayMs())
                * Math.max(1, message.getRetryCount());
        log.warn("向量化消息准备重试: fileId={}, docId={}, retryCount={}, delayMs={}", message.getFileId(),
                message.getId(), message.getRetryCount(), delay);
        CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS)
                .execute(() -> publishAsync(RagDocSyncStorageEvent.route(), buildEnvelope(message,
                        "向量化任务自动重试 #" + message.getRetryCount())));
    }

    private MessageEnvelope<RagDocSyncStorageMessage> buildEnvelope(RagDocSyncStorageMessage message, String description) {
        return MessageEnvelope.builder(message).addEventType(EventType.DOC_SYNC_RAG).description(description).build();
    }

    private void publishAsync(com.example.agentx.infrastructure.mq.core.MessageRoute route,
            MessageEnvelope<RagDocSyncStorageMessage> envelope) {
        try {
            messagePublisher.publish(route, envelope);
        } catch (Exception publishException) {
            log.error("发布向量化重试/死信消息失败，fileId={}, docId={}",
                    envelope.getData() != null ? envelope.getData().getFileId() : "unknown",
                    envelope.getData() != null ? envelope.getData().getId() : "unknown", publishException);
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
