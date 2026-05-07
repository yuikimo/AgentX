package com.example.agentx.interfaces.api.portal.account;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import com.example.agentx.application.account.dto.AccountDTO;
import com.example.agentx.application.account.service.AccountAppService;
import com.example.agentx.infrastructure.auth.UserContext;
import com.example.agentx.interfaces.api.common.Result;
import com.example.agentx.interfaces.dto.account.request.AddCreditRequest;
import com.example.agentx.interfaces.dto.account.request.RechargeRequest;

import java.math.BigDecimal;

/** 账户管理控制层 提供用户账户管理的API接口 */
@RestController
@RequestMapping("/accounts")
public class AccountController {

    private final AccountAppService accountAppService;

    public AccountController(AccountAppService accountAppService) {
        this.accountAppService = accountAppService;
    }

    /** 获取当前用户账户信息
     * 
     * @return 账户信息 */
    @GetMapping("/current")
    public Result<AccountDTO> getCurrentUserAccount() {
        String userId = UserContext.getCurrentUserId();
        AccountDTO account = accountAppService.getUserAccount(userId);
        return Result.success(account);
    }
}