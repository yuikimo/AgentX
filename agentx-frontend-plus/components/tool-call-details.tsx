"use client"

import { useMemo } from "react"
import type { ReactNode } from "react"
import { ChevronDown, FileJson, TerminalSquare } from "lucide-react"
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible"

interface ToolCallDetailsProps {
  payload?: string
  content?: string
}

interface ToolPayload {
  name?: string
  arguments?: string
  result?: string
  status?: string
  success?: boolean | string
  durationMs?: number | string
  errorCode?: string
  errorMessage?: string
  errorCategory?: string
  toolCalls?: Array<NormalizedToolCall>
}

interface NormalizedToolCall {
  name?: string
  arguments?: string
  result?: string
  status?: string
  success?: boolean | string
  durationMs?: number | string
  errorCode?: string
  errorMessage?: string
  errorCategory?: string
}
const TOOL_CALL_LIMIT_EXCEEDED_CODE = "TOOL_CALL_LIMIT_EXCEEDED"

export function ToolCallDetails({ payload, content }: ToolCallDetailsProps) {
  const parsedPayload = useMemo(() => parseToolPayload(payload), [payload])
  const collapsedSummary = useMemo(
    () => buildCollapsedSummary(parsedPayload, content),
    [content, parsedPayload]
  )

  if (!parsedPayload || (!parsedPayload.arguments && !parsedPayload.result)) {
    if (!parsedPayload?.toolCalls?.length) {
      return null
    }
  }

  if (parsedPayload.toolCalls?.length) {
    return (
      <Collapsible className="mt-2 rounded-lg border border-blue-100 bg-blue-50/50">
        <CollapsibleTrigger className="flex w-full items-center justify-between px-3 py-2 text-left text-xs text-blue-700 hover:bg-blue-100/60">
          <div className="flex items-center gap-2 font-medium">
            <TerminalSquare className="h-4 w-4" />
            <span>{collapsedSummary}</span>
          </div>
          <div className="flex items-center gap-1 text-[11px] text-blue-600">
            <span>展开查看</span>
            <ChevronDown className="h-4 w-4" />
          </div>
        </CollapsibleTrigger>
        <CollapsibleContent className="space-y-3 border-t border-blue-100 px-3 py-3">
          {parsedPayload.toolCalls.map((toolCall, index) => (
            <div
              key={`${toolCall.name || "tool"}-${index}`}
              className="space-y-3 rounded-md border border-blue-100 bg-white/70 p-3"
            >
              <div className="text-xs font-medium text-slate-600">
                {toolCall.name || `工具 ${index + 1}`}
              </div>
              <ToolMeta
                status={toolCall.status}
                success={toolCall.success}
                durationMs={toolCall.durationMs}
                errorCode={toolCall.errorCode}
                errorCategory={toolCall.errorCategory}
              />
              {toolCall.arguments && (
                <ToolPayloadSection icon={<FileJson className="h-4 w-4 text-slate-500" />} title="调用参数">
                  {formatToolText(toolCall.arguments)}
                </ToolPayloadSection>
              )}
              {toolCall.result && (
                <ToolPayloadSection icon={<TerminalSquare className="h-4 w-4 text-slate-500" />} title="执行结果">
                  {formatToolText(toolCall.result)}
                </ToolPayloadSection>
              )}
            </div>
          ))}
        </CollapsibleContent>
      </Collapsible>
    )
  }

  if (!parsedPayload || (!parsedPayload.arguments && !parsedPayload.result)) {
    return null
  }

  return (
    <Collapsible className="mt-2 rounded-lg border border-blue-100 bg-blue-50/50">
      <CollapsibleTrigger className="flex w-full items-center justify-between px-3 py-2 text-left text-xs text-blue-700 hover:bg-blue-100/60">
        <div className="flex items-center gap-2 font-medium">
          <TerminalSquare className="h-4 w-4" />
          <span>{collapsedSummary}</span>
        </div>
        <div className="flex items-center gap-1 text-[11px] text-blue-600">
          <span>展开查看</span>
          <ChevronDown className="h-4 w-4" />
        </div>
      </CollapsibleTrigger>
      <CollapsibleContent className="space-y-3 border-t border-blue-100 px-3 py-3">
        <ToolMeta
          status={parsedPayload.status}
          success={parsedPayload.success}
          durationMs={parsedPayload.durationMs}
          errorCode={parsedPayload.errorCode}
          errorCategory={parsedPayload.errorCategory}
        />
        {parsedPayload.arguments && (
          <ToolPayloadSection icon={<FileJson className="h-4 w-4 text-slate-500" />} title="调用参数">
            {formatToolText(parsedPayload.arguments)}
          </ToolPayloadSection>
        )}
        {parsedPayload.result && (
          <ToolPayloadSection icon={<TerminalSquare className="h-4 w-4 text-slate-500" />} title="执行结果">
            {formatToolText(parsedPayload.result)}
          </ToolPayloadSection>
        )}
      </CollapsibleContent>
    </Collapsible>
  )
}

