package com.example.agentx.infrastructure.config;

import com.example.agentx.domain.agent.model.LLMModelConfig;
import com.example.agentx.domain.conversation.constant.MessageType;
import com.example.agentx.domain.conversation.constant.Role;
import com.example.agentx.domain.llm.model.config.ProviderConfig;
import com.example.agentx.domain.llm.model.enums.ModelType;
import com.example.agentx.domain.task.constant.TaskStatus;
import com.example.agentx.infrastructure.converter.LLMModelConfigConverter;
import com.example.agentx.infrastructure.converter.ListConverter;
import com.example.agentx.infrastructure.converter.MessageTypeConverter;
import com.example.agentx.infrastructure.converter.ModelTypeConverter;
import com.example.agentx.infrastructure.converter.ProviderConfigConverter;
import com.example.agentx.infrastructure.converter.ProviderProtocolConverter;
import com.example.agentx.infrastructure.converter.RoleConverter;
import com.example.agentx.infrastructure.converter.TaskStatusConverter;
import com.example.agentx.infrastructure.llm.protocol.enums.ProviderProtocol;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.type.TypeHandlerRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.annotation.PostConstruct;

import java.util.List;

/**
 * MyBatis类型处理器配置类
 * 用于手动注册类型处理器
 */
@Configuration
public class MyBatisTypeHandlerConfig {

    private static final Logger log = LoggerFactory.getLogger(MyBatisTypeHandlerConfig.class);

    @Autowired
    private SqlSessionFactory sqlSessionFactory;

    /**
     * 初始化注册类型处理器
     */
    @PostConstruct
    public void registerTypeHandlers() {
        TypeHandlerRegistry typeHandlerRegistry = sqlSessionFactory.getConfiguration().getTypeHandlerRegistry();

        // 确保自动扫描没有生效时，我们手动注册需要的转换器
        typeHandlerRegistry.register(ProviderConfig.class, new ProviderConfigConverter());
        typeHandlerRegistry.register(List.class, new ListConverter());
        typeHandlerRegistry.register(LLMModelConfig.class, new LLMModelConfigConverter());
        typeHandlerRegistry.register(ProviderProtocol.class, new ProviderProtocolConverter());
        typeHandlerRegistry.register(ModelType.class, new ModelTypeConverter());
        typeHandlerRegistry.register(Role.class, new RoleConverter());
        typeHandlerRegistry.register(MessageType.class, new MessageTypeConverter());
        typeHandlerRegistry.register(TaskStatus.class, new TaskStatusConverter());

        log.info("手动注册类型处理器：ProviderConfigConverter");

        // 打印所有已注册的类型处理器
        log.info("已注册的类型处理器: {}", typeHandlerRegistry.getTypeHandlers().size());
    }
} 