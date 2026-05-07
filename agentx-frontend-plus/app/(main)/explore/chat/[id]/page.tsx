"use client"

import React, { useEffect, useState } from 'react'
import { AgentSidebar } from "@/components/agent-sidebar"
import { ChatPanel } from "@/components/chat-panel"
import { getAgentBySessionId } from "@/lib/agent-service"
import type { Agent } from "@/types/agent"

export default function ChatPage({ params }: { params: { id: string } }) {
  const [currentAgent, setCurrentAgent] = useState<Agent | null>(null)
  const [loading, setLoading] = useState(true)

  // 通过会话ID获取Agent信息
  useEffect(() => {
    const fetchData = async () => {
      try {
        setLoading(true)
        
        // 通过会话ID获取Agent信息
        const agentResponse = await getAgentBySessionId(params.id)
        if (agentResponse.code === 200 && agentResponse.data) {
          setCurrentAgent(agentResponse.data)
        }
        
      } catch (error) {
 
      } finally {
        setLoading(false)
      }
    }

    fetchData()
  }, [params.id])

  const multiModal = Boolean(currentAgent?.multiModal)
  
  return (
    <div className="flex h-[calc(100vh-3.5rem)] w-full">
      {/* 左侧边栏 */}
      <AgentSidebar />

      {/* 右侧聊天面板 */}
      <div className="flex-1 flex flex-col overflow-hidden">
        <ChatPanel 
          conversationId={params.id} 
          agentName={currentAgent?.name || "AI助手"}
          multiModal={multiModal}
        />
      </div>
    </div>
  )
}
