package com.example.agentx.application.rag.service.manager;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import com.example.agentx.domain.llm.model.enums.ModelType;
import com.example.agentx.domain.rag.model.DocumentUnitEntity;
import com.example.agentx.domain.rag.model.FileDetailEntity;
import com.example.agentx.domain.rag.model.RagQaDatasetEntity;
import com.example.agentx.domain.rag.message.RagDocSyncStorageMessage;
import com.example.agentx.domain.rag.service.DocumentUnitDomainService;
import com.example.agentx.domain.rag.service.EmbeddingDomainService;
import com.example.agentx.domain.rag.service.RagQaDatasetDomainService;
import com.example.agentx.domain.rag.service.FileDetailDomainService;
import com.example.agentx.infrastructure.rag.service.UserModelConfigResolver;

/** 数据集嵌入模型迁移（异步重建） */
@Service
public class DatasetEmbeddingMigrationAppService {

    private static final Logger log = LoggerFactory.getLogger(DatasetEmbeddingMigrationAppService.class);

    private final RagQaDatasetDomainService ragQaDatasetDomainService;
    private final FileDetailDomainService fileDetailDomainService;
    private final DocumentUnitDomainService documentUnitDomainService;
    private final EmbeddingDomainService embeddingDomainService;
    private final UserModelConfigResolver userModelConfigResolver;

    public DatasetEmbeddingMigrationAppService(RagQaDatasetDomainService ragQaDatasetDomainService,
            FileDetailDomainService fileDetailDomainService, DocumentUnitDomainService documentUnitDomainService,
            EmbeddingDomainService embeddingDomainService, UserModelConfigResolver userModelConfigResolver) {
        this.ragQaDatasetDomainService = ragQaDatasetDomainService;
        this.fileDetailDomainService = fileDetailDomainService;
        this.documentUnitDomainService = documentUnitDomainService;
        this.embeddingDomainService = embeddingDomainService;
        this.userModelConfigResolver = userModelConfigResolver;
    }

    @Async("embeddingMigrationTaskExecutor")
    public void migrateDatasetEmbeddingAsync(String datasetId, String userId, String targetModelId,
            String targetProfileId) {
        if (!StringUtils.hasText(targetModelId) || !StringUtils.hasText(targetProfileId)) {
            String message = String.format("数据集迁移缺少目标模型/Profile: modelId=%s, profileId=%s", targetModelId,
                    targetProfileId);
            ragQaDatasetDomainService.markEmbeddingMigrationFailed(datasetId, userId, message);
            log.error("数据集嵌入迁移失败(参数缺失): datasetId={}, userId={}, modelId={}, profileId={}", datasetId, userId,
                    targetModelId, targetProfileId);
            return;
        }

        long begin = System.currentTimeMillis();
        log.info("开始数据集嵌入迁移任务: datasetId={}, userId={}, targetModelId={}, targetProfileId={}", datasetId, userId,
                targetModelId, targetProfileId);
        try {
            RagQaDatasetEntity dataset = ragQaDatasetDomainService.getDataset(datasetId, userId);
            log.debug("迁移任务读取数据集成功: datasetId={}, status={}, currentModelId={}, pendingModelId={}, pendingProfileId={}",
                    datasetId, dataset.getEmbeddingMigrationStatus(), dataset.getEmbeddingModelId(),
                    dataset.getPendingEmbeddingModelId(), dataset.getPendingEmbeddingProfileId());

            var embeddingModelConfig = userModelConfigResolver.getModelConfigByModelId(userId, targetModelId,
                    ModelType.EMBEDDING);

            List<FileDetailEntity> files = fileDetailDomainService.listAllFilesByDataset(datasetId, userId);
            long totalUnits = 0;
            log.info("迁移任务扫描文件完成: datasetId={}, fileCount={}", datasetId, files.size());

            for (FileDetailEntity file : files) {
                List<DocumentUnitEntity> units = documentUnitDomainService.listDocumentsByFileAndStatus(file.getId(), true,
                        null);
                if (!units.isEmpty()) {
                    log.debug("迁移任务处理文件: datasetId={}, fileId={}, fileName={}, unitCount={}", datasetId, file.getId(),
                            file.getOriginalFilename(), units.size());
                }
                for (DocumentUnitEntity unit : units) {
                    if (!StringUtils.hasText(unit.getContent())) {
                        continue;
                    }
                    RagDocSyncStorageMessage msg = new RagDocSyncStorageMessage();
                    msg.setId(unit.getId());
                    msg.setFileId(file.getId());
                    msg.setFileName(file.getOriginalFilename());
                    msg.setPage(unit.getPage());
                    msg.setContent(unit.getContent());
                    msg.setDatasetId(datasetId);
                    msg.setUserId(userId);
                    msg.setEmbeddingModelConfig(embeddingModelConfig);
                    msg.setEmbeddingProfileId(targetProfileId);
                    embeddingDomainService.syncStorage(msg);
                    totalUnits++;
                }
            }

            ragQaDatasetDomainService.completeEmbeddingMigration(datasetId, userId, targetModelId, targetProfileId);
            log.info("数据集嵌入迁移成功: datasetId={}, userId={}, targetModelId={}, unitCount={}, cost={}ms", datasetId,
                    userId, targetModelId, totalUnits, System.currentTimeMillis() - begin);
        } catch (Exception e) {
            ragQaDatasetDomainService.markEmbeddingMigrationFailed(datasetId, userId, e.getMessage());
            log.error("数据集嵌入迁移失败: datasetId={}, userId={}, error={}", datasetId, userId, e.getMessage(), e);
        }
    }
}
