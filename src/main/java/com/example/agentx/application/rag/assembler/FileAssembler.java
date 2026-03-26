package com.example.agentx.application.rag.assembler;

import com.example.agentx.domain.rag.model.FileDetailEntity;

public class FileAssembler {

    /**
     * 将CreateAgentRequest转换为AgentEntity
     */
    public static FileDetailEntity toEntity(RagUploadRequest request) {

        FileDetailEntity fileDetailEntity = new FileDetailEntity();
        fileDetailEntity.setMultipartFile(request.getFile());
        fileDetailEntity.setDataSetId(request.getDataSetId());

        return fileDetailEntity;
    }
}