function ToolMeta({
  status,
  success,
  durationMs,
  errorCode,
  errorCategory,
}: {
  status?: string
  success?: boolean | string
  durationMs?: number | string
  errorCode?: string
  errorCategory?: string
}) {
  const statusLabel = resolveStatusLabel(success, errorCode, errorCategory, status)
  const resolvedDurationMs = resolveDurationMs(durationMs)

  const metaItems = [
    statusLabel ? `状态：${statusLabel}` : "",
    resolvedDurationMs > 0 ? `耗时：${resolvedDurationMs}ms` : "",
    errorCode ? `错误码：${errorCode}` : "",
    errorCategory ? `错误类型：${errorCategory}` : "",
  ].filter(Boolean)

  if (!metaItems.length) {
    return null
  }

  return (
    <div className="flex flex-wrap gap-2 text-[11px] text-slate-500">
      {metaItems.map((item) => (
        <span key={item} className="rounded bg-slate-100 px-2 py-1">
          {item}
        </span>
      ))}
    </div>
  )
}

function ToolPayloadSection({
  title,
  icon,
  children,
}: {
  title: string
  icon: ReactNode
  children: string
}) {
  return (
    <div className="space-y-1">
      <div className="flex items-center gap-2 text-xs font-medium text-slate-600">
        {icon}
        <span>{title}</span>
      </div>
      <pre className="max-h-72 overflow-auto whitespace-pre-wrap break-words rounded-md bg-slate-950 px-3 py-2 text-xs text-slate-100">
        {children}
      </pre>
    </div>
  )
}

function parseToolPayload(payload?: string): ToolPayload | null {
  if (!payload) {
    return null
  }

  try {
    const parsed = JSON.parse(payload) as ToolPayload
    return {
      name: parsed.name,
      arguments: parsed.arguments,
      result: parsed.result,
      status: parsed.status,
      success: parsed.success,
      durationMs: parsed.durationMs,
      errorCode: parsed.errorCode,
      errorMessage: parsed.errorMessage,
      errorCategory: parsed.errorCategory,
      toolCalls: parsed.toolCalls,
    }
  } catch {
    return {
      result: payload,
    }
  }
}

function formatToolText(value: string): string {
  const normalized = value.replace(/\\n/g, "\n")
  try {
    return JSON.stringify(JSON.parse(normalized), null, 2)
  } catch {
    return normalized
  }
}

function buildCollapsedSummary(payload: ToolPayload | null, content?: string): string {
  if (payload?.toolCalls?.length) {
    return buildMultiToolSummary(payload.toolCalls)
  }

  const toolName = payload?.name || extractToolName(content) || "工具调用"
  const statusLabel = resolveStatusLabel(payload?.success, payload?.errorCode, payload?.errorCategory, payload?.status)
  const durationLabel = formatDuration(payload?.durationMs)

  return [toolName, statusLabel, durationLabel].filter(Boolean).join(" · ")
}

