package com.example.agentx.application.memory.assembler;

import com.example.agentx.application.memory.dto.MemoryItemDTO;
import com.example.agentx.domain.memory.model.MemoryItemEntity;

import java.util.List;
import java.util.stream.Collectors;

public class MemoryAssembler {

    public static MemoryItemDTO toDTO(MemoryItemEntity e) {
        MemoryItemDTO dto = new MemoryItemDTO();
        dto.setId(e.getId());
        dto.setType(e.getType());
        dto.setText(e.getText());
        dto.setImportance(e.getImportance());
        dto.setTags(e.getTags());
        dto.setSourceSessionId(e.getSourceSessionId());
        dto.setLastHitAt(e.getLastHitAt());
        dto.setHitCount(e.getHitCount());
        dto.setCreatedAt(e.getCreatedAt());
        dto.setUpdatedAt(e.getUpdatedAt());
        return dto;
    }

    public static List<MemoryItemDTO> toDTOs(List<MemoryItemEntity> list) {
        return list.stream().map(MemoryAssembler::toDTO).collect(Collectors.toList());
    }
}
