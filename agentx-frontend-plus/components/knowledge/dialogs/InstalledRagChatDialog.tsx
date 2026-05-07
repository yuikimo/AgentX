"use client"

import { useEffect, useRef, useState } from "react"
import { MessageSquare } from "lucide-react"
import { Badge } from "@/components/ui/badge"
import { ResponsiveDialog } from "@/components/layout/ResponsiveDialog"
import { SplitLayout } from "@/components/layout/SplitLayout"
import { ChatMessageList } from "@/components/rag-chat/ChatMessageList"
import { ChatInputArea } from "@/components/rag-chat/ChatInputArea"
import { FileDetailPanel } from "@/components/rag-chat/FileDetailPanel"
import { useUserRagChatSession } from "@/hooks/rag-chat/useUserRagChatSession"
import { useChatLayout } from "@/hooks/rag-chat/useChatLayout"
import { toast } from "@/hooks/use-toast"
import { closeRagSession, createUserRagSession } from "@/lib/rag-session-service"
import type { UserRagDTO, RetrievedFileInfo } from "@/types/rag-dataset"

interface InstalledRagChatDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  userRag: UserRagDTO | null
}

export function InstalledRagChatDialog({ 
  open, 
  onOpenChange, 
  userRag 
}: InstalledRagChatDialogProps) {
  const [sessionId, setSessionId] = useState<string | null>(null)
  const [isPreparingSession, setIsPreparingSession] = useState(false)
  const closingSessionRef = useRef<string | null>(null)
  const {
    uiState,
    selectFile,
    closeFileDetail,
    setFileDetailData,
    resetState
  } = useChatLayout()

  const {
    messages,
    isLoading,
    sendMessage,
    clearMessages,
    stopGeneration
  } = useUserRagChatSession({})

  useEffect(() => {
    if (!open || !userRag?.id) {
      return
    }

    let cancelled = false
    const prepareSession = async () => {
      setIsPreparingSession(true)
      clearMessages()
      resetState()
      try {
        const response = await createUserRagSession(userRag.id)
        if (!cancelled && response.code === 200 && response.data?.sessionId) {
          setSessionId(response.data.sessionId)
        } else if (!cancelled) {
          toast({
            title: "会话创建失败",
            description: response.message || "无法创建RAG会话",
            variant: "destructive"
          })
          setSessionId(null)
        }
      } catch (error) {
        if (!cancelled) {
          toast({
            title: "会话创建失败",
            description: error instanceof Error ? error.message : "无法创建RAG会话",
            variant: "destructive"
          })
          setSessionId(null)
        }
      } finally {
        if (!cancelled) {
          setIsPreparingSession(false)
        }
      }
    }

    void prepareSession()
    return () => {
      cancelled = true
    }
  }, [open, userRag?.id])

  useEffect(() => {
    return () => {
      const activeSessionId = closingSessionRef.current || sessionId
      if (activeSessionId) {
        void closeRagSession(activeSessionId)
      }
    }
  }, [sessionId])

  // 处理文件点击
  const handleFileClick = (file: RetrievedFileInfo) => {
 
    // 为已安装RAG的文件添加必要的标识信息
    const enrichedFile: RetrievedFileInfo = {
      ...file,
      isInstalledRag: true,
      userRagId: userRag?.id || ''
    };
 
    selectFile(enrichedFile)
  }

  // 处理文件详情数据加载
  const handleFileDetailDataLoad = (data: any) => {
    setFileDetailData(data)
  }

  // 处理发送消息 - 使用 userRagId 作为数据源
  const handleSendMessage = async (message: string) => {
    if (!userRag?.id) {
 
      return
    }
    if (!sessionId) {
      toast({
        title: "会话尚未就绪",
        description: "正在初始化RAG会话，请稍后重试",
        variant: "destructive"
      })
      return
    }

    // 使用用户RAG ID，支持快照感知的数据访问
    await sendMessage(message, userRag.id, sessionId)
  }

  // 处理对话框关闭
  const handleDialogClose = (open: boolean) => {
    if (!open) {
      const activeSessionId = sessionId
      closingSessionRef.current = activeSessionId
      resetState()
      clearMessages()
      stopGeneration()
      setSessionId(null)
      if (activeSessionId) {
        void closeRagSession(activeSessionId).finally(() => {
          if (closingSessionRef.current === activeSessionId) {
            closingSessionRef.current = null
          }
        })
      }
    }
    onOpenChange(open)
  }

  // 处理清空对话
  const handleClearMessages = () => {
    clearMessages()
    closeFileDetail()
  }

  if (!userRag) return null

  return (
    <ResponsiveDialog
      open={open}
      onOpenChange={handleDialogClose}
      title={
        <div className="flex items-center gap-3">
          <MessageSquare className="h-5 w-5" />
          <span>RAG 智能问答</span>
          <Badge variant="secondary">{userRag.name}</Badge>
          <Badge variant="outline" className="text-xs">
            v{userRag.version}
          </Badge>
        </div>
      }
      layout={uiState.layout}
    >
      <SplitLayout
        leftPanel={
          <div className="flex flex-col h-full">
            <ChatMessageList
              messages={messages}
              onFileClick={handleFileClick}
              selectedFileId={uiState.selectedFile?.fileId}
              className="flex-1"
            />
            
            <ChatInputArea
              onSend={handleSendMessage}
              onStop={stopGeneration}
              onClear={handleClearMessages}
              isLoading={isLoading || isPreparingSession}
              disabled={!sessionId || isPreparingSession}
              hasMessages={messages.length > 0}
            />
          </div>
        }
        rightPanel={
          <FileDetailPanel
            selectedFile={uiState.selectedFile}
            onDataLoad={handleFileDetailDataLoad}
          />
        }
        showRightPanel={uiState.showFileDetail}
        onCloseRightPanel={closeFileDetail}
      />
    </ResponsiveDialog>
  )
}