function buildMultiToolSummary(toolCalls: NormalizedToolCall[]): string {
  const total = toolCalls.length
  let successCount = 0
  let failedCount = 0
  let pendingCount = 0
  let limitExceededCount = 0
  let maxDurationMs = 0

  toolCalls.forEach((toolCall) => {
    if (isToolCallLimitExceeded(toolCall)) {
      limitExceededCount += 1
      const durationMs = resolveDurationMs(toolCall.durationMs)
      if (durationMs > maxDurationMs) {
        maxDurationMs = durationMs
      }
      return
    }
    const resolvedSuccess = resolveSuccess(toolCall.success)
    if (resolvedSuccess === true) {
      successCount += 1
    } else if (resolvedSuccess === false) {
      failedCount += 1
    } else {
      pendingCount += 1
    }

    const durationMs = resolveDurationMs(toolCall.durationMs)
    if (durationMs > maxDurationMs) {
      maxDurationMs = durationMs
    }
  })

  const statusParts = [
    successCount > 0 ? `${successCount} 成功` : "",
    failedCount > 0 ? `${failedCount} 失败` : "",
    pendingCount > 0 ? `${pendingCount} 进行中` : "",
    limitExceededCount > 0 ? `${limitExceededCount} 超限` : "",
  ].filter(Boolean)

  return [
    `${total} 个工具`,
    statusParts.join(" "),
    maxDurationMs > 0 ? `最长 ${maxDurationMs}ms` : "",
  ]
    .filter(Boolean)
    .join(" · ")
}

function extractToolName(content?: string): string {
  if (!content) {
    return ""
  }
  const match = content.match(/执行工具[:：]\s*(.+)/)
  return match?.[1]?.trim() || ""
}

function resolveStatusLabel(success?: boolean | string, errorCode?: string, errorCategory?: string, status?: string): string {
  if (isToolCallLimitExceeded({ success, errorCode, errorCategory })) {
    return "已达上限"
  }
  const resolvedSuccess = resolveSuccess(success)
  if (resolvedSuccess === true) {
    return "成功"
  }
  if (resolvedSuccess === false) {
    return "失败"
  }
  if (typeof status === "string") {
    const normalizedStatus = status.toLowerCase()
    if (normalizedStatus === "running" || normalizedStatus === "pending") {
      return "进行中"
    }
    if (normalizedStatus === "success" || normalizedStatus === "succeeded") {
      return "成功"
    }
    if (normalizedStatus === "failed" || normalizedStatus === "failure" || normalizedStatus === "error") {
      return "失败"
    }
  }
  if (errorCategory || errorCode) {
    return "失败"
  }
  return "进行中"
}

function resolveSuccess(success?: boolean | string): boolean | undefined {
  if (typeof success === "boolean") {
    return success
  }
  if (typeof success === "string") {
    if (success === "true") {
      return true
    }
    if (success === "false") {
      return false
    }
  }
  return undefined
}

function isToolCallLimitExceeded(
  toolCall?: Pick<NormalizedToolCall, "errorCode" | "errorCategory" | "errorMessage" | "result" | "success">
): boolean {
  if (!toolCall) {
    return false
  }
  if (toolCall.errorCode === TOOL_CALL_LIMIT_EXCEEDED_CODE || toolCall.errorCategory === TOOL_CALL_LIMIT_EXCEEDED_CODE) {
    return true
  }
  const mergedText = `${toolCall.errorMessage || ""} ${toolCall.result || ""}`.toLowerCase()
  return mergedText.includes("工具调用次数已达到上限") || mergedText.includes("工具调用次数已达上限")
}

function resolveDurationMs(durationMs?: number | string): number {
  if (typeof durationMs === "number" && Number.isFinite(durationMs)) {
    return durationMs
  }
  if (typeof durationMs === "string") {
    const parsed = Number(durationMs)
    return Number.isFinite(parsed) ? parsed : 0
  }
  return 0
}

function formatDuration(durationMs?: number | string): string {
  const resolvedDurationMs = resolveDurationMs(durationMs)
  return resolvedDurationMs > 0 ? `${resolvedDurationMs}ms` : ""
}
