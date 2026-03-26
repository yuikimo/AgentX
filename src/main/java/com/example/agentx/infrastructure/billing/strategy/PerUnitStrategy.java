package com.example.agentx.infrastructure.billing.strategy;

import com.example.agentx.domain.product.constant.PricingConfigKeys;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 按次计费策略 按使用次数进行固定计费
 */
@Component
public class PerUnitStrategy implements RuleStrategy {

    @Override
    public BigDecimal process(Map<String, Object> usageData, Map<String, Object> pricingConfig) {
        return null;
    }

    @Override
    public String getStrategyName() {
        return "PER_UNIT_STRATEGY";
    }

    @Override
    public boolean validateUsageData(Map<String, Object> usageData) {
        return false;
    }

    @Override
    public boolean validatePricingConfig(Map<String, Object> pricingConfig) {
        if (pricingConfig == null || pricingConfig.isEmpty()) {
            return false;
        }

        // 检查必需字段
        Object costPerUnit = pricingConfig.get(PricingConfigKeys.COST_PER_UNIT);

        if (costPerUnit == null) {
            return false;
        }

        try {
            BigDecimal costDecimal = getBigDecimalValue(pricingConfig, PricingConfigKeys.COST_PER_UNIT);

            // 检查价格非负
            return costDecimal.compareTo(BigDecimal.ZERO) >= 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 从配置中获取BigDecimal值，支持多种数值类型转换
     */
    private BigDecimal getBigDecimalValue(Map<String, Object> config, String key) {
        Object value = config.get(key);
        if (value instanceof BigDecimal) {
            return (BigDecimal) value;
        } else if (value instanceof Double) {
            return BigDecimal.valueOf((Double) value);
        } else if (value instanceof Float) {
            return BigDecimal.valueOf((Float) value);
        } else if (value instanceof Integer) {
            return new BigDecimal((Integer) value);
        } else if (value instanceof Long) {
            return new BigDecimal((Long) value);
        } else if (value instanceof String) {
            return new BigDecimal((String) value);
        } else {
            throw new IllegalArgumentException("无法转换为BigDecimal: " + key + " = " + value);
        }
    }
}
