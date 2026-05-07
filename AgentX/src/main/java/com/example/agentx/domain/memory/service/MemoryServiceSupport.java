package com.example.agentx.domain.memory.service;

import org.springframework.util.StringUtils;
import com.example.agentx.domain.memory.model.MemoryType;
import com.example.agentx.infrastructure.exception.BusinessException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

final class MemoryServiceSupport {

    private MemoryServiceSupport() {
    }

    static String normalizeText(String value) {
        return value == null ? "" : value.replaceAll("\n+", "\n").replaceAll("\\s+", " ").trim().toLowerCase();
    }

    static String safeCacheKeyPart(String value) {
        return value == null ? "" : value;
    }

    static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte b : hash) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (Exception e) {
            throw new BusinessException("计算hash失败", e);
        }
    }

    static Float safeImportance(Float value) {
        if (value == null) {
            return 0.5f;
        }
        return Math.max(0f, Math.min(1f, value));
    }

    static List<String> safeList(List<String> list) {
        return list == null ? new ArrayList<>() : new ArrayList<>(list);
    }

    static Float max(Float left, Float right) {
        if (left == null) {
            return right == null ? 0.5f : right;
        }
        if (right == null) {
            return left;
        }
        return Math.max(left, right);
    }

    static List<String> mergeTags(List<String> left, List<String> right) {
        Set<String> tags = new LinkedHashSet<>();
        if (left != null) {
            tags.addAll(left);
        }
        if (right != null) {
            tags.addAll(right);
        }
        return new ArrayList<>(tags);
    }

    static Map<String, Object> mergeData(Map<String, Object> left, Map<String, Object> right) {
        if (left == null && right == null) {
            return null;
        }
        Map<String, Object> merged = new LinkedHashMap<>();
        if (left != null) {
            merged.putAll(left);
        }
        if (right != null) {
            merged.putAll(right);
        }
        return merged;
    }

    static String pickRichText(String oldText, String newText) {
        if (!StringUtils.hasText(newText)) {
            return oldText;
        }
        if (!StringUtils.hasText(oldText)) {
            return newText;
        }
        return newText.length() >= oldText.length() ? newText : oldText;
    }

    static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    static MemoryType parseMemoryType(Object value) {
        String type = stringValue(value);
        if (!StringUtils.hasText(type)) {
            return MemoryType.FACT;
        }
        try {
            return MemoryType.valueOf(type.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return MemoryType.FACT;
        }
    }

    static Float parseImportance(Object value) {
        if (value instanceof Number number) {
            return safeImportance(number.floatValue());
        }
        String text = stringValue(value);
        if (!StringUtils.hasText(text)) {
            return 0.5f;
        }
        try {
            return safeImportance(Float.parseFloat(text));
        } catch (NumberFormatException e) {
            return 0.5f;
        }
    }

    static LocalDateTime parseUpdatedAt(Object value) {
        if (value instanceof Number number) {
            return LocalDateTime.ofInstant(Instant.ofEpochMilli(number.longValue()), ZoneOffset.UTC);
        }
        String text = stringValue(value);
        if (!StringUtils.hasText(text)) {
            return null;
        }
        try {
            return LocalDateTime.ofInstant(Instant.ofEpochMilli(Long.parseLong(text)), ZoneOffset.UTC);
        } catch (NumberFormatException e) {
            try {
                return LocalDateTime.parse(text);
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    static List<String> parseTags(Object value) {
        if (value instanceof Collection<?> collection) {
            return collection.stream().map(MemoryServiceSupport::stringValue).filter(StringUtils::hasText)
                    .collect(Collectors.toCollection(ArrayList::new));
        }
        String text = stringValue(value);
        if (!StringUtils.hasText(text)) {
            return new ArrayList<>();
        }
        return Arrays.stream(text.split(",")).map(String::trim).filter(StringUtils::hasText)
                .collect(Collectors.toCollection(ArrayList::new));
    }
}
