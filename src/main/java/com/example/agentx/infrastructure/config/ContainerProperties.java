package com.example.agentx.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 容器管理属性。 */
@ConfigurationProperties(prefix = "agentx.container")
public class ContainerProperties {

    /** Docker连接配置 */
    private String dockerHost = "unix:///var/run/docker.sock";

    /** 用户数据卷基础路径 */
    private String userVolumeBasePath = "/docker/users";

    /** 默认MCP网关镜像 */
    private String defaultMcpGatewayImage = "ghcr.io/lucky-aeon/mcp-gateway:latest";

    /** 后端容器访问宿主机地址（用于bridge/host模式端口映射访问） */
    private String hostAccessAddress = "localhost";

    /** 容器监控间隔（毫秒） */
    private long monitorInterval = 300000;

    /** 资源使用率更新间隔（毫秒） */
    private long statsUpdateInterval = 120000;

    public String getDockerHost() {
        return dockerHost;
    }

    public void setDockerHost(String dockerHost) {
        this.dockerHost = dockerHost;
    }

    public String getUserVolumeBasePath() {
        return userVolumeBasePath;
    }

    public void setUserVolumeBasePath(String userVolumeBasePath) {
        this.userVolumeBasePath = userVolumeBasePath;
    }

    public String getDefaultMcpGatewayImage() {
        return defaultMcpGatewayImage;
    }

    public void setDefaultMcpGatewayImage(String defaultMcpGatewayImage) {
        this.defaultMcpGatewayImage = defaultMcpGatewayImage;
    }

    public String getHostAccessAddress() {
        return hostAccessAddress;
    }

    public void setHostAccessAddress(String hostAccessAddress) {
        this.hostAccessAddress = hostAccessAddress;
    }

    public long getMonitorInterval() {
        return monitorInterval;
    }

    public void setMonitorInterval(long monitorInterval) {
        this.monitorInterval = monitorInterval;
    }

    public long getStatsUpdateInterval() {
        return statsUpdateInterval;
    }

    public void setStatsUpdateInterval(long statsUpdateInterval) {
        this.statsUpdateInterval = statsUpdateInterval;
    }
}
