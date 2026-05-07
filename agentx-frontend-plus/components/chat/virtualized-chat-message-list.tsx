"use client"

import { memo, useEffect, useMemo, useRef, useState, type RefObject } from "react"
import { ChatPanelMessageItem } from "@/components/chat/chat-panel-message-item"
import { MessageType, type Message } from "@/types/conversation"

const DEFAULT_ITEM_HEIGHT = 140
const OVERSCAN_PX = 600

interface VirtualizedChatMessageListProps {
  messages: Message[]
  agentName: string
  formatMessageTime: (timestamp?: string) => string
  scrollContainerRef: RefObject<HTMLDivElement | null>
}

interface VirtualItemRowProps {
  index: number
  message: Message
  top: number
  agentName: string
  formatMessageTime: (timestamp?: string) => string
  onHeightChange: (index: number, height: number) => void
}

interface ToolCallPayloadItem {
  name?: string
  arguments?: string
  result?: string
  success?: boolean | string
  durationMs?: number | string
  errorCode?: string
  errorMessage?: string
  errorCategory?: string
}
const TOOL_CALL_LIMIT_EXCEEDED_CODE = "TOOL_CALL_LIMIT_EXCEEDED"

const VirtualItemRow = memo(function VirtualItemRow({
  index,
  message,
  top,
  agentName,
  formatMessageTime,
  onHeightChange,
}: VirtualItemRowProps) {
  const rowRef = useRef<HTMLDivElement | null>(null)

  useEffect(() => {
    const element = rowRef.current
    if (!element) {
      return
    }

    const measure = () => {
      const nextHeight = element.getBoundingClientRect().height
      if (nextHeight > 0) {
        onHeightChange(index, nextHeight)
      }
    }

    measure()
    const observer = new ResizeObserver(measure)
    observer.observe(element)
    return () => observer.disconnect()
  }, [index, message.content, message.id, message.isStreaming, message.payload, message.type, onHeightChange])

  return (
    <div
      ref={rowRef}
      className="absolute left-0 top-0 w-full pb-6"
      style={{ transform: `translateY(${top}px)` }}
    >
      <ChatPanelMessageItem
        message={message}
        agentName={agentName}
        formatMessageTime={formatMessageTime}
      />
    </div>
  )
})

export function VirtualizedChatMessageList({
  messages,
  agentName,
  formatMessageTime,
  scrollContainerRef,
}: VirtualizedChatMessageListProps) {
  const listRef = useRef<HTMLDivElement | null>(null)
  const [scrollTop, setScrollTop] = useState(0)
  const [containerHeight, setContainerHeight] = useState(0)
  const [itemHeights, setItemHeights] = useState<Record<string, number>>({})
  const groupedMessages = useMemo(() => groupConsecutiveToolMessages(messages), [messages])

  useEffect(() => {
    const scrollContainer = scrollContainerRef.current
    if (!scrollContainer) {
      return
    }

    const updateMetrics = () => {
      setScrollTop(scrollContainer.scrollTop)
      setContainerHeight(scrollContainer.clientHeight)
    }

    updateMetrics()
    scrollContainer.addEventListener("scroll", updateMetrics)
    const resizeObserver = new ResizeObserver(updateMetrics)
    resizeObserver.observe(scrollContainer)

    return () => {
      scrollContainer.removeEventListener("scroll", updateMetrics)
      resizeObserver.disconnect()
    }
  }, [scrollContainerRef])

  const listOffsetTop = listRef.current?.offsetTop ?? 0

  const layout = useMemo(() => {
    const positions: number[] = new Array(groupedMessages.length)
    let totalHeight = 0

    for (let index = 0; index < groupedMessages.length; index += 1) {
      positions[index] = totalHeight
      totalHeight += itemHeights[groupedMessages[index].id] ?? DEFAULT_ITEM_HEIGHT
    }

    const viewportTop = Math.max(0, scrollTop - listOffsetTop)
    const viewportBottom = Math.max(viewportTop + containerHeight, 0)
    const startBoundary = Math.max(0, viewportTop - OVERSCAN_PX)
    const endBoundary = viewportBottom + OVERSCAN_PX

    let startIndex = 0
    while (
      startIndex < groupedMessages.length &&
      positions[startIndex] + (itemHeights[groupedMessages[startIndex].id] ?? DEFAULT_ITEM_HEIGHT) < startBoundary
    ) {
      startIndex += 1
    }

    let endIndex = startIndex
    while (endIndex < groupedMessages.length && positions[endIndex] < endBoundary) {
      endIndex += 1
    }

    return {
      positions,
      totalHeight,
      startIndex: Math.max(0, startIndex),
      endIndex: Math.min(groupedMessages.length, endIndex + 1),
    }
  }, [containerHeight, groupedMessages, itemHeights, listOffsetTop, scrollTop])

  const visibleMessages = useMemo(
    () =>
      groupedMessages.slice(layout.startIndex, layout.endIndex).map((message, offset) => {
        const index = layout.startIndex + offset
        return {
          index,
          message,
          top: layout.positions[index] ?? 0,
        }
      }),
    [groupedMessages, layout.endIndex, layout.positions, layout.startIndex]
  )

  const handleHeightChange = (index: number, height: number) => {
    const message = groupedMessages[index]
    if (!message || height <= 0) {
      return
    }
    setItemHeights((previousHeights) => {
      if (previousHeights[message.id] === height) {
        return previousHeights
      }
      return {
        ...previousHeights,
        [message.id]: height,
      }
    })
  }

  return (
    <div ref={listRef} className="relative w-full" style={{ height: layout.totalHeight }}>
      {visibleMessages.map(({ index, message, top }) => (
        <VirtualItemRow
          key={message.id}
          index={index}
          message={message}
          top={top}
          agentName={agentName}
          formatMessageTime={formatMessageTime}
          onHeightChange={handleHeightChange}
        />
      ))}
    </div>
  )
}

