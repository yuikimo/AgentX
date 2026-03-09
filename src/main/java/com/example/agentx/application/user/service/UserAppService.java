package com.example.agentx.application.user.service;

import com.example.agentx.application.user.assembler.UserAssembler;
import com.example.agentx.application.user.dto.UserDTO;
import com.example.agentx.domain.user.model.UserEntity;
import com.example.agentx.domain.user.service.UserDomainService;
import com.example.agentx.interfaces.dto.user.request.UserUpdateRequest;
import org.springframework.stereotype.Service;

@Service
public class UserAppService {

    private final UserDomainService userDomainService;

    public UserAppService(UserDomainService userDomainService) {
        this.userDomainService = userDomainService;
    }

    /**
     * 获取用户信息
     */
    public UserDTO getUserInfo(String id) {
        UserEntity userEntity = userDomainService.getUserInfo(id);
        return UserAssembler.toDTO(userEntity);
    }

    /**
     * 修改用户信息
     */
    public void updateUserInfo(UserUpdateRequest userUpdateRequest, String userId) {
        UserEntity user = UserAssembler.toEntity(userUpdateRequest, userId);
        userDomainService.updateUserInfo(user);
    }
}

