"use client"

import React, { useEffect, useState } from "react"
import { Button } from "@/components/ui/button"
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { Card, CardContent } from "@/components/ui/card"
import { Badge } from "@/components/ui/badge"
import { Switch } from "@/components/ui/switch"
import { Skeleton } from "@/components/ui/skeleton"

import { useAgentForm, type AgentFormData } from "@/hooks/use-agent-form"
import { useToast } from "@/hooks/use-toast"
import { ModelSelectDialog } from "@/components/model-select-dialog"
import AssistantDefaultModelSelector from "@/components/assistant-default-model-selector"
import AgentBasicInfoForm from "@/app/(main)/studio/edit/[id]/components/AgentBasicInfoForm"
import AgentPromptForm from "@/app/(main)/studio/edit/[id]/components/AgentPromptForm"
import AgentToolsForm from "@/app/(main)/studio/edit/[id]/components/AgentToolsForm"
import ToolDetailSidebar from "@/app/(main)/studio/edit/[id]/components/ToolDetailSidebar"
import KnowledgeBaseDetailSidebar from "@/app/(main)/studio/edit/[id]/components/KnowledgeBaseDetailSidebar"
import AgentPreviewChat from "@/components/agent-preview-chat"
import { AgentWidgetTab } from "@/app/(main)/studio/edit/[id]/components/AgentWidgetTab"

interface AgentFormModalProps {
  // 模式控制
  mode: "create" | "edit"
  
  // 编辑模式属性
  agentId?: string
  initialData?: Partial<AgentFormData>
  
  // 标题和描述
  title?: string
  description?: string
  
  // 操作回调
  onSubmit: (formData: AgentFormData) => Promise<void>
  onCancel: () => void
  
  // 编辑模式特有的操作
  onDelete?: () => void
  onPublish?: () => void
  onShowVersions?: () => void
  
  // 加载状态
  isSubmitting?: boolean
  
  // 其他编辑模式组件（如版本历史对话框等）
  children?: React.ReactNode

  // 编辑模式打开后自动弹出工作区模型配置
  autoOpenWorkspaceModelDialog?: boolean
}