function groupConsecutiveToolMessages(messages: Message[]): Message[] {
  if (messages.length <= 1) {
    return messages
  }

  const grouped: Message[] = []
  let index = 0

  while (index < messages.length) {
    const currentMessage = messages[index]
    if (!isToolCallMessage(currentMessage)) {
      grouped.push(currentMessage)
      index += 1
      continue
    }

    const toolGroup: Message[] = [currentMessage]
    let cursor = index + 1
    while (cursor < messages.length && isToolCallMessage(messages[cursor])) {
      toolGroup.push(messages[cursor])
      cursor += 1
    }

    grouped.push(toolGroup.length === 1 ? currentMessage : buildGroupedToolMessage(toolGroup))
    index = cursor
  }

  return grouped
}

function isToolCallMessage(message?: Message): boolean {
  return message?.role === "assistant" && message.type === MessageType.TOOL_CALL
}

function buildGroupedToolMessage(messages: Message[]): Message {
  const toolCalls = deduplicateLimitExceededToolCalls(
    messages.flatMap((message) => extractToolCallsFromPayload(message))
  )
  const firstMessage = messages[0]
  const lastMessage = messages[messages.length - 1]

  return {
    ...firstMessage,
    id: `tool-group:${firstMessage.id}:${messages.length}:${lastMessage.id}`,
    content: buildGroupedToolContent(toolCalls),
    payload: JSON.stringify({ toolCalls }),
    isStreaming: messages.some((message) => message.isStreaming),
    createdAt: firstMessage.createdAt || lastMessage.createdAt,
    updatedAt: lastMessage.updatedAt || lastMessage.createdAt,
  }
}

function extractToolCallsFromPayload(message: Message): ToolCallPayloadItem[] {
  const fallbackName = extractToolNameFromContent(message.content)
  if (!message.payload) {
    return fallbackName ? [{ name: fallbackName }] : []
  }

  try {
    const parsed = JSON.parse(message.payload) as {
      name?: string
      arguments?: string
      result?: string
      success?: boolean | string
      durationMs?: number | string
      errorCode?: string
      errorMessage?: string
      errorCategory?: string
      toolCalls?: ToolCallPayloadItem[]
    }

    if (Array.isArray(parsed.toolCalls) && parsed.toolCalls.length > 0) {
      return parsed.toolCalls.map((toolCall) => ({
        ...toolCall,
        name: toolCall.name || fallbackName,
      }))
    }

    return [
      {
        name: parsed.name || fallbackName,
        arguments: parsed.arguments,
        result: parsed.result,
        success: parsed.success,
        durationMs: parsed.durationMs,
        errorCode: parsed.errorCode,
        errorMessage: parsed.errorMessage,
        errorCategory: parsed.errorCategory,
      },
    ]
  } catch {
    return [
      {
        name: fallbackName,
        result: message.payload,
      },
    ]
  }
}

function buildGroupedToolContent(toolCalls: ToolCallPayloadItem[]): string {
  if (toolCalls.length === 0) {
    return "工具调用"
  }

  const limitExceededCount = toolCalls.filter(isToolCallLimitExceeded).length
  if (limitExceededCount > 0 && toolCalls.length === limitExceededCount) {
    return "工具调用：本轮调用次数已达上限，已停止继续调用工具。"
  }

  const lines = toolCalls.map((toolCall, index) => {
    const toolName = toolCall.name || `工具 ${index + 1}`
    const status = resolveToolStatus(toolCall.success, toolCall.errorCode, toolCall.errorCategory)
    return `- ${toolName}（${status}）`
  })

  return `工具调用：\n${lines.join("\n")}`
}

function extractToolNameFromContent(content?: string): string {
  if (!content) {
    return ""
  }
  const match = content.match(/执行工具[:：]\s*(.+)/)
  return match?.[1]?.trim() || ""
}

function resolveToolStatus(success?: boolean | string, errorCode?: string, errorCategory?: string): string {
  if (errorCode === TOOL_CALL_LIMIT_EXCEEDED_CODE || errorCategory === TOOL_CALL_LIMIT_EXCEEDED_CODE) {
    return "已达上限"
  }
  const resolvedSuccess =
    typeof success === "boolean"
      ? success
      : success === "true"
        ? true
        : success === "false"
          ? false
          : undefined

  if (resolvedSuccess === true) {
    return "成功"
  }
  if (resolvedSuccess === false || errorCategory) {
    return "失败"
  }
  return "进行中"
}

function deduplicateLimitExceededToolCalls(toolCalls: ToolCallPayloadItem[]): ToolCallPayloadItem[] {
  if (toolCalls.length <= 1) {
    return toolCalls
  }
  const deduplicated: ToolCallPayloadItem[] = []
  let seenLimitExceeded = false
  for (const toolCall of toolCalls) {
    if (isToolCallLimitExceeded(toolCall)) {
      if (seenLimitExceeded) {
        continue
      }
      seenLimitExceeded = true
    }
    deduplicated.push(toolCall)
  }
  return deduplicated
}

function isToolCallLimitExceeded(toolCall?: ToolCallPayloadItem): boolean {
  if (!toolCall) {
    return false
  }
  if (toolCall.errorCode === TOOL_CALL_LIMIT_EXCEEDED_CODE || toolCall.errorCategory === TOOL_CALL_LIMIT_EXCEEDED_CODE) {
    return true
  }
  const mergedText = `${toolCall.errorMessage || ""} ${toolCall.result || ""}`.toLowerCase()
  return mergedText.includes("工具调用次数已达到上限") || mergedText.includes("工具调用次数已达上限")
}
