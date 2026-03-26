package com.example.agentx.application.account.assembler;

import com.example.agentx.application.account.dto.AccountDTO;
import com.example.agentx.domain.user.model.AccountEntity;
import org.springframework.beans.BeanUtils;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 账户装配器 处理Entity、DTO之间的转换
 */
public class AccountAssembler {

    /**
     * 将Entity转换为DTO
     *
     * @param entity 账户实体
     * @return 账户DTO
     */
    public static AccountDTO toDTO(AccountEntity entity) {
        if (entity == null) {
            return null;
        }

        AccountDTO dto = new AccountDTO();
        BeanUtils.copyProperties(entity, dto);
        return dto;
    }

    /**
     * 将Entity列表转换为DTO列表
     *
     * @param entities 账户实体列表
     * @return 账户DTO列表
     */
    public static List<AccountDTO> toDTOs(List<AccountEntity> entities) {
        if (entities == null || entities.isEmpty()) {
            return Collections.emptyList();
        }
        return entities.stream().map(AccountAssembler::toDTO).collect(Collectors.toList());
    }
}