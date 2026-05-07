import { API_CONFIG, API_ENDPOINTS } from "@/lib/api-config"
import { StreamResponse } from "@/types/api"
import { toast } from "@/hooks/use-toast"
import type { ConversationAttachment } from "@/types/conversation"
import { splitSseBlocks, extractSseData } from "@/lib/sse"

/**
 * 流式聊天API服务
 * 处理与流式响应相关的API调用
 */

/**
 * 获取认证头
 * @returns 认证头对象
 */
function getAuthHeaders(): HeadersInit {
  const headers: HeadersInit = {
    "Content-Type": "application/json",
    Accept: "text/event-stream",
    "Cache-Control": "no-cache",
  }

  // 添加认证令牌
  if (typeof window !== "undefined") {
    const token = localStorage.getItem("auth_token")
    if (token) {
      headers.Authorization = `Bearer ${token}`
    }
  }

  return headers
}

/**
 * 处理API错误响应
 * @param response 响应对象
 * @returns 返回原始响应
 */
async function handleErrorResponse(response: Response): Promise<Response> {
  let errorMessage = `请求失败 (${response.status})`;
  let responseClone = response.clone();
  
  try {
    // 尝试从响应中提取详细的错误信息
    const contentType = response.headers.get('content-type');
    if (contentType && contentType.includes('application/json')) {
      const errorData = await responseClone.json();
      // 使用服务器返回的错误消息，如果存在的话
      errorMessage = errorData.message || errorData.error || errorMessage;
    }
  } catch (e) {
    // 如果无法解析JSON，使用默认错误消息
 
  }

  
  // 显示错误提示，确保包含状态码和错误消息
  toast({
    title: `错误`,
    description: errorMessage,
    variant: "destructive",
  });

  return response;
}

/**
 * 发送流式聊天消息
 * @param sessionId 会话ID
 * @param message 消息内容
 * @param fileUrls 可选的文件URL列表，用于多模态功能
 * @returns 流式响应
 */
export async function streamChat(
  sessionId: string,
  message: string,
  attachments?: ConversationAttachment[],
  signal?: AbortSignal
): Promise<Response> {
  try {
    // 使用API_ENDPOINTS.CHAT常量
    const url = `${API_CONFIG.BASE_URL}${API_ENDPOINTS.CHAT}`
    
 
    
    // 构建请求体，包含可选的文件URL
    const requestBody: any = {
      sessionId,
      message
    }
    
    // 如果有文件URL，添加到请求体中
    if (attachments && attachments.length > 0) {
      requestBody.attachments = attachments
      requestBody.fileUrls = attachments.map((attachment) => attachment.url).filter(Boolean)
 
    }
    
    const response = await fetch(url, {
      method: "POST",
      headers: getAuthHeaders(),
      body: JSON.stringify(requestBody),
      signal
    })
    
    // 处理非成功响应
    if (!response.ok) {
      return await handleErrorResponse(response);
    }
    
    return response
  } catch (error) {
 
    // 显示网络错误提示
    toast({
      title: "网络错误",
      description: error instanceof Error ? error.message : "网络请求失败",
      variant: "destructive",
    });
    throw error
  }
}

/**
 * 发送结构化消息到Agent
 * @param sessionId 会话ID
 * @param message 消息对象
 * @returns 流式响应
 */
export async function streamSendMessage(sessionId: string, message: any): Promise<Response> {
  try {
    const url = `${API_CONFIG.BASE_URL}${API_ENDPOINTS.SEND_MESSAGE(sessionId)}`
    
 
    
    const response = await fetch(url, {
      method: "POST",
      headers: getAuthHeaders(),
      body: JSON.stringify(message),
    })
    
    // 处理非成功响应
    if (!response.ok) {
      return await handleErrorResponse(response);
    }
    
    return response
  } catch (error) {
 
    // 显示网络错误提示
    toast({
      title: "网络错误",
      description: error instanceof Error ? error.message : "网络请求失败",
      variant: "destructive",
    });
    throw error
  }
}

/**
 * 解析SSE响应
 * @param response 流式响应
 * @param onMessage 消息处理回调
 * @param onError 错误处理回调
 */
export async function parseSSEResponse(
  response: Response,
  onMessage: (data: StreamResponse) => void,
  onError?: (error: any) => void
): Promise<void> {
  try {
    const reader = response.body?.getReader()
    if (!reader) throw new Error("Response body is null")
    
    const decoder = new TextDecoder()
    let buffer = ""
    
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      
      buffer += decoder.decode(value, { stream: true })
      
      const { blocks, rest } = splitSseBlocks(buffer)
      buffer = rest
      
      for (const block of blocks) {
        const dataPayload = extractSseData(block)
        if (dataPayload) {
          try {
            const jsonData = JSON.parse(dataPayload)
            onMessage(jsonData)
          } catch (e) {
 
            if (onError) onError(e)
          }
        }
      }
    }
  } catch (error) {
 
    if (onError) onError(error)
  }
}
