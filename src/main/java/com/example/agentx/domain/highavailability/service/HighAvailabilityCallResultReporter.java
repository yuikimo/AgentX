package com.example.agentx.domain.highavailability.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import com.example.agentx.domain.highavailability.gateway.HighAvailabilityGateway;
import com.example.agentx.infrastructure.config.HighAvailabilityProperties;
import com.example.agentx.infrastructure.highavailability.dto.request.ReportResultRequest;

@Service
public class HighAvailabilityCallResultReporter {

    private static final Logger logger = LoggerFactory.getLogger(HighAvailabilityCallResultReporter.class);

    private final HighAvailabilityProperties properties;
    private final HighAvailabilityGateway gateway;
    private final Queue<PendingReport> pendingReports = new ConcurrentLinkedQueue<>();

    public HighAvailabilityCallResultReporter(HighAvailabilityProperties properties, HighAvailabilityGateway gateway) {
        this.properties = properties;
        this.gateway = gateway;
    }

    public void reportCallResult(String instanceId, String modelId, boolean success, long latencyMs, String errorMessage) {
        if (!properties.isEnabled() || instanceId == null || modelId == null) {
            return;
        }
        ReportResultRequest request = new ReportResultRequest();
        request.setInstanceId(instanceId);
        request.setBusinessId(modelId);
        request.setSuccess(success);
        request.setLatencyMs(latencyMs);
        request.setErrorMessage(errorMessage);
        request.setCallTimestamp(System.currentTimeMillis());
        pendingReports.offer(new PendingReport(request, 0, System.currentTimeMillis()));
    }

    @Scheduled(fixedDelayString = "${high-availability.report-flush-interval-ms:1000}",
            initialDelayString = "${high-availability.report-flush-interval-ms:1000}")
    public void flushPendingReports() {
        if (!properties.isEnabled() || pendingReports.isEmpty()) {
            return;
        }
        int batchSize = Math.max(1, properties.getReportBatchSize());
        long now = System.currentTimeMillis();
        List<PendingReport> deferred = new ArrayList<>();
        int processed = 0;

        while (processed < batchSize) {
            PendingReport pendingReport = pendingReports.poll();
            if (pendingReport == null) {
                break;
            }
            if (pendingReport.nextAttemptAtMillis() > now) {
                deferred.add(pendingReport);
                continue;
            }
            try {
                gateway.reportResult(pendingReport.request());
                processed++;
            } catch (Exception e) {
                int nextRetry = pendingReport.retryCount() + 1;
                if (nextRetry <= Math.max(0, properties.getReportMaxRetries())) {
                    long nextAttemptAt = now + Math.max(100L, properties.getReportRetryDelayMs()) * nextRetry;
                    deferred.add(new PendingReport(pendingReport.request(), nextRetry, nextAttemptAt));
                } else {
                    logger.error("调用结果上报失败且超过最大重试次数: instanceId={}, modelId={}",
                            pendingReport.request().getInstanceId(), pendingReport.request().getBusinessId(), e);
                }
            }
        }

        for (PendingReport pendingReport : deferred) {
            pendingReports.offer(pendingReport);
        }
    }

    private record PendingReport(ReportResultRequest request, int retryCount, long nextAttemptAtMillis) {
    }
}
