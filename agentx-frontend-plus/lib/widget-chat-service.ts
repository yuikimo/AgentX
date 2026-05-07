import { API_CONFIG } from "@/lib/api-config";
import type { ConversationAttachment } from "@/types/conversation";
import { splitSseBlocks, extractSseData } from "@/lib/sse";

// Widget聊天请求类型
export interface WidgetChatRequest {
  message: string;
  sessionId: string; // 后端要求sessionId必须提供
  fileUrls?: string[];
  attachments?: ConversationAttachment[];
}

// Widget聊天响应类型
export interface WidgetChatResponse {
  content: string;
  done: boolean;
  messageType?: string;
  taskId?: string;
  payload?: string;
  errorCode?: string;
  userMessage?: string;
  toolNotices?: string[];
  timestamp: number;
  tasks?: any[];
  sessionId?: string;
  provider?: string;
  model?: string;
  files?: string[];
}

/**
 * Widget聊天流式请求 - 使用公开HTTP客户端，无需认证
 */
export async function widgetChatStream(publicId: string, request: WidgetChatRequest, signal?: AbortSignal): Promise<ReadableStream<Uint8Array> | null> {
  try {
    const url = `${API_CONFIG.BASE_URL}/widget/${publicId}/chat`;
    
    const response = await fetch(url, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Referer': typeof window !== 'undefined' ? window.location.origin : '',
      },
      body: JSON.stringify(request),
      signal // 添加AbortSignal支持
    });

    if (!response.ok) {
      const errorData = await response.json();
      throw new Error(errorData.message || `HTTP error! status: ${response.status}`);
    }

    return response.body;
  } catch (error) {
 
    throw error;
  }
}

/**
 * 解析Widget流式响应数据
 */
export function parseWidgetStreamData(line: string): WidgetChatResponse | null {
  try {
    const jsonStr = extractSseData(line);
    if (!jsonStr) {
      return null;
    }
    if (jsonStr === '[DONE]') {
      return { content: '', done: true, messageType: 'TEXT', timestamp: Date.now() };
    }
    return JSON.parse(jsonStr) as WidgetChatResponse;
  } catch (error) {
 
  }
  
  return null;
}

/**
 * 处理Widget流式响应
 */
export async function handleWidgetStream(
  stream: ReadableStream<Uint8Array>,
  onData: (data: WidgetChatResponse) => void,
  onError: (error: Error) => void,
  onComplete: () => void
): Promise<void> {
  if (!stream) {
    onError(new Error('No stream provided'));
    return;
  }

  try {
    const reader = stream.getReader();
    const decoder = new TextDecoder();
    let buffer = '';

    while (true) {
      const { done, value } = await reader.read();
      
      if (done) break;
      
      buffer += decoder.decode(value, { stream: true });
      
      const { blocks, rest } = splitSseBlocks(buffer);
      buffer = rest;
      
      for (const block of blocks) {
        const parsed = parseWidgetStreamData(block);
        if (parsed) {
          onData(parsed);
          
          if (parsed.done) {
            onComplete();
            return;
          }
        }
      }
    }
    
    // 处理剩余缓冲区内容
    if (buffer.trim()) {
      const parsed = parseWidgetStreamData(buffer);
      if (parsed) {
        onData(parsed);
      }
    }
    
    onComplete();
  } catch (error) {
 
    onError(error instanceof Error ? error : new Error('Widget stream processing error'));
  }
}
