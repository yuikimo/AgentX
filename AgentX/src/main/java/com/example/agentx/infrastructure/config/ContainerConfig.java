package com.example.agentx.infrastructure.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 容器配置装配类。 */
@Configuration
@EnableConfigurationProperties(ContainerProperties.class)
public class ContainerConfig {
}
