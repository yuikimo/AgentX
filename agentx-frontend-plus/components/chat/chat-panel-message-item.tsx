"use client"

import { memo } from "react"
import { Wrench } from "lucide-react"
import { MessageMarkdown } from "@/components/ui/message-markdown"
import { ToolCallDetails } from "@/components/tool-call-details"
import MessageFileDisplay from "@/components/message-file-display"
import { MessageType, type Message } from "@/types/conversation"

interface ChatPanelMessageItemProps {
  message: Message
  agentName: string
  formatMessageTime: (timestamp?: string) => string
}

function getMessageTypeInfo(type: MessageType, agentName: string) {
  switch (type) {
    case MessageType.TOOL_CALL:
      return {
        icon: <Wrench className="h-5 w-5 text-blue-500" />,
        text: "工具调用",
      }
    case MessageType.TOOL_NOTICE:
      return {
        icon: <Wrench className="h-5 w-5 text-amber-500" />,
        text: "工具提示",
      }
    case MessageType.TEXT:
    default:
      return {
        icon: null,
        text: agentName,
      }
  }
}

export const ChatPanelMessageItem = memo(function ChatPanelMessageItem({
  message,
  agentName,
  formatMessageTime,
}: ChatPanelMessageItemProps) {
  if (message.role === "USER") {
    return (
      <div className="flex justify-end">
        <div className="max-w-[80%]">
          {((message.attachments && message.attachments.length > 0) || (message.fileUrls && message.fileUrls.length > 0)) && (
            <div className="mb-3">
              <MessageFileDisplay fileUrls={message.fileUrls || []} attachments={message.attachments} />
            </div>
          )}

          {message.content && (
            <div className="bg-blue-50 p-3 text-gray-800 rounded-lg shadow-sm">
              {message.content}
            </div>
          )}

          <div className="mt-1 text-right text-xs text-gray-500">
            {formatMessageTime(message.createdAt)}
          </div>
        </div>
      </div>
    )
  }

  const messageTypeInfo = getMessageTypeInfo(message.type || MessageType.TEXT, agentName)

  return (
    <div className="flex">
      <div className="mr-2 h-8 w-8 flex-shrink-0 rounded-full bg-gray-100 flex items-center justify-center">
        {message.type && message.type !== MessageType.TEXT ? messageTypeInfo.icon : <div className="text-lg">🤖</div>}
      </div>
      <div className="max-w-[95%]">
        <div className="mb-1 flex items-center text-xs text-gray-500">
          <span className="font-medium">{messageTypeInfo.text}</span>
          <span className="mx-1 text-gray-400">·</span>
          <span>{formatMessageTime(message.createdAt)}</span>
        </div>

        {((message.attachments && message.attachments.length > 0) || (message.fileUrls && message.fileUrls.length > 0)) && (
          <div className="mb-3">
            <MessageFileDisplay fileUrls={message.fileUrls || []} attachments={message.attachments} />
          </div>
        )}

        {message.content && (
          <div
            className={`p-3 rounded-lg ${
              message.isError
                ? "bg-red-50 text-red-700 border border-red-200"
                : message.type === MessageType.TOOL_NOTICE
                  ? "bg-amber-50 text-amber-800 border border-amber-200"
                  : ""
            }`}
          >
            <MessageMarkdown
              showCopyButton
              content={message.content}
              isStreaming={message.isStreaming}
              isError={message.isError}
            />
            {message.type === MessageType.TOOL_CALL && (
              <ToolCallDetails payload={message.payload} content={message.content} />
            )}
          </div>
        )}
      </div>
    </div>
  )
}, (prevProps, nextProps) => (
  prevProps.agentName === nextProps.agentName &&
  prevProps.formatMessageTime === nextProps.formatMessageTime &&
  prevProps.message.id === nextProps.message.id &&
  prevProps.message.content === nextProps.message.content &&
  prevProps.message.isStreaming === nextProps.message.isStreaming &&
  prevProps.message.isError === nextProps.message.isError &&
  prevProps.message.type === nextProps.message.type &&
  prevProps.message.payload === nextProps.message.payload &&
  prevProps.message.createdAt === nextProps.message.createdAt &&
  prevProps.message.fileUrls === nextProps.message.fileUrls &&
  prevProps.message.attachments === nextProps.message.attachments
))
