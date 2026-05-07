"use client"

import { MessageType } from "@/types/conversation"

export interface StreamChunkLike {
  content?: string
  done?: boolean
  messageType?: string
  payload?: string
}

export type AssistantStreamEvent =
  | { kind: "first_response" }
  | { kind: "upsert"; id: string; type: MessageType; content: string; payload?: string }
  | { kind: "finalize"; id: string; type: MessageType; content: string; payload?: string }

export function createAssistantStreamAccumulator(options: {
  displayableMessageTypes: Array<string | undefined>
}) {
  let hasReceivedFirstResponse = false
  let sequenceNo = 0
  let accumulator: { content: string; type: MessageType; payload?: string } = {
    content: "",
    type: MessageType.TEXT,
    payload: undefined,
  }

  const resetAccumulator = () => {
    accumulator = { content: "", type: MessageType.TEXT, payload: undefined }
  }

  return {
    resetAll: () => {
      hasReceivedFirstResponse = false
      sequenceNo = 0
      resetAccumulator()
    },

    push: (data: StreamChunkLike, baseMessageId: string): AssistantStreamEvent[] => {
      const events: AssistantStreamEvent[] = []

      if (!hasReceivedFirstResponse) {
        hasReceivedFirstResponse = true
        events.push({ kind: "first_response" })
      }

      const messageType = (data.messageType as MessageType) || MessageType.TEXT
      const currentMessageId = `assistant-${messageType}-${baseMessageId}-seq${sequenceNo}`
      const shouldReplaceContent = messageType === MessageType.TOOL_CALL

      const isDisplayableType = options.displayableMessageTypes.includes(data.messageType)
      if (isDisplayableType && data.content) {
        accumulator.content = shouldReplaceContent ? data.content : accumulator.content + data.content
        accumulator.type = messageType
        accumulator.payload = data.payload || accumulator.payload

        events.push({
          kind: "upsert",
          id: currentMessageId,
          type: accumulator.type,
          content: accumulator.content,
          payload: accumulator.payload,
        })
      }

      if (data.done) {
        if (isDisplayableType && accumulator.content) {
          events.push({
            kind: "finalize",
            id: currentMessageId,
            type: accumulator.type,
            content: accumulator.content,
            payload: accumulator.payload,
          })
        }

        resetAccumulator()
        sequenceNo += 1
      }

      return events
    },
  }
}
