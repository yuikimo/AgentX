package com.example.agentx.application.conversation.service.message;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderResult;
import org.springframework.scheduling.TaskScheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;

/** Shared helpers for wrapping tool executors and collecting execution metadata. */
public final class ToolCaptureUtils {

    private ToolCaptureUtils() {
    }

    public static Map<ToolSpecification, ToolExecutor> wrapToolExecutorsForCapture(
            Map<ToolSpecification, ToolExecutor> toolExecutors, List<CapturedToolExecution> capturedToolExecutions) {
        return wrapToolExecutorsForCapture(toolExecutors, capturedToolExecutions, null, null, 0L);
    }

    public static Map<ToolSpecification, ToolExecutor> wrapToolExecutorsForCapture(
            Map<ToolSpecification, ToolExecutor> toolExecutors, List<CapturedToolExecution> capturedToolExecutions,
            ToolExecutionProgressListener progressListener, TaskScheduler progressTaskScheduler,
            long progressIntervalMs) {
        if (toolExecutors == null || toolExecutors.isEmpty()) {
            return toolExecutors == null ? Collections.emptyMap() : toolExecutors;
        }
        if (capturedToolExecutions == null && progressListener == null) {
            return toolExecutors;
        }
        Map<ToolSpecification, ToolExecutor> wrappedTools = new LinkedHashMap<>();
        toolExecutors.forEach((toolSpecification, toolExecutor) -> wrappedTools.put(toolSpecification,
                wrapSingleToolExecutorForCapture(toolExecutor, capturedToolExecutions, progressListener,
                        progressTaskScheduler, progressIntervalMs)));
        return wrappedTools;
    }

    public static ToolProvider wrapToolProviderForCapture(ToolProvider delegate,
            List<CapturedToolExecution> capturedToolExecutions) {
        return wrapToolProviderForCapture(delegate, capturedToolExecutions, null, null, 0L);
    }

    public static ToolProvider wrapToolProviderForCapture(ToolProvider delegate,
            List<CapturedToolExecution> capturedToolExecutions, ToolExecutionProgressListener progressListener,
            TaskScheduler progressTaskScheduler, long progressIntervalMs) {
        if (delegate == null) {
            return delegate;
        }
        if (capturedToolExecutions == null && progressListener == null) {
            return delegate;
        }
        return request -> {
            ToolProviderResult delegateResult = delegate.provideTools(request);
            if (delegateResult == null || delegateResult.tools() == null || delegateResult.tools().isEmpty()) {
                return delegateResult;
            }
            return new ToolProviderResult(wrapToolExecutorsForCapture(delegateResult.tools(), capturedToolExecutions,
                    progressListener, progressTaskScheduler, progressIntervalMs));
        };
    }

    public static ToolExecutor wrapSingleToolExecutorForCapture(ToolExecutor toolExecutor,
            List<CapturedToolExecution> capturedToolExecutions) {
        return wrapSingleToolExecutorForCapture(toolExecutor, capturedToolExecutions, null, null, 0L);
    }

    public static ToolExecutor wrapSingleToolExecutorForCapture(ToolExecutor toolExecutor,
            List<CapturedToolExecution> capturedToolExecutions, ToolExecutionProgressListener progressListener,
            TaskScheduler progressTaskScheduler, long progressIntervalMs) {
        return (request, memoryId) -> {
            long startTime = System.currentTimeMillis();
            ScheduledFuture<?> progressFuture = null;
            if (progressListener != null) {
                progressListener.onStarted(request);
                progressFuture = scheduleProgress(progressListener, progressTaskScheduler, request, startTime,
                        progressIntervalMs);
            }
            try {
                String result = toolExecutor.execute(request, memoryId);
                if (capturedToolExecutions != null) {
                    capturedToolExecutions.add(new CapturedToolExecution(request, result,
                            (int) Math.max(0, System.currentTimeMillis() - startTime)));
                }
                return result;
            } finally {
                if (progressFuture != null) {
                    progressFuture.cancel(false);
                }
            }
        };
    }

    private static ScheduledFuture<?> scheduleProgress(ToolExecutionProgressListener progressListener,
            TaskScheduler progressTaskScheduler, ToolExecutionRequest request, long startTime, long progressIntervalMs) {
        if (progressListener == null || progressTaskScheduler == null || progressIntervalMs <= 0) {
            return null;
        }
        Duration period = Duration.ofMillis(Math.max(250L, progressIntervalMs));
        Instant firstRunAt = Instant.now().plus(period);
        return progressTaskScheduler.scheduleAtFixedRate(
                () -> progressListener.onProgress(request, Math.max(0L, System.currentTimeMillis() - startTime)),
                firstRunAt, period);
    }

    public static Optional<CapturedToolExecution> findAndRemoveMatchingExecution(
            List<CapturedToolExecution> capturedToolExecutions, ToolExecutionRequest request, String result) {
        if (capturedToolExecutions == null || capturedToolExecutions.isEmpty()) {
            return Optional.empty();
        }
        synchronized (capturedToolExecutions) {
            Iterator<CapturedToolExecution> iterator = capturedToolExecutions.iterator();
            while (iterator.hasNext()) {
                CapturedToolExecution execution = iterator.next();
                if (sameRequest(execution.request(), request) && Objects.equals(execution.result(), result)) {
                    iterator.remove();
                    return Optional.of(execution);
                }
            }
        }
        return Optional.empty();
    }

    public static List<ToolPayloadUtils.ToolPayloadItem> toPayloadItems(
            List<CapturedToolExecution> capturedToolExecutions) {
        if (capturedToolExecutions == null || capturedToolExecutions.isEmpty()) {
            return Collections.emptyList();
        }
        List<ToolPayloadUtils.ToolPayloadItem> toolCalls = new ArrayList<>(capturedToolExecutions.size());
        for (CapturedToolExecution execution : capturedToolExecutions) {
            ToolExecutionRequest request = execution.request();
            toolCalls.add(new ToolPayloadUtils.ToolPayloadItem(request != null ? request.name() : "unknown",
                    request != null ? request.arguments() : null, execution.result(), execution.executionTime()));
        }
        return toolCalls;
    }

    private static boolean sameRequest(ToolExecutionRequest left, ToolExecutionRequest right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        return Objects.equals(left.name(), right.name()) && Objects.equals(left.arguments(), right.arguments());
    }
}