export default function AgentFormModal({
  mode,
  agentId,
  initialData,
  title,
  description,
  onSubmit,
  onCancel,
  onDelete,
  onPublish,
  onShowVersions,
  isSubmitting: externalIsSubmitting = false,
  children,
  autoOpenWorkspaceModelDialog = false,
}: AgentFormModalProps) {
  const {
    // 基础状态
    activeTab,
    setActiveTab,
    isSubmitting: internalIsSubmitting,
    
    // 工具相关状态
    selectedToolForSidebar,
    isToolSidebarOpen,
    setIsToolSidebarOpen,
    installedTools, // 已安装工具列表，用于过滤有效工具ID
    
    // 知识库相关状态
    selectedKnowledgeBaseForSidebar,
    isKnowledgeBaseSidebarOpen,
    setIsKnowledgeBaseSidebarOpen,
    
    // 表单数据
    formData,
    updateFormField,
    
    // 表单操作函数
    toggleTool,
    toggleKnowledgeBase,
    handleToolClick,
    handleKnowledgeBaseClick,
    updateToolPresetParameters,
    
    // 工具函数
    getAvailableTabs,
  } = useAgentForm({ 
    initialData, 
    isEditMode: mode === "edit" 
  })

  const { toast } = useToast()
  const [workspaceModelDialogOpen, setWorkspaceModelDialogOpen] = useState(false)

  const isSubmitting = externalIsSubmitting || internalIsSubmitting
  
  // 编辑模式下，如果没有initialData，说明还在加载
  const isLoading = mode === "edit" && !initialData

  // 获取有效的工具ID列表（只包含在已安装工具列表中存在的工具）
  const getValidToolIds = (): string[] => {
    const formToolIds = formData.tools.map(t => t.id);
    const installedToolIds = installedTools.map(tool => tool.toolId).filter(Boolean);
    
    // 过滤出在已安装工具列表中存在的工具ID
    const validToolIds = formToolIds.filter(formToolId => installedToolIds.includes(formToolId));
    
    // 打印调试信息
 
 
 
    
    // 检查是否有无效的工具ID
    const invalidToolIds = formToolIds.filter(formToolId => !installedToolIds.includes(formToolId));
    if (invalidToolIds.length > 0) {
 
    }
    
    return validToolIds;
  }

  // 监控预览传递的工具ID变化
  useEffect(() => {
    const validToolIds = getValidToolIds();
    if (validToolIds.length > 0) {
 
    }
  }, [formData.tools, installedTools])

  useEffect(() => {
    if (autoOpenWorkspaceModelDialog && mode === "edit" && agentId) {
      setWorkspaceModelDialogOpen(true)
    }
  }, [autoOpenWorkspaceModelDialog, mode, agentId])

  // 处理提交
  const handleSubmit = async () => {
    // 前端校验
    if (!formData.name.trim()) {
      toast({
        title: "请输入助理名称",
        description: "助理名称是必填项",
        variant: "destructive",
      })
      return
    }

    if (!formData.description?.trim()) {
      toast({
        title: "请输入助理描述",
        description: "助理描述是必填项",
        variant: "destructive",
      })
      return
    }
    
    await onSubmit(formData)
  }

  // 如果正在加载（编辑模式），显示加载状态
  if (isLoading) {
    return (
      <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50 p-4">
        <div className="bg-white rounded-lg shadow-xl w-full max-w-7xl flex h-[95vh] overflow-hidden">
          <div className="w-3/5 p-8 overflow-auto">
            <div className="flex items-center justify-between mb-6">
              <Skeleton className="h-8 w-64" />
              <Skeleton className="h-10 w-10 rounded-full" />
            </div>
            <div className="space-y-6">
              <Skeleton className="h-10 w-full" />
              <div className="space-y-4">
                <Skeleton className="h-6 w-32" />
                <div className="grid grid-cols-2 gap-4">
                  <Skeleton className="h-32 w-full" />
                  <Skeleton className="h-32 w-full" />
                </div>
              </div>
              <div className="space-y-4">
                <Skeleton className="h-6 w-32" />
                <div className="flex gap-4 items-center">
                  <Skeleton className="h-20 w-full" />
                  <Skeleton className="h-20 w-32" />
                </div>
              </div>
            </div>
          </div>
          <div className="w-2/5 bg-gray-50 px-8 pt-8 pb-0 overflow-auto border-l">
            <Skeleton className="h-8 w-32 mb-2" />
            <Skeleton className="h-4 w-64 mb-6" />
            <Skeleton className="h-[500px] w-full mb-6" />
            <Skeleton className="h-6 w-32 mb-3" />
            <Skeleton className="h-40 w-full" />
          </div>
        </div>
      </div>
    )
  }

  return (
    <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50 p-4">
      <div className="bg-white rounded-lg shadow-xl w-full max-w-7xl flex h-[95vh] overflow-hidden">
        {/* 左侧表单 */}
        <div className="w-3/5 min-h-0 flex flex-col overflow-hidden">
          <div className="p-8 pb-4 flex-1 min-h-0 flex flex-col overflow-hidden">
            {/* 头部 */}
            <div className="flex items-center justify-between mb-6 shrink-0">
              <div>
                <h1 className="text-2xl font-bold">
                  {title || (mode === "create" ? "创建新的助理" : "编辑助理")}
                </h1>
                {description && (
                  <p className="text-muted-foreground mt-1">{description}</p>
                )}
              </div>
              
              <div className="flex items-center gap-2">
                {/* 编辑模式的额外操作 */}
                {mode === "edit" && (
                  <>
                    {onShowVersions && (
                      <Button variant="outline" size="sm" onClick={onShowVersions}>
                        版本历史
                      </Button>
                    )}
                    {onPublish && (
                      <Button variant="outline" size="sm" onClick={onPublish}>
                        发布版本
                      </Button>
                    )}

                    {onDelete && (
                      <Button variant="destructive" size="sm" onClick={onDelete}>
                        删除
                      </Button>
                    )}
                  </>
                )}
                
              </div>
            </div>

            {/* 表单标签页 */}
            <Tabs
              value={activeTab}
              onValueChange={setActiveTab}
              className="mt-2 flex-1 min-h-0 flex flex-col"
            >
              <TabsList
                className={`shrink-0 grid w-full h-auto rounded-xl bg-muted p-1 ${mode === "edit" ? "grid-cols-4" : "grid-cols-3"}`}
              >
                {getAvailableTabs().map((tab) => (
                  <TabsTrigger
                    key={tab.id}
                    value={tab.id}
                    className="h-9 rounded-lg data-[state=active]:bg-background data-[state=active]:shadow-sm"
                  >
                    {tab.label}
                  </TabsTrigger>
                ))}
              </TabsList>

              <TabsContent value="basic" className="mt-4 flex-1 min-h-0 overflow-y-auto overflow-x-visible px-1">
                <div className="space-y-6 pb-4">
                  <AgentBasicInfoForm
                    formData={formData}
                    selectedType="agent"
                    updateFormField={updateFormField}
                  />
                  <div className="space-y-4">
                    <h3 className="text-lg font-medium">配置</h3>
                    <div className="grid grid-cols-1 gap-4 xl:grid-cols-2">
                      <Card className="border-blue-100 bg-blue-50/40">
                        <CardContent className="p-4">
                          <div className="flex items-start justify-between gap-3">
                            <div>
                              <div className="text-sm font-medium">配置摘要</div>
                              <div className="text-xs text-muted-foreground mt-1">快速查看当前助理能力配置</div>
                            </div>
                            <div className="shrink-0 flex flex-wrap items-center justify-end gap-2">
                              <Badge variant={formData.enabled ? "default" : "secondary"} className="text-[11px]">
                                {formData.enabled ? "已启用" : "已禁用"}
                              </Badge>
                              <Badge variant="outline" className="text-[11px]">
                                {formData.multiModal ? "支持多模态" : "仅文本"}
                              </Badge>
                            </div>
                          </div>
                          <div className="mt-4 grid grid-cols-2 gap-3">
                            <div className="rounded-lg border bg-white/80 p-3">
                              <div className="text-xs text-muted-foreground">工具数量</div>
                              <div className="mt-1 text-lg font-semibold">{formData.tools.length}</div>
                            </div>
                            <div className="rounded-lg border bg-white/80 p-3">
                              <div className="text-xs text-muted-foreground">知识库数量</div>
                              <div className="mt-1 text-lg font-semibold">{formData.knowledgeBaseIds.length}</div>
                            </div>
                          </div>
                          <div className="mt-4 rounded-lg border bg-white/80 p-3 flex items-center justify-between">
                            <div>
                              <div className="text-sm font-medium">助理状态</div>
                            </div>
                            <Switch
                              checked={formData.enabled}
                              onCheckedChange={(checked) => updateFormField("enabled", checked)}
                              aria-label="切换助理启用状态"
                            />
                          </div>
                          <div className="mt-3 rounded-lg border bg-white/80 p-3 flex items-center justify-between gap-4">
                            <div>
                              <div className="text-sm font-medium">支持多模态</div>
                            </div>
                            <Switch
                              checked={formData.multiModal}
                              onCheckedChange={(checked) => updateFormField("multiModal", checked)}
                              aria-label="切换附件上传能力"
                            />
                          </div>
                        </CardContent>
                      </Card>
                      <Card className="border-blue-100 bg-blue-50/40">
                        <CardContent className="p-4">
                          <div className="flex items-start justify-between gap-3">
                            <div>
                              <div className="text-sm font-medium">会话模型（工作区）</div>
                              <div className="text-xs text-muted-foreground leading-5 mt-1">
                                当前助理在工作区会话中的模型与上下文策略
                              </div>
                            </div>
                            <Badge variant="outline" className="shrink-0 text-[11px]">
                              会话配置
                            </Badge>
                          </div>
                          <div className="mt-4 grid grid-cols-2 gap-2">
                            <div className="rounded-lg border bg-white/80 px-3 py-2">
                              <div className="text-[11px] text-muted-foreground">采样参数</div>
                              <div className="text-xs font-medium mt-0.5">Temperature / Top P / Top K</div>
                            </div>
                            <div className="rounded-lg border bg-white/80 px-3 py-2">
                              <div className="text-[11px] text-muted-foreground">上下文策略</div>
                              <div className="text-xs font-medium mt-0.5">Token上限 / 摘要 / 滑窗</div>
                            </div>
                          </div>
                          <div className="mt-4">
                            {mode === "edit" && agentId ? (
								
                              <Button
                                size="sm"
                                variant="outline"
                                className="w-full"
                                onClick={() => setWorkspaceModelDialogOpen(true)}
                              >
                                配置会话模型
                              </Button>
                            ) : (
                              <div className="text-xs text-muted-foreground">创建助理后可配置会话模型。</div>
                            )}
                          </div>
                        </CardContent>
                      </Card>
                    </div>
                  </div>
                </div>
              </TabsContent>

              <TabsContent value="prompt" className="mt-4 flex-1 min-h-0 overflow-y-auto overflow-x-visible px-1">
                <div className="space-y-6 pb-4">
                  <AgentPromptForm
                    formData={formData}
                    updateFormField={updateFormField}
                    agentId={agentId}
                  />
                </div>
              </TabsContent>

              <TabsContent value="tools" className="mt-4 flex-1 min-h-0 overflow-y-auto overflow-x-visible px-1">
                <div className="space-y-6 pb-4">
                  <AgentToolsForm
                    formData={formData}
                    selectedType="agent"
                    toggleTool={toggleTool}
                    toggleKnowledgeBase={toggleKnowledgeBase}
                    onToolClick={handleToolClick}
                    onKnowledgeBaseClick={handleKnowledgeBaseClick}
                    updateToolPresetParameters={updateToolPresetParameters}
                  />
                </div>
              </TabsContent>

              {/* 小组件标签页 - 仅编辑模式 */}
              {mode === "edit" && agentId && (
                <TabsContent value="widget" className="mt-4 flex-1 min-h-0 overflow-y-auto overflow-x-visible px-1">
                  <div className="space-y-6 pb-4">
                    <AgentWidgetTab agentId={agentId} />
                  </div>
                </TabsContent>
              )}
            </Tabs>
          </div>
          <div className="px-8 py-4 border-t bg-white">
            <div className="flex justify-end gap-2">
              <Button variant="outline" onClick={onCancel}>
                取消
              </Button>
              <Button onClick={handleSubmit} disabled={isSubmitting}>
                {isSubmitting 
                  ? (mode === "create" ? "创建中..." : "保存中...") 
                  : (mode === "create" ? "确认创建" : "保存更改")
                }
              </Button>
            </div>
          </div>
        </div>

        {/* 右侧预览 */}
        <div className="w-2/5 bg-gray-50 px-8 pt-8 pb-0 border-l flex min-h-0 flex-col overflow-hidden">
          <div className="mb-4 shrink-0">
            <div className="flex items-center justify-between">
              <div>
                <h2 className="text-xl font-semibold">预览</h2>
                <p className="text-muted-foreground">
                  与你的Agent进行实时对话，预览实际效果
                </p>
              </div>
            </div>
            
            <div className="mt-4 rounded-xl border bg-white p-4">
              <div className="flex items-center justify-between gap-2">
                <div className="text-sm font-semibold text-foreground">默认模型</div>
              </div>
              <AssistantDefaultModelSelector
                mode={mode}
                agentId={agentId}
                value={formData.defaultModelId ?? null}
                onChange={(modelId) => updateFormField("defaultModelId", modelId)}
                className="mt-3 w-full"
              />
            </div>
          </div>

          {/* Agent预览 */}
          <div className="flex-1 min-h-0">
            <AgentPreviewChat
              agentName={formData.name || (mode === "create" ? "新建助理" : "预览助理")}
              agentAvatar={formData.avatar}
              systemPrompt={formData.systemPrompt || "你是一个智能助手，可以帮助用户解答问题和完成任务。"}
              welcomeMessage={formData.welcomeMessage}
              toolIds={getValidToolIds()} 
              toolPresetParams={formData.toolPresetParams as unknown as Record<string, Record<string, Record<string, string>>>}
              multiModal={formData.multiModal}
              knowledgeBaseIds={formData.knowledgeBaseIds}
              disabled={false}
              className="h-full min-h-0"
            />
          </div>
        </div>
      </div>

      {/* 工具详情侧边栏 */}
      <ToolDetailSidebar
        tool={selectedToolForSidebar}
        isOpen={isToolSidebarOpen}
        onClose={() => setIsToolSidebarOpen(false)}
        presetParameters={selectedToolForSidebar && selectedToolForSidebar.mcpServerName && formData.toolPresetParams[selectedToolForSidebar.mcpServerName] ? 
          formData.toolPresetParams[selectedToolForSidebar.mcpServerName] : 
          {}}
        onSavePresetParameters={updateToolPresetParameters}
      />

      {/* 知识库详情侧边栏 */}
      <KnowledgeBaseDetailSidebar
        knowledgeBase={selectedKnowledgeBaseForSidebar}
        isOpen={isKnowledgeBaseSidebarOpen}
        onClose={() => setIsKnowledgeBaseSidebarOpen(false)}
      />

      {/* 编辑模式的额外组件（如版本历史对话框等） */}
      {children}

      {mode === "edit" && agentId && (
        <ModelSelectDialog
          open={workspaceModelDialogOpen}
          onOpenChange={setWorkspaceModelDialogOpen}
          agentId={agentId}
        />
      )}
    </div>
  )
}
