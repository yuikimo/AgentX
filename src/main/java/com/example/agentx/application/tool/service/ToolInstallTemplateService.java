package com.example.agentx.application.tool.service;

import org.springframework.stereotype.Service;
import com.example.agentx.infrastructure.exception.BusinessException;
import com.example.agentx.infrastructure.utils.JsonUtils;
import com.example.agentx.infrastructure.utils.ValidationUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** MCP安装模板校验、变量保存和渲染服务 */
@Service
public class ToolInstallTemplateService {

    public static final String STATUS_UNCONFIGURED = "UNCONFIGURED";
    public static final String STATUS_CONFIGURED = "CONFIGURED";
    public static final String STATUS_INVALID = "INVALID";

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\$\\{([A-Za-z0-9_.-]+)}");

    public void validateTemplateForPublish(Map<String, Object> auditedInstallCommand,
            Map<String, Object> installTemplate, Map<String, Object> installFields, String mcpServerName) {
        if (installTemplate == null || installTemplate.isEmpty()) {
            throw new BusinessException("上架工具必须提供公开安装模板");
        }
        Map<String, Object> auditedServer = getMcpServerConfig(auditedInstallCommand, mcpServerName);
        Map<String, Object> templateServer = getMcpServerConfig(installTemplate, mcpServerName);

        if (!Objects.equals(auditedServer.get("command"), templateServer.get("command"))) {
            throw new BusinessException("公开安装模板的 command 必须与审核通过的安装命令一致");
        }
        if (!Objects.equals(auditedServer.get("args"), templateServer.get("args"))) {
            throw new BusinessException("公开安装模板的 args 必须与审核通过的安装命令一致");
        }

        Set<String> declaredFields = getDeclaredFieldNames(installFields);
        Set<String> placeholders = extractPlaceholders(installTemplate);
        for (String placeholder : placeholders) {
            if (!declaredFields.contains(placeholder)) {
                throw new BusinessException("安装模板变量未声明: " + placeholder);
            }
        }
    }

    public boolean requiresUserConfig(Map<String, Object> installFields) {
        return !getDeclaredFieldNames(installFields).isEmpty();
    }

    public boolean requiresSensitiveUserConfig(Map<String, Object> installFields) {
        for (Map<String, Object> field : getFieldDefinitions(installFields)) {
            if (isSensitiveField(field)) {
                return true;
            }
        }
        return false;
    }

    public String encryptInstallValues(Map<String, Object> installFields, Map<String, Object> installValues) {
        validateInstallValues(installFields, installValues);
        return ValidationUtils.EncryptUtils.encrypt(JsonUtils.toJsonString(installValues));
    }

    public Map<String, Object> decryptInstallValues(String encryptedInstallValues) {
        if (encryptedInstallValues == null || encryptedInstallValues.isBlank()) {
            return Map.of();
        }
        String json = ValidationUtils.EncryptUtils.decrypt(encryptedInstallValues);
        return JsonUtils.parseMap(json);
    }

    public Map<String, Object> renderInstallCommand(Map<String, Object> installTemplate,
            Map<String, Object> installFields, Map<String, Object> installValues) {
        validateInstallValues(installFields, installValues);
        Map<String, Object> templateCopy = JsonUtils.parseMap(JsonUtils.toJsonString(installTemplate));
        if (templateCopy == null) {
            throw new BusinessException("安装模板格式错误");
        }
        Object rendered = renderNode(templateCopy, installValues == null ? Map.of() : installValues);
        @SuppressWarnings("unchecked")
        Map<String, Object> renderedMap = (Map<String, Object>) rendered;
        return renderedMap;
    }

    public Map<String, Object> emptyInstallFields() {
        return Map.of("fields", List.of());
    }

