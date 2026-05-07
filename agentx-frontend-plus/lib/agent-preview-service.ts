import { API_CONFIG } from "@/lib/api-config"
import type { ConversationAttachment } from "@/types/conversation"
import { splitSseBlocks, extractSseData } from "@/lib/sse"

// 预览请求类型
export interface AgentPreviewRequest {
  userMessage: string
  systemPrompt?: string
  toolIds?: string[]
  toolPresetParams?: Record<string, Record<string, Record<string, string>>>
  messageHistory?: MessageHistoryItem[]
  modelId?: string // 可选，不传则使用用户默认模型
  multiModal?: boolean
  fileUrls?: string[] // 新增：文件URL列表
  attachments?: ConversationAttachment[]
  knowledgeBaseIds?: string[] // 新增：知识库ID列表，用于RAG功能
}

// 消息历史项
export interface MessageHistoryItem {
  id?: string
  role: 'USER' | 'ASSISTANT' | 'SYSTEM'
  content: string
  createdAt?: string
  fileUrls?: string[] // 新增：文件URL列表
  attachments?: ConversationAttachment[]
}

// 聊天响应类型（流式）- 扩展支持更多消息类型
export interface AgentChatResponse {
  content: string
  done: boolean
  messageType?: string // 支持所有消息类型字符串
  taskId?: string
  payload?: string
  errorCode?: string
  userMessage?: string
  timestamp: number
  tasks?: any[]
  sessionId?: string
  provider?: string
  model?: string
  files?: string[]
}

/**
 * 使用 fetch 方式发送预览请求（返回 ReadableStream）
 * 这是推荐的方式，支持流式响应
 */
export async function previewAgentStream(request: AgentPreviewRequest, signal?: AbortSignal): Promise<ReadableStream<Uint8Array> | null> {
  try {
    const url = `${API_CONFIG.BASE_URL}/agents/sessions/preview`
    
    const response = await fetch(url, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        // 添加认证header，如果有token的话
        ...(typeof window !== 'undefined' && localStorage.getItem('auth_token') 
          ? { 'Authorization': `Bearer ${localStorage.getItem('auth_token')}` }
          : {}
        )
      },
      body: JSON.stringify(request),
      credentials: 'include',
      signal // 添加AbortSignal支持
    })

    if (!response.ok) {
      const errorData = await response.json()
      throw new Error(errorData.message || `HTTP error! status: ${response.status}`)
    }

    return response.body
  } catch (error) {
 
    throw error
  }
}

/**
 * 解析流式响应数据 - 与ChatPanel保持一致的解析逻辑
 */
export function parseStreamData(line: string): AgentChatResponse | null {
  try {
    const jsonStr = extractSseData(line)
    if (!jsonStr) {
      return null
    }
    if (jsonStr === '[DONE]') {
      return { content: '', done: true, messageType: 'TEXT', timestamp: Date.now() }
    }
    return JSON.parse(jsonStr) as AgentChatResponse
  } catch (error) {
 
    return null
  }
}

/**
 * 创建流式文本解码器 - 与ChatPanel保持一致的解码逻辑
 */
export function createStreamDecoder(): {
  decode: (chunk: Uint8Array) => string[]
} {
  const decoder = new TextDecoder()
  let buffer = ''

  return {
    decode: (chunk: Uint8Array): string[] => {
      // 解码数据块并添加到缓冲区
      const newText = decoder.decode(chunk, { stream: true });
      buffer += newText;
      
 
 
      
      // 按双换行符分割SSE数据块
      const blocks = buffer.split('\n\n');
      // 保留最后一个可能不完整的块
      buffer = blocks.pop() || '';
      
 
 
      
      // 返回完整的数据块，并过滤空块
      return blocks.filter(block => block.trim() !== '');
    }
  }
}

/**
 * 处理预览响应流 - 与chat-panel保持一致的流处理逻辑
 */
export async function handlePreviewStream(
  stream: ReadableStream<Uint8Array>, 
  onData: (response: AgentChatResponse) => void,
  onError?: (error: Error) => void,
  onComplete?: () => void
): Promise<void> {
  const reader = stream.getReader()
  const decoder = new TextDecoder()
  let buffer = ''

  try {
    while (true) {
      const { done, value } = await reader.read()
      
      if (done) {
 
        onComplete?.()
        break
      }

      if (value) {
        // 解码数据块并添加到缓冲区
        buffer += decoder.decode(value, { stream: true })
        
        const { blocks, rest } = splitSseBlocks(buffer)
        buffer = rest
        
        for (const block of blocks) {
          const jsonStr = extractSseData(block)
          if (jsonStr) {
            try {
              const data = JSON.parse(jsonStr) as AgentChatResponse
              onData(data)
            } catch (e) {
 
            }
          }
        }
      }
    }
  } catch (error) {
 
    onError?.(error as Error)
  } finally {
    reader.releaseLock()
  }
}

/**
 * 简化的预览函数 - 保持向后兼容，但现在支持所有消息类型 
 */
export async function previewAgent(
  request: AgentPreviewRequest,
  onMessage: (content: string) => void,
  onComplete: (fullContent: string) => void,
  onError?: (error: Error) => void
): Promise<void> {
  try {
    const stream = await previewAgentStream(request)
    if (!stream) {
      throw new Error('Failed to get preview stream')
    }

    let fullContent = ''

    await handlePreviewStream(
      stream,
      (response) => {
        // 处理可显示的消息类型内容
        const displayableTypes = [undefined, "TEXT", "TOOL_CALL", "TOOL_NOTICE"]
        const isDisplayableType = displayableTypes.includes(response.messageType)
        
        if (isDisplayableType && response.content) {
          fullContent += response.content
          onMessage(response.content) // 发送增量内容给UI
        }
      },
      onError,
      () => onComplete(fullContent) // 发送完整内容给UI
    )
  } catch (error) {
    onError?.(error as Error)
  }
} 
