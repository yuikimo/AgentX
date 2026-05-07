"use client"

import { useRouter } from "next/navigation"
import { toast } from "@/hooks/use-toast"

import { createAgentWithToast } from "@/lib/agent-service"
import { API_CONFIG } from "@/lib/api-config"
import { setAgentModel } from "@/lib/api-services"
import AgentFormModal from "@/components/agent-form-modal"
import type { AgentFormData } from "@/hooks/use-agent-form"

const DEFAULT_WORKSPACE_MODEL_CONFIG = {
  temperature: 0.7,
  topP: 0.9,
  topK: 50,
  maxTokens: 4096,
  strategyType: "NONE",
  reserveRatio: 0.2,
  summaryThreshold: 35,
}

export default function CreateAgentPage() {
  const router = useRouter()

  // 处理创建助理
  const handleCreateAgent = async (formData: AgentFormData) => {
    if (!formData.name.trim()) {
      toast({
        title: "请输入名称",
        variant: "destructive",
      });
      return;
    }

    try {
      // 将工具对象数组转换为工具ID字符串数组
      const toolIds = formData.tools.map(tool => tool.id);
      
      const agentData = {
        name: formData.name,
        avatar: formData.avatar,
        description: formData.description || "",

        systemPrompt: formData.systemPrompt,
        welcomeMessage: formData.welcomeMessage,
        modelConfig: {
          modelName: "gpt-4o", 
          temperature: 0.7,
          maxTokens: 2000
        },
        toolIds: toolIds,
        knowledgeBaseIds: formData.knowledgeBaseIds,
        toolPresetParams: formData.toolPresetParams,
        userId: API_CONFIG.CURRENT_USER_ID,
        multiModal: formData.multiModal,
      };

      const response = await createAgentWithToast(agentData);

      if (response.code === 200) {
        if (response.data?.id && formData.defaultModelId) {
          try {
            await setAgentModel(response.data.id, {
              ...DEFAULT_WORKSPACE_MODEL_CONFIG,
              modelId: formData.defaultModelId,
            })
          } catch (error) {
            toast({
              title: "助理默认模型绑定失败",
              description: "助理已创建成功，可在编辑页重新设置",
              variant: "destructive",
            })
          }
        }
        router.push("/studio");
      }
      // 错误也已由createAgentWithToast处理
    } catch (error) {
 
      // createAgentWithToast 通常也会处理 catch 块的 toast，但以防万一
      if (!(error instanceof Error && error.message.includes("toast already shown"))) {
        toast({
          title: "创建失败",
          description: "请稍后再试",
          variant: "destructive",
        });
      }
      throw error; // 重新抛出错误，让AgentFormModal处理loading状态
    }
  };

  // 处理取消
  const handleCancel = () => {
    router.push("/studio")
  }

  return (
    <AgentFormModal
      mode="create"
      title="创建新的助理"
      description="配置你的智能助理，支持工具调用和知识库集成"
      onSubmit={handleCreateAgent}
      onCancel={handleCancel}
    />
  )
}
