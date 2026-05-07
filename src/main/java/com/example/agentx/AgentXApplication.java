package com.example.agentx;

import org.dromara.x.file.storage.spring.EnableFileStorage;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 应用入口类 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
@EnableFileStorage
public class AgentXApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentXApplication.class, args);
    }
}
