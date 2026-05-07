"use client";

import { useEffect, useRef, useState } from "react";
import { MessageSquare } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { ResponsiveDialog } from "@/components/layout/ResponsiveDialog";
import { SplitLayout } from "@/components/layout/SplitLayout";
import { ChatMessageList } from "./ChatMessageList";
import { ChatInputArea } from "./ChatInputArea";
import { FileDetailPanel } from "./FileDetailPanel";
import { useRagChatSession } from "@/hooks/rag-chat/useRagChatSession";
import { useChatLayout } from "@/hooks/rag-chat/useChatLayout";
import { toast } from "@/hooks/use-toast";
import { closeRagSession, createRagSession } from "@/lib/rag-session-service";
import type { RagDataset, RetrievedFileInfo, DocumentSegment } from "@/types/rag-dataset";

interface RagChatDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  dataset: RagDataset;
}

export function RagChatDialog({ open, onOpenChange, dataset }: RagChatDialogProps) {
  const [sessionId, setSessionId] = useState<string | null>(null);
  const [isPreparingSession, setIsPreparingSession] = useState(false);
  const closingSessionRef = useRef<string | null>(null);
  const {
    uiState,
    selectFile,
    selectSegment,
    closeFileDetail,
    setFileDetailData,
    resetState
  } = useChatLayout();

  const {
    messages,
    isLoading,
    sendMessage,
    clearMessages,
    stopGeneration
  } = useRagChatSession({
    onError: (error) => {
      toast({
        title: "对话出错",
        description: error,
        variant: "destructive"
      });
    }
  });

  useEffect(() => {
    if (!open) {
      return;
    }

    let cancelled = false;
    const prepareSession = async () => {
      setIsPreparingSession(true);
      clearMessages();
      resetState();
      try {
        const response = await createRagSession();
        if (!cancelled && response.code === 200 && response.data?.sessionId) {
          setSessionId(response.data.sessionId);
        } else if (!cancelled) {
          toast({
            title: "会话创建失败",
            description: response.message || "无法创建RAG会话",
            variant: "destructive"
          });
          setSessionId(null);
        }
      } catch (error) {
        if (!cancelled) {
          toast({
            title: "会话创建失败",
            description: error instanceof Error ? error.message : "无法创建RAG会话",
            variant: "destructive"
          });
          setSessionId(null);
        }
      } finally {
        if (!cancelled) {
          setIsPreparingSession(false);
        }
      }
    };

    void prepareSession();
    return () => {
      cancelled = true;
    };
  }, [open, dataset.id]);

  useEffect(() => {
    return () => {
      const activeSessionId = closingSessionRef.current || sessionId;
      if (activeSessionId) {
        void closeRagSession(activeSessionId);
      }
    };
  }, [sessionId]);

  // 处理文件点击
  const handleFileClick = (file: RetrievedFileInfo) => {
    selectFile(file);
  };

  // 处理文档片段点击
  const handleSegmentClick = (segment: DocumentSegment) => {
    selectSegment(segment);
  };

  // 处理文件详情数据加载
  const handleFileDetailDataLoad = (data: any) => {
    setFileDetailData(data);
  };

  // 处理发送消息
  const handleSendMessage = async (message: string) => {
    if (!sessionId) {
      toast({
        title: "会话尚未就绪",
        description: "正在初始化RAG会话，请稍后重试",
        variant: "destructive"
      });
      return;
    }
    await sendMessage(message, [dataset.id], sessionId);
  };

  // 处理对话框关闭
  const handleDialogClose = (open: boolean) => {
    if (!open) {
      const activeSessionId = sessionId;
      closingSessionRef.current = activeSessionId;
      resetState();
      clearMessages();
      stopGeneration();
      setSessionId(null);
      if (activeSessionId) {
        void closeRagSession(activeSessionId).finally(() => {
          if (closingSessionRef.current === activeSessionId) {
            closingSessionRef.current = null;
          }
        });
      }
    }
    onOpenChange(open);
  };

  // 处理清空对话
  const handleClearMessages = () => {
    clearMessages();
    closeFileDetail();
  };

  return (
    <ResponsiveDialog
      open={open}
      onOpenChange={handleDialogClose}
      title={
        <div className="flex items-center gap-3">
          <MessageSquare className="h-5 w-5" />
          <span>RAG 智能问答</span>
          <Badge variant="secondary">{dataset.name}</Badge>
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
              onSegmentClick={handleSegmentClick}
              selectedFileId={uiState.selectedFile?.fileId}
              selectedSegmentId={uiState.selectedSegment?.documentId}
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
            selectedSegment={uiState.selectedSegment}
            onDataLoad={handleFileDetailDataLoad}
          />
        }
        showRightPanel={uiState.showFileDetail}
        onCloseRightPanel={closeFileDetail}
      />
    </ResponsiveDialog>
  );
}
