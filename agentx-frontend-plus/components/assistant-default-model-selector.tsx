"use client"

import { useEffect, useMemo, useRef, useState } from "react"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { Skeleton } from "@/components/ui/skeleton"
import { useToast } from "@/hooks/use-toast"
import { getDefaultModel, getModels, getAgentModel, setAgentModel } from "@/lib/api-services"

interface ModelItem {
  id: string
  name?: string
  modelId?: string
  providerName?: string | null
  status?: boolean
  type?: string
}

interface AssistantDefaultModelSelectorProps {
  mode: "create" | "edit"
  agentId?: string
  value?: string | null
  onChange?: (modelId: string | null) => void
  className?: string
}

const DEFAULT_MODEL_CONFIG = {
  temperature: 0.7,
  topP: 0.9,
  topK: 50,
  maxTokens: 4096,
  strategyType: "NONE",
  reserveRatio: 0.2,
  summaryThreshold: 35,
}

export default function AssistantDefaultModelSelector({
  mode,
  agentId,
  value,
  onChange,
  className = "",
}: AssistantDefaultModelSelectorProps) {
  const { toast } = useToast()
  const [models, setModels] = useState<ModelItem[]>([])
  const [loading, setLoading] = useState(true)
  const [updating, setUpdating] = useState(false)
  const [selectedModelId, setSelectedModelId] = useState<string | null>(value ?? null)
  const [currentAgentConfig, setCurrentAgentConfig] = useState<any>(null)
  const onChangeRef = useRef(onChange)

  useEffect(() => {
    onChangeRef.current = onChange
  }, [onChange])

  const availableModels = useMemo(
    () => models.filter((model) => model.status && model.type === "CHAT"),
    [models]
  )
  const selectedModel = useMemo(
    () => availableModels.find((model) => model.id === selectedModelId) ?? null,
    [availableModels, selectedModelId]
  )

  useEffect(() => {
    setSelectedModelId(value ?? null)
  }, [value])

  useEffect(() => {
    async function loadData() {
      setLoading(true)
      try {
        const modelsResponse = await getModels("CHAT")
        if (modelsResponse.code === 200 && Array.isArray(modelsResponse.data)) {
          setModels(modelsResponse.data)
        } else {
          setModels([])
        }

        if (mode === "edit" && agentId) {
          const configResponse = await getAgentModel(agentId)
          if (configResponse.code === 200 && configResponse.data) {
            setCurrentAgentConfig(configResponse.data)
            if (configResponse.data.modelId) {
              setSelectedModelId(configResponse.data.modelId)
            }
          }
          return
        }

        if (mode === "create" && !value) {
          const defaultModelResponse = await getDefaultModel()
          if (defaultModelResponse.code === 200 && defaultModelResponse.data?.id) {
            const defaultModelId = defaultModelResponse.data.id as string
            setSelectedModelId(defaultModelId)
            onChangeRef.current?.(defaultModelId)
          }
        }
      } catch (error) {
        toast({
          title: "加载模型失败",
          description: "请稍后重试",
          variant: "destructive",
        })
      } finally {
        setLoading(false)
      }
    }

    loadData()
  }, [mode, agentId, value, toast])

  const handleChange = async (modelId: string) => {
    const previousModelId = selectedModelId
    setSelectedModelId(modelId)

    if (mode === "create") {
      onChangeRef.current?.(modelId)
      return
    }

    if (!agentId) {
      return
    }

    setUpdating(true)
    try {
      const payload = {
        ...DEFAULT_MODEL_CONFIG,
        ...(currentAgentConfig || {}),
        modelId,
      }
      const response = await setAgentModel(agentId, payload)

      if (response.code === 200) {
        setCurrentAgentConfig(payload)
      } else {
        setSelectedModelId(previousModelId)
      }
    } catch (error) {
      setSelectedModelId(previousModelId)
    } finally {
      setUpdating(false)
    }
  }

  if (loading) {
    return <Skeleton className={`h-10 w-full ${className}`} />
  }

  return (
    <div className={className}>
      <Select value={selectedModelId ?? undefined} onValueChange={handleChange} disabled={updating}>
        <SelectTrigger className="h-10 w-full bg-white">
          {selectedModel ? (
            <div className="flex w-full min-w-0 items-center text-left leading-tight">
              <span className="min-w-0 flex-1 truncate font-medium">
                {selectedModel.name || selectedModel.modelId || selectedModel.id}
              </span>
              {selectedModel.providerName ? (
                <span className="ml-4 max-w-[120px] shrink-0 truncate text-right text-xs text-muted-foreground">
                  {selectedModel.providerName}
                </span>
              ) : null}
            </div>
          ) : (
            <SelectValue placeholder="选择当前助理默认模型" />
          )}
        </SelectTrigger>
        <SelectContent>
          {availableModels.map((model) => (
            <SelectItem key={model.id} value={model.id}>
              <div className="flex flex-col items-start text-left">
                <span className="font-medium">{model.name || model.modelId || model.id}</span>
                {model.providerName && (
                  <span className="text-xs text-muted-foreground">{model.providerName}</span>
                )}
              </div>
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
      {updating && <div className="text-xs text-muted-foreground mt-1">更新中...</div>}
    </div>
  )
}
