package com.example.agentx.interfaces.api.portal.user;

import com.example.agentx.application.user.dto.UserDTO;
import com.example.agentx.application.user.service.UserAppService;
import com.example.agentx.infrastructure.auth.UserContext;
import com.example.agentx.interfaces.api.common.Result;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/** 用户 */
@RestController
@RequestMapping("/users")
public class PortalUserController {

    private final UserAppService userAppService;

    public PortalUserController(UserAppService userAppService) {
        this.userAppService = userAppService;
    }

    /** 获取用户信息
     * @return */
    @GetMapping
    public Result<UserDTO> getUserInfo() {
        String userId = UserContext.getCurrentUserId();
        return Result.success(userAppService.getUserInfo(userId));
    }

    /** 修改用户信息
     * @param userUpdateRequest 需要修改的信息
     * @return */
    @PostMapping
    public Result<?> updateUserInfo(@RequestBody @Validated UserUpdateRequest userUpdateRequest) {
        String userId = UserContext.getCurrentUserId();
        userAppService.updateUserInfo(userUpdateRequest, userId);
        return Result.success();
    }
}
