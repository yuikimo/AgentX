"use client"

import { useCallback, useEffect, useRef, useState } from "react"
import { toast } from "@/hooks/use-toast"
import { getSessionMessagesPage, type MessageDTO } from "@/lib/session-message-service"
import { MessageType, type Message } from "@/types/conversation"

const MESSAGE_PAGE_SIZE = 30

const mapSessionMessage = (msg: MessageDTO): Message => {
  const normalizedRole = msg.role === "SYSTEM" ? "assistant" : msg.role as "USER" | "SYSTEM" | "assistant"

  let messageType = MessageType.TEXT
  if (msg.messageType) {
    messageType = msg.messageType as MessageType
  }

  return {
    id: msg.id,
    role: normalizedRole,
    content: msg.content,
    isError: msg.messageType?.toString().toUpperCase() === "ERROR",
    type: messageType,
    payload: msg.payload,
    createdAt: msg.createdAt,
    updatedAt: msg.updatedAt,
    fileUrls: msg.fileUrls || [],
    attachments: msg.attachments || [],
  }
}

const toChronologicalMessages = (records: MessageDTO[]) =>
  [...records].reverse().map(mapSessionMessage)

const prependDistinctMessages = (
  previousMessages: Message[],
  olderMessages: Message[]
) => {
  const existingIds = new Set(previousMessages.map(message => message.id))
  const uniqueOlderMessages = olderMessages.filter(message => !existingIds.has(message.id))
  return [...uniqueOlderMessages, ...previousMessages]
}

export function useConversationMessages(conversationId: string) {
  const [messages, setMessages] = useState<Message[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [messagePage, setMessagePage] = useState(1)
  const [hasMoreHistory, setHasMoreHistory] = useState(false)
  const [isLoadingHistory, setIsLoadingHistory] = useState(false)
  const historyScrollRestoreRef = useRef<{ scrollHeight: number; scrollTop: number } | null>(null)

  const fetchLatestMessages = useCallback(async () => {
    if (!conversationId) return

    try {
      setLoading(true)
      setError(null)
      setMessages([])

      const messagesResponse = await getSessionMessagesPage(conversationId, {
        page: 1,
        pageSize: MESSAGE_PAGE_SIZE,
      })

      if (messagesResponse.code === 200 && messagesResponse.data) {
        const pageData = messagesResponse.data
        setMessages(toChronologicalMessages(pageData.records || []))
        setMessagePage(pageData.current || 1)
        setHasMoreHistory((pageData.current || 1) < (pageData.pages || 0))
        return
      }

      setError(messagesResponse.message || "获取会话消息失败")
    } catch (fetchError) {
      setError(fetchError instanceof Error ? fetchError.message : "获取会话消息时发生未知错误")
    } finally {
      setLoading(false)
    }
  }, [conversationId])

  const loadOlderMessages = useCallback(async (chatContainer: HTMLDivElement | null) => {
    if (!conversationId || loading || isLoadingHistory || !hasMoreHistory || !chatContainer) {
      return
    }

    const nextPage = messagePage + 1
    const scrollSnapshot = {
      scrollHeight: chatContainer.scrollHeight,
      scrollTop: chatContainer.scrollTop,
    }

    try {
      setIsLoadingHistory(true)
      const messagesResponse = await getSessionMessagesPage(conversationId, {
        page: nextPage,
        pageSize: MESSAGE_PAGE_SIZE,
      })

      if (messagesResponse.code !== 200 || !messagesResponse.data) {
        toast({
          title: "加载历史消息失败",
          description: messagesResponse.message || "请稍后再试",
          variant: "destructive",
        })
        return
      }

      const pageData = messagesResponse.data
      const olderMessages = toChronologicalMessages(pageData.records || [])
      historyScrollRestoreRef.current = scrollSnapshot
      setMessages(previousMessages => prependDistinctMessages(previousMessages, olderMessages))
      setMessagePage(pageData.current || nextPage)
      setHasMoreHistory((pageData.current || nextPage) < (pageData.pages || 0))
    } catch (loadError) {
      toast({
        title: "加载历史消息失败",
        description: loadError instanceof Error ? loadError.message : "请稍后再试",
        variant: "destructive",
      })
    } finally {
      setIsLoadingHistory(false)
    }
  }, [conversationId, hasMoreHistory, isLoadingHistory, loading, messagePage])

  const restoreHistoryScrollPosition = useCallback((chatContainer: HTMLDivElement | null) => {
    const restore = historyScrollRestoreRef.current
    if (!restore || !chatContainer) {
      return
    }

    const nextScrollTop = chatContainer.scrollHeight - restore.scrollHeight + restore.scrollTop
    chatContainer.scrollTop = nextScrollTop
    historyScrollRestoreRef.current = null
  }, [])

  useEffect(() => {
    historyScrollRestoreRef.current = null
    setMessagePage(1)
    setHasMoreHistory(false)
    setIsLoadingHistory(false)
  }, [conversationId])

  useEffect(() => {
    void fetchLatestMessages()
  }, [fetchLatestMessages])

  return {
    messages,
    setMessages,
    loading,
    error,
    isLoadingHistory,
    loadOlderMessages,
    restoreHistoryScrollPosition,
  }
}
