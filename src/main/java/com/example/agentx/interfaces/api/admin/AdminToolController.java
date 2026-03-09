package com.example.agentx.interfaces.api.admin;

import com.example.agentx.domain.tool.constant.ToolStatus;
import com.example.agentx.interfaces.api.common.Result;
import org.springframework.web.bind.annotation.*;

/**
 * 管理员Tool管理
 */
@RestController
@RequestMapping("/admin/tool")
public class AdminToolController {

    private final AdminToolAppService adminToolAppService;

    public AdminToolController(AdminToolAppService adminToolAppService) {
        this.adminToolAppService = adminToolAppService;
    }

    /**
     * 修改工具的状态
     *
     * @param toolId 工具 id
     * @param status 工具状态
     * @param reason 如果审核未通过，则说明未通过原因
     * @return
     */
    @PostMapping("/{toolId}/status")
    public Result updateStatus(@PathVariable String toolId, ToolStatus status,
                               @RequestParam(required = false) String reason) {
        if (status == ToolStatus.FAILED && (reason == null || reason.isEmpty())) {
            return Result.serverError("拒绝操作需要提供原因");
        }
        adminToolAppService.updateToolStatus(toolId, status, reason);
        return Result.success();
    }

}
