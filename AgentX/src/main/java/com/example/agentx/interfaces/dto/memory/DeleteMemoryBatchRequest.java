package com.example.agentx.interfaces.dto.memory;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/** 批量删除记忆请求 */
public class DeleteMemoryBatchRequest {

    @NotEmpty(message = "itemIds 不能为空")
    private List<String> itemIds;

    public List<String> getItemIds() {
        return itemIds;
    }

    public void setItemIds(List<String> itemIds) {
        this.itemIds = itemIds;
    }
}
