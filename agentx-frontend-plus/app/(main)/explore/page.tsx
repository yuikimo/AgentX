"use client"

import { useEffect, useState } from "react"
import { Bot, Search, Plus, Check, ArrowRight } from "lucide-react"
import { useRouter } from "next/navigation"

import { Button } from "@/components/ui/button"
import { Card } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Skeleton } from "@/components/ui/skeleton"
import { toast } from "@/hooks/use-toast"
import { getPublishedAgents, addAgentToWorkspaceWithToast } from "@/lib/agent-service"
import type { AgentVersion } from "@/types/agent"
import { Sidebar } from "@/components/sidebar"
import { useWorkspace } from "@/contexts/workspace-context"

export default function ExplorePage() {
  const router = useRouter()
  const { refreshWorkspace } = useWorkspace()
  const [searchQuery, setSearchQuery] = useState("")
  const [debouncedQuery, setDebouncedQuery] = useState("")
  const [agents, setAgents] = useState<AgentVersion[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [addingAgentId, setAddingAgentId] = useState<string | null>(null)

  // 防抖处理搜索查询
  useEffect(() => {
    const timer = setTimeout(() => {
      setDebouncedQuery(searchQuery)
    }, 500)
    return () => clearTimeout(timer)
  }, [searchQuery])

  // 获取已发布的助理列表
  const fetchAgents = async () => {
    try {
      setLoading(true)
      setError(null)
      const response = await getPublishedAgents(debouncedQuery)
      if (response.code === 200) {
        setAgents(response.data)
      } else {
        setError(response.message)
        toast({
          title: "获取助理列表失败",
          description: response.message,
          variant: "destructive",
        })
      }
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : "未知错误"
      setError(errorMessage)
      toast({
        title: "获取助理列表失败",
        description: errorMessage,
        variant: "destructive",
      })
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    fetchAgents()
  }, [debouncedQuery])

  // 处理添加助理到工作区
  const handleAddToWorkspace = async (agentId: string) => {
    try {
      setAddingAgentId(agentId)
      const response = await addAgentToWorkspaceWithToast(agentId)
      if (response.code === 200) {
        await fetchAgents()
        refreshWorkspace()
      }
    } finally {
      setAddingAgentId(null)
    }
  }

  return (
    <div className="flex h-[calc(100vh-3.5rem)] w-full">
      {/* 左侧边栏 */}
      <Sidebar />

      {/* 右侧内容区域 */}
      <div className="flex-1 overflow-auto">
        <div className="container py-6 px-4">
          {/* 标题区域 */}
          <div className="mb-6 flex items-center justify-between flex-wrap gap-4">
            <div>
              <h1 className="text-2xl font-bold tracking-tight text-blue-600">探索 AgentX 的应用</h1>
              <p className="text-muted-foreground mt-1">发现社区发布的助理，一键添加到你的工作区</p>
            </div>
            <div className="relative w-full md:w-[260px]">
              <Search className="absolute left-2.5 top-2.5 h-4 w-4 text-muted-foreground" />
              <Input
                type="search"
                placeholder="搜索助理名称..."
                className="pl-8"
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
              />
            </div>
          </div>

          {/* 加载状态 */}
          {loading && (
            <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
              {Array.from({ length: 6 }).map((_, index) => (
                <Card key={index} className="p-4">
                  <div className="flex items-start gap-3 mb-3">
                    <Skeleton className="h-12 w-12 rounded-lg" />
                    <div className="flex-1">
                      <Skeleton className="h-5 w-32 mb-1" />
                      <Skeleton className="h-4 w-16" />
                    </div>
                  </div>
                  <Skeleton className="h-4 w-full mb-2" />
                  <Skeleton className="h-4 w-3/4" />
                </Card>
              ))}
            </div>
          )}

          {/* 错误状态 */}
          {!loading && error && (
            <div className="text-center py-16">
              <div className="text-red-500 mb-4">{error}</div>
              <Button variant="outline" onClick={() => fetchAgents()}>
                重试
              </Button>
            </div>
          )}

          {/* 空状态 - 无搜索结果 */}
          {!loading && !error && agents.length === 0 && searchQuery && (
            <div className="text-center py-16 border rounded-lg bg-gray-50">
              <Search className="h-12 w-12 mx-auto text-gray-400 mb-4" />
              <h3 className="text-lg font-medium mb-2">未找到匹配的助理</h3>
              <p className="text-muted-foreground mb-4">尝试使用不同的搜索词</p>
              <Button variant="outline" onClick={() => setSearchQuery("")}>
                清除搜索
              </Button>
            </div>
          )}

          {/* 空状态 - 无已发布助理 */}
          {!loading && !error && agents.length === 0 && !searchQuery && (
            <div className="text-center py-16 border rounded-lg bg-gray-50">
              <Bot className="h-14 w-14 mx-auto text-gray-300 mb-4" />
              <h3 className="text-lg font-medium mb-2">暂无已发布的助理</h3>
              <p className="text-muted-foreground mb-6 max-w-md mx-auto">
                还没有助理被发布到社区。去工作室创建你的第一个助理并发布，让其他用户发现和使用吧。
              </p>
              <Button onClick={() => router.push("/studio")} className="bg-blue-500 hover:bg-blue-600">
                去工作室创建
                <ArrowRight className="h-4 w-4 ml-2" />
              </Button>
            </div>
          )}

          {/* 助理列表 */}
          {!loading && !error && agents.length > 0 && (
            <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
              {agents.map((agent) => (
                <Card key={agent.id} className="group relative">
                  <div className="p-5">
                    <div className="flex items-start gap-3 mb-3">
                      <div className="w-12 h-12 rounded-lg overflow-hidden bg-amber-100 flex items-center justify-center shrink-0">
                        {agent.avatar ? (
                          <img
                            src={agent.avatar || "/placeholder.svg"}
                            alt={agent.name}
                            className="w-full h-full object-cover"
                          />
                        ) : (
                          <Bot className="h-6 w-6 text-amber-500" />
                        )}
                      </div>
                      <div className="min-w-0 flex-1">
                        <h3 className="text-lg font-semibold leading-tight truncate">{agent.name}</h3>
                        <div className="text-xs text-muted-foreground uppercase font-medium mt-1">AGENT</div>
                      </div>
                    </div>
                    <p className="text-sm text-gray-600 line-clamp-3">{agent.description || "暂无描述"}</p>

                    <div className="mt-4 opacity-0 group-hover:opacity-100 transition-opacity">
                      {agent.addWorkspace ? (
                        <Button className="w-full bg-green-500 text-white cursor-default" disabled>
                          <Check className="h-4 w-4 mr-2" />
                          已添加到工作区
                        </Button>
                      ) : (
                        <Button
                          className="w-full bg-blue-500 hover:bg-blue-600 text-white"
                          onClick={() => handleAddToWorkspace(agent.agentId)}
                          disabled={addingAgentId === agent.agentId}
                        >
                          {addingAgentId === agent.agentId ? (
                            "添加中..."
                          ) : (
                            <>
                              <Plus className="h-4 w-4 mr-2" />
                              添加到工作区
                            </>
                          )}
                        </Button>
                      )}
                    </div>
                  </div>
                </Card>
              ))}
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
