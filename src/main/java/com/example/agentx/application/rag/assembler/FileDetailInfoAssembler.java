package com.example.agentx.application.rag.assembler;

import com.example.agentx.application.rag.dto.FileDetailInfoDTO;
import com.example.agentx.domain.rag.model.FileDetailEntity;
import org.springframework.beans.BeanUtils;

/**
 * 文件详细信息转换器
 */
public class FileDetailInfoAssembler {

    /**
     * Convert FileDetailEntity to FileDetailInfoDTO
     */
    public static FileDetailInfoDTO toDTO(FileDetailEntity entity) {
        if (entity == null) {
            return null;
        }
        FileDetailInfoDTO dto = new FileDetailInfoDTO();
        BeanUtils.copyProperties(entity, dto);
        dto.setFileId(entity.getId());
        return dto;
    }
}