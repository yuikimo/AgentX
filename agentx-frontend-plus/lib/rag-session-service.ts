import { API_ENDPOINTS } from "@/lib/api-config";
import { httpClient } from "@/lib/http-client";
import type { ApiResponse, RagSessionDTO } from "@/types/rag-dataset";

export async function createRagSession(): Promise<ApiResponse<RagSessionDTO>> {
  return httpClient.post<ApiResponse<RagSessionDTO>>(API_ENDPOINTS.RAG_SESSION);
}

export async function createUserRagSession(userRagId: string): Promise<ApiResponse<RagSessionDTO>> {
  return httpClient.post<ApiResponse<RagSessionDTO>>(API_ENDPOINTS.RAG_USER_RAG_SESSION(userRagId));
}

export async function closeRagSession(sessionId: string): Promise<ApiResponse<null>> {
  return httpClient.delete<ApiResponse<null>>(API_ENDPOINTS.RAG_STREAM_CHAT_SESSION(sessionId));
}
