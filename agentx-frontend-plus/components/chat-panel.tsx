"use client"

import { useState, useRef, useEffect, useCallback } from "react"
import { Send, Clock, Square } from 'lucide-react'
import { Button } from "@/components/ui/button"
import { Textarea } from "@/components/ui/textarea"
import { streamChat } from "@/lib/api"
import { toast } from "@/hooks/use-toast"
import { AgentSessionService } from "@/lib/agent-session-service"
import { MessageType, type ConversationAttachment } from "@/types/conversation"
import { nanoid } from 'nanoid'
import MultiModalUpload, { type ChatFile } from "@/components/multi-modal-upload"
import { createAssistantStreamAccumulator } from "@/lib/assistant-stream-accumulator"
import { splitSseBlocks, extractSseData } from "@/lib/sse"
import { VirtualizedChatMessageList } from "@/components/chat/virtualized-chat-message-list"
import { useConversationMessages } from "@/hooks/chat/use-conversation-messages"
import { useChatScroll } from "@/hooks/chat/use-chat-scroll"
import { useWorkspace } from "@/contexts/workspace-context"

interface ChatPanelProps {
  conversationId: string
  isFunctionalAgent?: boolean
  agentName?: string

  onToggleScheduledTaskPanel?: () => void // 新增：切换定时任务面板的回调
  multiModal?: boolean
}

interface AssistantMessage {
  id: string
  hasContent: boolean
}

interface StreamData {
  content: string
  done: boolean
  sessionId: string
  provider?: string
  model?: string
  timestamp: number
  messageType?: string // 消息类型
  payload?: string
  errorCode?: string
  userMessage?: string
  files?: string[] // 新增：文件URL列表
}

interface PendingAssistantMessageUpdate {
  content: string
  type: MessageType
  payload?: string
}

const toAttachmentKind = (contentType: string): ConversationAttachment["kind"] => {
  if (contentType.startsWith("image/")) return "IMAGE"
  if (contentType.startsWith("text/")) return "TEXT"
  if (
    contentType.includes("pdf") ||
    contentType.includes("word") ||
    contentType.includes("officedocument")
  ) return "DOCUMENT"
  return "OTHER"
}

const chatFilesToAttachments = (files: ChatFile[]): ConversationAttachment[] =>
  files
    .filter(file => file.url)
    .map(file => ({
      url: file.url,
      name: file.name,
      contentType: file.type,
      kind: toAttachmentKind(file.type),
    }))

