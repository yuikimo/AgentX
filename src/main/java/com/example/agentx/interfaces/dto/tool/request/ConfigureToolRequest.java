package com.example.agentx.interfaces.dto.tool.request;

import jakarta.validation.constraints.NotNull;

import java.util.Map;

/** 配置用户已安装工具的私有安装变量 */
public class ConfigureToolRequest {

    /** 与工具版本 installFields 中声明的字段名对应 */
    @NotNull(message = "安装配置不可为空")
    private Map<String, Object> installValues;

    public Map<String, Object> getInstallValues() {
        return installValues;
    }

    public void setInstallValues(Map<String, Object> installValues) {
        this.installValues = installValues;
    }
}
