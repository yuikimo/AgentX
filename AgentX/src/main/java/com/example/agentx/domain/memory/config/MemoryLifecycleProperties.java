package com.example.agentx.domain.memory.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Memory lifecycle cleanup defaults. */
@ConfigurationProperties(prefix = "memory.lifecycle")
public class MemoryLifecycleProperties {

    private int episodicTtlDays = 7;
    private int maxActivePerUser = 500;
    private int cleanupBatchSize = 200;
    private int cleanupUserBatchSize = 200;
    private int cleanupMaxItemsPerRun = 5000;
    private int cleanupMaxUsersPerRun = 5000;
    private boolean clusterLockEnabled = true;
    private long clusterLockKey = 421337;

    public int getEpisodicTtlDays() {
        return episodicTtlDays;
    }

    public void setEpisodicTtlDays(int episodicTtlDays) {
        this.episodicTtlDays = episodicTtlDays;
    }

    public int getMaxActivePerUser() {
        return maxActivePerUser;
    }

    public void setMaxActivePerUser(int maxActivePerUser) {
        this.maxActivePerUser = maxActivePerUser;
    }

    public int getCleanupBatchSize() {
        return cleanupBatchSize;
    }

    public void setCleanupBatchSize(int cleanupBatchSize) {
        this.cleanupBatchSize = cleanupBatchSize;
    }

    public int getCleanupUserBatchSize() {
        return cleanupUserBatchSize;
    }

    public void setCleanupUserBatchSize(int cleanupUserBatchSize) {
        this.cleanupUserBatchSize = cleanupUserBatchSize;
    }

    public int getCleanupMaxItemsPerRun() {
        return cleanupMaxItemsPerRun;
    }

    public void setCleanupMaxItemsPerRun(int cleanupMaxItemsPerRun) {
        this.cleanupMaxItemsPerRun = cleanupMaxItemsPerRun;
    }

    public int getCleanupMaxUsersPerRun() {
        return cleanupMaxUsersPerRun;
    }

    public void setCleanupMaxUsersPerRun(int cleanupMaxUsersPerRun) {
        this.cleanupMaxUsersPerRun = cleanupMaxUsersPerRun;
    }

    public boolean isClusterLockEnabled() {
        return clusterLockEnabled;
    }

    public void setClusterLockEnabled(boolean clusterLockEnabled) {
        this.clusterLockEnabled = clusterLockEnabled;
    }

    public long getClusterLockKey() {
        return clusterLockKey;
    }

    public void setClusterLockKey(long clusterLockKey) {
        this.clusterLockKey = clusterLockKey;
    }
}