export function ChatPanel({ conversationId, isFunctionalAgent = false, agentName = "AI助手", onToggleScheduledTaskPanel, multiModal = false }: ChatPanelProps) {
  const { refreshWorkspace } = useWorkspace()
  const [input, setInput] = useState("")
  const [isTyping, setIsTyping] = useState(false)
  const [isThinking, setIsThinking] = useState(false)
  const [currentAssistantMessage, setCurrentAssistantMessage] = useState<AssistantMessage | null>(null)
  const [uploadedFiles, setUploadedFiles] = useState<ChatFile[]>([]) // 新增：已上传的文件列表
  const [isInterrupting, setIsInterrupting] = useState(false) // 新增：中断状态
  const [canInterrupt, setCanInterrupt] = useState(false) // 新增：是否可以中断

  const chatContainerRef = useRef<HTMLDivElement>(null)
  const abortControllerRef = useRef<AbortController | null>(null) // 新增：中断控制器
  const sessionSwitchScrollTimeoutRef = useRef<number | null>(null)
  const hasEmittedErrorMessage = useRef(false)
  const assistantStreamAccumulator = useRef(
    createAssistantStreamAccumulator({
      displayableMessageTypes: [undefined, "TEXT", "TOOL_CALL", "TOOL_NOTICE"],
    })
  )
  const pendingAssistantMessageUpdatesRef = useRef<Map<string, PendingAssistantMessageUpdate>>(new Map())
  const messageFlushFrameRef = useRef<number | null>(null)
  const {
    messages,
    setMessages,
    loading,
    error,
    isLoadingHistory,
    loadOlderMessages,
    restoreHistoryScrollPosition,
  } = useConversationMessages(conversationId)
  const { autoScroll, setAutoScroll, scrollToBottom: scrollToBottomBase } = useChatScroll({
    containerRef: chatContainerRef,
    onReachTop: () => {
      void loadOlderMessages(chatContainerRef.current)
    },
  })

  const applyAssistantMessageUpdates = useCallback((updates: Array<[string, PendingAssistantMessageUpdate]>) => {
    if (updates.length === 0) return

    setMessages(prev => {
      const next = [...prev]
      const indexById = new Map(next.map((message, index) => [message.id, index]))

      for (const [messageId, messageData] of updates) {
        const messageIndex = indexById.get(messageId)

        if (messageIndex !== undefined) {
          next[messageIndex] = {
            ...next[messageIndex],
            content: messageData.content,
            payload: messageData.payload || next[messageIndex].payload,
            isStreaming: true,
          }
          continue
        }

        indexById.set(messageId, next.length)
        next.push({
          id: messageId,
          role: "assistant",
          content: messageData.content,
          type: messageData.type,
          payload: messageData.payload,
          isStreaming: true,
          createdAt: new Date().toISOString(),
        })
      }

      return next
    })

    const [lastMessageId] = updates[updates.length - 1]
    setCurrentAssistantMessage({ id: lastMessageId, hasContent: true })
  }, [])

  const flushPendingAssistantMessages = useCallback(() => {
    if (messageFlushFrameRef.current !== null) {
      cancelAnimationFrame(messageFlushFrameRef.current)
      messageFlushFrameRef.current = null
    }

    const updates = Array.from(pendingAssistantMessageUpdatesRef.current.entries())
    pendingAssistantMessageUpdatesRef.current.clear()
    applyAssistantMessageUpdates(updates)
  }, [applyAssistantMessageUpdates])

  const scheduleAssistantMessageUpdate = useCallback((messageId: string, messageData: PendingAssistantMessageUpdate) => {
    pendingAssistantMessageUpdatesRef.current.set(messageId, messageData)

    if (messageFlushFrameRef.current !== null) {
      return
    }

    messageFlushFrameRef.current = requestAnimationFrame(() => {
      messageFlushFrameRef.current = null
      const updates = Array.from(pendingAssistantMessageUpdatesRef.current.entries())
      pendingAssistantMessageUpdatesRef.current.clear()
      applyAssistantMessageUpdates(updates)
    })
  }, [applyAssistantMessageUpdates])

  // 在组件初始化和conversationId变更时重置状态
  useEffect(() => {
    if (abortControllerRef.current) {
      abortControllerRef.current.abort()
      abortControllerRef.current = null
    }
    if (messageFlushFrameRef.current !== null) {
      cancelAnimationFrame(messageFlushFrameRef.current)
      messageFlushFrameRef.current = null
    }
    pendingAssistantMessageUpdatesRef.current.clear()

    assistantStreamAccumulator.current.resetAll()
    
    // 重置中断相关状态
    setCanInterrupt(false);
    setIsInterrupting(false);
    setIsTyping(false)
    setIsThinking(false)
    setAutoScroll(true)
    hasEmittedErrorMessage.current = false
  }, [conversationId]);

  useEffect(() => {
    return () => {
      if (abortControllerRef.current) {
        abortControllerRef.current.abort()
      }
      if (sessionSwitchScrollTimeoutRef.current !== null) {
        window.clearTimeout(sessionSwitchScrollTimeoutRef.current)
        sessionSwitchScrollTimeoutRef.current = null
      }
      if (messageFlushFrameRef.current !== null) {
        cancelAnimationFrame(messageFlushFrameRef.current)
        messageFlushFrameRef.current = null
      }
      pendingAssistantMessageUpdatesRef.current.clear()
    }
  }, [])

  useEffect(() => {
    const chatContainer = chatContainerRef.current
    if (!chatContainer) {
      return
    }

    const frameId = requestAnimationFrame(() => {
      restoreHistoryScrollPosition(chatContainer)
    })

    return () => cancelAnimationFrame(frameId)
  }, [messages, restoreHistoryScrollPosition])

  const forceScrollToBottomAfterSessionSwitch = useCallback(() => {
    const chatContainer = chatContainerRef.current
    if (!chatContainer) {
      return
    }

    setAutoScroll(true)

    const performScroll = (remainingFrames: number) => {
      const activeContainer = chatContainerRef.current
      if (!activeContainer) {
        return
      }
      activeContainer.scrollTop = activeContainer.scrollHeight
      if (remainingFrames <= 0) {
        return
      }
      requestAnimationFrame(() => performScroll(remainingFrames - 1))
    }

    performScroll(4)

    if (sessionSwitchScrollTimeoutRef.current !== null) {
      window.clearTimeout(sessionSwitchScrollTimeoutRef.current)
    }
    sessionSwitchScrollTimeoutRef.current = window.setTimeout(() => {
      const activeContainer = chatContainerRef.current
      if (activeContainer) {
        activeContainer.scrollTop = activeContainer.scrollHeight
      }
      sessionSwitchScrollTimeoutRef.current = null
    }, 180)
  }, [setAutoScroll])

  useEffect(() => {
    if (loading) {
      return
    }
    forceScrollToBottomAfterSessionSwitch()
  }, [conversationId, loading, forceScrollToBottomAfterSessionSwitch])

  // 滚动到底部
  useEffect(() => {
    if (autoScroll) {
      scrollToBottomBase(isTyping ? "auto" : "smooth")
    }
  }, [messages, isTyping, autoScroll, scrollToBottomBase])

  // 处理对话中断
  const handleInterrupt = async () => {
    if (!conversationId || !canInterrupt || isInterrupting) {
      return
    }

    setIsInterrupting(true)
    
    try {
      // 1. 取消当前的网络请求
      if (abortControllerRef.current) {
        abortControllerRef.current.abort()
        abortControllerRef.current = null
      }

      // 2. 调用AgentSessionService中断接口
      const response = await AgentSessionService.interruptSession(conversationId)
      
      if (response.code === 200) {
        toast({
          title: "对话已中断",
          variant: "default"
        })
      } else {
        throw new Error(response.message || "中断失败")
      }
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : "中断对话失败"
 
      toast({
        title: "中断失败",
        description: errorMessage,
        variant: "destructive"
      })
    } finally {
      setIsInterrupting(false)
      setCanInterrupt(false)
      setIsTyping(false)
      setIsThinking(false)
    }
  }

  // 处理用户主动发送消息时强制滚动到底部
  const scrollToBottom = () => {
    window.setTimeout(() => {
      scrollToBottomBase("smooth")
    }, 100)
  }

  // 处理发送消息
  const handleSendMessage = async () => {
    if (!input.trim() && uploadedFiles.length === 0) return

    // 添加调试信息
 
    
    // 获取已完成上传的文件URL
    const completedFiles = multiModal ? uploadedFiles.filter(file => file.url && file.uploadProgress === 100) : []
    const fileUrls = completedFiles.map(file => file.url)
    const attachments = chatFilesToAttachments(completedFiles)

    const userMessage = input.trim()
    setInput("")
    setUploadedFiles([]) // 清空已上传的文件
    setIsTyping(true)
    setIsThinking(true) // 设置思考状态
    setCurrentAssistantMessage(null) // 重置助手消息状态
    setCanInterrupt(true) // 启用中断功能
    setIsInterrupting(false) // 重置中断状态
    scrollToBottom() // 用户发送新消息时强制滚动到底部
    
    // 创建新的AbortController
    const currentAbortController = new AbortController()
    abortControllerRef.current = currentAbortController
    
    // 重置所有状态
    assistantStreamAccumulator.current.resetAll()
    hasEmittedErrorMessage.current = false

    // 输出文件URL到控制台
    if (fileUrls.length > 0) {
 
    }

    // 添加用户消息到消息列表
    const userMessageId = `user-${nanoid()}`
    setMessages((prev) => [
      ...prev,
      {
        id: userMessageId,
        role: "USER",
        content: userMessage,
        type: MessageType.TEXT,
        createdAt: new Date().toISOString(),
        fileUrls: fileUrls.length > 0 ? fileUrls : undefined,
        attachments: attachments.length > 0 ? attachments : undefined
      },
    ])

    try {
      // 发送消息到服务器并获取流式响应，包含文件URL
      const response = await streamChat(
        userMessage,
        conversationId,
        attachments.length > 0 ? attachments : undefined,
        currentAbortController.signal
      )

      // 检查响应状态，如果不是成功状态，则关闭思考状态并返回
      if (!response.ok) {
        // 错误已在streamChat中处理并显示toast
        setIsTyping(false)
        setIsThinking(false) // 关闭思考状态，修复动画一直显示的问题
        return // 直接返回，不继续处理
      }

      const reader = response.body?.getReader()
      if (!reader) {
        throw new Error("No reader available")
      }

      // 生成基础消息ID，作为所有消息序列的前缀
      const baseMessageId = nanoid()
      
      // 重置状态
      assistantStreamAccumulator.current.resetAll()
      
      const decoder = new TextDecoder()
      let buffer = ""

      while (true) {
        // 检查是否被中断
        if (abortControllerRef.current?.signal.aborted) {
 
          break
        }
        
        const { done, value } = await reader.read()
        if (done) break

        // 解码数据块并添加到缓冲区
        buffer += decoder.decode(value, { stream: true })
        
        // 处理缓冲区中的SSE数据
        const { blocks, rest } = splitSseBlocks(buffer)
        buffer = rest
        
        for (const block of blocks) {
          const dataPayload = extractSseData(block)
          if (dataPayload) {
            try {
              const data = JSON.parse(dataPayload) as StreamData
 
              
              // 处理消息 - 传递baseMessageId作为前缀
              handleStreamDataMessage(data, baseMessageId);
            } catch (e) {
 
            }
          }
        }
      }
    } catch (error) {
 
      
      // 如果是中断导致的错误，不显示错误提示
      if (error instanceof Error && error.name === 'AbortError') {
 
      } else {
        setIsThinking(false) // 错误发生时关闭思考状态
        toast({
          title: "发送消息失败",
          description: error instanceof Error ? error.message : "未知错误",
          variant: "destructive",
        })
      }
    } finally {
      flushPendingAssistantMessages()
      setIsTyping(false)
      setCanInterrupt(false) // 重置中断状态
      setIsInterrupting(false)
      if (abortControllerRef.current) {
        abortControllerRef.current = null
      }
      refreshWorkspace()
      window.setTimeout(() => {
        refreshWorkspace()
      }, 1200)
    }
  }

  // 消息处理主函数 - 完全重构
  const handleStreamDataMessage = (data: StreamData, baseMessageId: string) => {
    // 处理错误消息
    if (isErrorMessage(data)) {
      handleErrorMessage(data);
      return;
    }

    const events = assistantStreamAccumulator.current.push(data, baseMessageId)
    for (const event of events) {
      if (event.kind === "first_response") {
        setIsThinking(false)
        continue
      }
      if (event.kind === "upsert") {
        updateOrCreateMessageInUI(event.id, {
          content: event.content,
          type: event.type,
          payload: event.payload,
        })
        continue
      }
      if (event.kind === "finalize") {
        finalizeMessage(event.id, {
          content: event.content,
          type: event.type,
          payload: event.payload,
        })
      }
    }
  }
  
  // 更新或创建UI消息
  const updateOrCreateMessageInUI = (messageId: string, messageData: {
    content: string;
    type: MessageType;
    payload?: string;
  }) => {
    scheduleAssistantMessageUpdate(messageId, messageData)
  }
  
  // 完成消息处理
  const finalizeMessage = (messageId: string, messageData: {
    content: string;
    type: MessageType;
    payload?: string;
  }) => {
 
    
    // 如果消息内容为空，不处理
    if (!messageData.content || messageData.content.trim() === "") {
 
      return;
    }
    flushPendingAssistantMessages()
    
    // 确保UI已更新到最终状态，使用相同的原子操作模式
    setMessages(prev => {
      // 检查消息是否已存在
      const messageIndex = prev.findIndex(msg => msg.id === messageId);
      
      if (messageIndex >= 0) {
        // 消息已存在，更新内容
 
        const newMessages = [...prev];
        newMessages[messageIndex] = {
          ...newMessages[messageIndex],
          content: messageData.content,
          payload: messageData.payload || newMessages[messageIndex].payload,
          isStreaming: false
        };
        return newMessages;
      } else {
        // 消息不存在，创建新消息
 
        return [
          ...prev,
          {
            id: messageId,
            role: "assistant",
            content: messageData.content,
            type: messageData.type,
            payload: messageData.payload,
            isStreaming: false,
            createdAt: new Date().toISOString()
          }
        ];
      }
    });
    
  }

  // 处理按键事件
  const handleKeyPress = (e: React.KeyboardEvent) => {
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault()
      handleSendMessage()
    }
  }

  // 格式化消息时间
  const formatMessageTime = useCallback((timestamp?: string) => {
    if (!timestamp) return '';
    try {
      const date = new Date(timestamp);
      return date.toLocaleString('zh-CN', {
        hour: '2-digit',
        minute: '2-digit',
        year: 'numeric',
        month: '2-digit',
        day: '2-digit'
      });
    } catch (e) {
      return '';
    }
  }, []);

  // 判断是否为错误消息
  const isErrorMessage = (data: StreamData): boolean => {
    return data.messageType === MessageType.ERROR || data.messageType === "ERROR" || !!data.errorCode;
  };

  const getErrorDisplayMessage = (data: StreamData): string => {
    if (data.userMessage && data.userMessage.trim()) {
      return data.userMessage;
    }
    if (data.content && data.content.trim()) {
      return data.content;
    }
    return "任务执行过程中发生错误，请稍后再试。";
  };

  // 处理错误消息
  const handleErrorMessage = (data: StreamData) => {
    if (hasEmittedErrorMessage.current) {
      return
    }
    hasEmittedErrorMessage.current = true

 
    toast({
      title: "任务执行错误",
      description: getErrorDisplayMessage(data),
      variant: "destructive",
    });

    const errorMessage = getErrorDisplayMessage(data)
    setMessages(prev => [
      ...prev,
      {
        id: `error-${nanoid()}`,
        role: "assistant",
        content: errorMessage,
        isError: true,
        errorCode: data.errorCode,
        userMessage: data.userMessage,
        type: MessageType.TEXT,
        isStreaming: false,
        createdAt: new Date().toISOString(),
      },
    ])
  };

  return (
    <div className="relative flex h-full w-full flex-col overflow-hidden bg-white">
      <div 
        ref={chatContainerRef}
        className="flex-1 overflow-y-auto px-4 pt-3 pb-4 w-full"
      >
        {loading ? (
          // 加载状态
          <div className="flex items-center justify-center h-full w-full">
            <div className="text-center">
              <div className="inline-block animate-spin rounded-full h-8 w-8 border-2 border-gray-200 border-t-blue-500 mb-2"></div>
              <p className="text-gray-500">正在加载消息...</p>
            </div>
          </div>
        ) : (
          <div className="space-y-4 w-full">
            {error && (
              <div className="bg-red-50 border border-red-200 rounded-md p-3 text-sm text-red-600">
                {error}
              </div>
            )}
            
            {/* 消息内容 */}
            <div className="space-y-6 w-full">
              {isLoadingHistory && (
                <div className="flex justify-center">
                  <div className="rounded-full border border-gray-200 bg-gray-50 px-3 py-1 text-xs text-gray-500">
                    正在加载更早的消息...
                  </div>
                </div>
              )}

              {messages.length === 0 ? (
                <div className="flex items-center justify-center h-20 w-full">
                  <p className="text-gray-400">暂无消息，开始发送消息吧</p>
                </div>
              ) : (
                <VirtualizedChatMessageList
                  messages={messages}
                  agentName={agentName}
                  formatMessageTime={formatMessageTime}
                  scrollContainerRef={chatContainerRef}
                />
              )}
              
              {/* 思考中提示 */}
              {isThinking && (!currentAssistantMessage || !currentAssistantMessage.hasContent) && (
                <div className="flex items-start">
                  <div className="h-8 w-8 mr-2 bg-gray-100 rounded-full flex items-center justify-center flex-shrink-0">
                    <div className="text-lg">🤖</div>
                  </div>
                  <div className="max-w-[95%]">
                    <div className="flex items-center mb-1 text-xs text-gray-500">
                      <span className="font-medium">{agentName}</span>
                      <span className="mx-1 text-gray-400">·</span>
                      <span>刚刚</span>
                    </div>
                    <div className="space-y-2 p-3 rounded-lg">
                      <div className="flex space-x-2 items-center">
                        <div className="w-2 h-2 rounded-full bg-blue-500 animate-pulse"></div>
                        <div className="w-2 h-2 rounded-full bg-blue-500 animate-pulse delay-75"></div>
                        <div className="w-2 h-2 rounded-full bg-blue-500 animate-pulse delay-150"></div>
                        <div className="text-sm text-gray-500 animate-pulse">思考中...</div>
                      </div>
                    </div>
                  </div>
                </div>
              )}
              
              {!autoScroll && isTyping && (
                <Button
                  variant="outline"
                  size="sm"
                  className="fixed bottom-20 right-6 rounded-full shadow-md bg-white"
                  onClick={scrollToBottom}
                >
                  <span>↓</span>
                </Button>
              )}
            </div>
          </div>
        )}
      </div>

      {/* 输入框 */}
      <div className="border-t p-2 bg-white">
        {/* 已上传文件显示区域 - 在输入框上方 */}
        {uploadedFiles.length > 0 && (
          <div className="mb-2 px-2">
            <div className="flex flex-wrap gap-2">
              {uploadedFiles.map((file) => (
                <div
                  key={file.id}
                  className="flex items-center gap-2 px-3 py-2 bg-blue-50 rounded-lg text-sm border border-blue-200"
                >
                  <div className="flex-shrink-0 w-5 h-5 bg-blue-100 rounded flex items-center justify-center">
                    {file.type.startsWith('image/') ? (
                      <span className="text-sm">🖼️</span>
                    ) : (
                      <span className="text-sm">📄</span>
                    )}
                  </div>
                  <div className="flex-1 min-w-0">
                    <p className="text-sm font-medium text-gray-900 truncate max-w-32">
                      {file.name}
                    </p>
                    {/* 上传进度条 */}
                    {file.uploadProgress !== undefined && file.uploadProgress < 100 && (
                      <div className="w-full bg-gray-200 rounded-full h-1 mt-1">
                        <div
                          className="bg-blue-600 h-1 rounded-full transition-all duration-300"
                          style={{ width: `${file.uploadProgress}%` }}
                        />
                      </div>
                    )}
                  </div>
                  <button
                    onClick={() => {
                      setUploadedFiles(prev => prev.filter(f => f.id !== file.id))
                    }}
                    className="flex-shrink-0 w-4 h-4 rounded-full bg-red-100 hover:bg-red-200 flex items-center justify-center transition-colors"
                    disabled={isTyping}
                  >
                    <span className="text-xs text-red-600">×</span>
                  </button>
                </div>
              ))}
            </div>
          </div>
        )}
        
        {/* 输入框和按钮区域 */}
        <div className="flex items-end gap-2">
          {/* 多模态文件上传按钮 */}
          <MultiModalUpload
            multiModal={multiModal}
            uploadedFiles={uploadedFiles}
            setUploadedFiles={setUploadedFiles}
            disabled={isTyping}
            className="flex-shrink-0"
            showFileList={false}
          />
          
          {/* 定时任务按钮 */}
          {isFunctionalAgent && (
            <Button
              variant="ghost"
              size="icon"
              className="h-10 w-10 flex-shrink-0"
              onClick={onToggleScheduledTaskPanel}
              title="定时任务"
            >
              <Clock className="h-5 w-5 text-gray-500 hover:text-primary" />
            </Button>
          )}
          
          <Textarea
            placeholder="输入消息...(Shift+Enter换行, Enter发送)"
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={handleKeyPress}
            className="min-h-[56px] flex-1 resize-none overflow-hidden rounded-xl bg-white px-3 py-2 font-normal border-gray-200 shadow-sm focus-visible:ring-2 focus-visible:ring-blue-400 focus-visible:ring-opacity-50"
            rows={Math.min(5, Math.max(2, input.split('\n').length))}
          />
          
          {/* 中断/发送按钮 - 根据状态条件渲染 */}
          {canInterrupt ? (
            <Button 
              onClick={handleInterrupt}
              disabled={isInterrupting}
              className="h-10 w-10 rounded-xl bg-red-500 hover:bg-red-600 shadow-sm flex-shrink-0"
              title="中断对话"
            >
              <Square className="h-5 w-5" />
            </Button>
          ) : (
            <Button 
              onClick={handleSendMessage} 
              disabled={(!input.trim() && uploadedFiles.length === 0) || isTyping} 
              className="h-10 w-10 rounded-xl bg-blue-500 hover:bg-blue-600 shadow-sm flex-shrink-0"
              title="发送消息"
            >
              <Send className="h-5 w-5" />
            </Button>
          )}
        </div>
      </div>
    </div>
  )
}