    private void validateInstallValues(Map<String, Object> installFields, Map<String, Object> installValues) {
        Set<String> declaredFields = getDeclaredFieldNames(installFields);
        Map<String, Object> values = installValues == null ? Map.of() : installValues;

        for (String key : values.keySet()) {
            if (!declaredFields.contains(key)) {
                throw new BusinessException("安装配置包含未声明字段: " + key);
            }
        }

        for (Map<String, Object> field : getFieldDefinitions(installFields)) {
            String name = stringValue(field.get("name"));
            boolean required = Boolean.TRUE.equals(field.get("required"));
            Object value = values.get(name);
            if (required && (value == null || (value instanceof String str && str.isBlank()))) {
                throw new BusinessException("安装配置缺少必填字段: " + name);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getMcpServerConfig(Map<String, Object> installCommand, String mcpServerName) {
        if (installCommand == null || installCommand.isEmpty()) {
            throw new BusinessException("安装命令为空");
        }
        Object mcpServersObj = installCommand.get("mcpServers");
        if (!(mcpServersObj instanceof Map<?, ?> mcpServers)) {
            throw new BusinessException("安装命令中 mcpServers 为空或格式错误");
        }
        Object serverObj = mcpServers.get(mcpServerName);
        if (!(serverObj instanceof Map<?, ?>)) {
            throw new BusinessException("安装命令必须包含 MCP 服务: " + mcpServerName);
        }
        return (Map<String, Object>) serverObj;
    }

    private Set<String> getDeclaredFieldNames(Map<String, Object> installFields) {
        Set<String> names = new LinkedHashSet<>();
        for (Map<String, Object> field : getFieldDefinitions(installFields)) {
            String name = stringValue(field.get("name"));
            if (name == null || name.isBlank()) {
                throw new BusinessException("安装字段 name 不可为空");
            }
            if (!names.add(name)) {
                throw new BusinessException("安装字段重复: " + name);
            }
        }
        return names;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getFieldDefinitions(Map<String, Object> installFields) {
        if (installFields == null || installFields.isEmpty()) {
            return List.of();
        }
        Object fieldsObj = installFields.get("fields");
        if (fieldsObj == null) {
            return List.of();
        }
        if (!(fieldsObj instanceof List<?> fields)) {
            throw new BusinessException("installFields.fields 必须是数组");
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object fieldObj : fields) {
            if (!(fieldObj instanceof Map<?, ?>)) {
                throw new BusinessException("installFields.fields 中的字段定义必须是对象");
            }
            result.add((Map<String, Object>) fieldObj);
        }
        return result;
    }

    private Set<String> extractPlaceholders(Object value) {
        Set<String> placeholders = new LinkedHashSet<>();
        collectPlaceholders(value, placeholders);
        return placeholders;
    }

    private void collectPlaceholders(Object value, Set<String> placeholders) {
        if (value instanceof Map<?, ?> map) {
            map.values().forEach(item -> collectPlaceholders(item, placeholders));
            return;
        }
        if (value instanceof List<?> list) {
            list.forEach(item -> collectPlaceholders(item, placeholders));
            return;
        }
        if (value instanceof String str) {
            Matcher matcher = PLACEHOLDER_PATTERN.matcher(str);
            while (matcher.find()) {
                placeholders.add(matcher.group(1));
            }
        }
    }

    private boolean isSensitiveField(Map<String, Object> field) {
        Object sensitive = field.get("sensitive");
        if (Boolean.TRUE.equals(sensitive)) {
            return true;
        }

        String type = stringValue(field.get("type"));
        if (type == null) {
            return false;
        }
        return "secret".equalsIgnoreCase(type) || "password".equalsIgnoreCase(type);
    }

    @SuppressWarnings("unchecked")
    private Object renderNode(Object node, Map<String, Object> values) {
        if (node instanceof Map<?, ?> map) {
            ((Map<String, Object>) map).replaceAll((key, value) -> renderNode(value, values));
            return map;
        }
        if (node instanceof List<?> list) {
            List<Object> rendered = new ArrayList<>(list.size());
            for (Object item : list) {
                rendered.add(renderNode(item, values));
            }
            return rendered;
        }
        if (!(node instanceof String str)) {
            return node;
        }

        Matcher exactMatcher = PLACEHOLDER_PATTERN.matcher(str);
        if (exactMatcher.matches()) {
            return values.get(exactMatcher.group(1));
        }

        Matcher matcher = PLACEHOLDER_PATTERN.matcher(str);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            Object value = values.get(matcher.group(1));
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(value == null ? "" : String.valueOf(value)));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
