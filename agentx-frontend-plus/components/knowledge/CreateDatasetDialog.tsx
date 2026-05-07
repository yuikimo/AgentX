"use client"

import { useState } from "react"
import { Book, Plus } from "lucide-react"

import { Button } from "@/components/ui/button"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Textarea } from "@/components/ui/textarea"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { toast } from "@/hooks/use-toast"

import { createDatasetWithToast } from "@/lib/rag-dataset-service"
import { getEmbeddingModelsWithToast, getUserSettingsWithToast, type Model } from "@/lib/user-settings-service"
import type { CreateDatasetRequest } from "@/types/rag-dataset"

interface CreateDatasetDialogProps {
  onSuccess?: () => void
  trigger?: React.ReactNode
}

export function CreateDatasetDialog({ onSuccess, trigger }: CreateDatasetDialogProps) {
  const [open, setOpen] = useState(false)
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [embeddingModelsLoading, setEmbeddingModelsLoading] = useState(false)
  const [embeddingModels, setEmbeddingModels] = useState<Model[]>([])
  const [formData, setFormData] = useState<CreateDatasetRequest>({
    name: "",
    icon: "",
    description: "",
    embeddingModelId: "",
  })

  const loadEmbeddingModels = async () => {
    try {
      setEmbeddingModelsLoading(true)
      const [modelsResponse, settingsResponse] = await Promise.all([
        getEmbeddingModelsWithToast(),
        getUserSettingsWithToast(),
      ])

      if (modelsResponse.code === 200 && Array.isArray(modelsResponse.data)) {
        const activeModels = modelsResponse.data.filter((model) => model.status)
        setEmbeddingModels(activeModels)

        if (!formData.embeddingModelId && settingsResponse.code === 200) {
          const defaultEmbeddingModel = settingsResponse.data?.settingConfig?.defaultEmbeddingModel
          if (defaultEmbeddingModel) {
            setFormData((prev) => ({
              ...prev,
              embeddingModelId: defaultEmbeddingModel,
            }))
          }
        }
      } else {
        setEmbeddingModels([])
      }
    } catch (error) {
      setEmbeddingModels([])
    } finally {
      setEmbeddingModelsLoading(false)
    }
  }

  const handleOpenChange = (nextOpen: boolean) => {
    setOpen(nextOpen)
    if (nextOpen) {
      loadEmbeddingModels()
    }
  }

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()

    // 客户端验证
    if (!formData.name.trim()) {
      toast({
        title: "请输入数据集名称",
        variant: "destructive",
      })
      return
    }

    if (formData.name.length > 100) {
      toast({
        title: "数据集名称不能超过100个字符",
        variant: "destructive",
      })
      return
    }

    if (formData.description && formData.description.length > 1000) {
      toast({
        title: "数据集说明不能超过1000个字符",
        variant: "destructive",
      })
      return
    }

    if (formData.icon && formData.icon.length > 500) {
      toast({
        title: "图标URL不能超过500个字符",
        variant: "destructive",
      })
      return
    }

    try {
      setIsSubmitting(true)

      const response = await createDatasetWithToast({
        name: formData.name.trim(),
        icon: formData.icon?.trim() || undefined,
        description: formData.description?.trim() || undefined,
        embeddingModelId: formData.embeddingModelId?.trim() || undefined,
      })

      if (response.code === 200) {
        // 重置表单
        setFormData({
          name: "",
          icon: "",
          description: "",
          embeddingModelId: "",
        })
        setOpen(false)
        onSuccess?.()
      }
    } catch (error) {
      // 错误已由withToast处理
    } finally {
      setIsSubmitting(false)
    }
  }

  const handleInputChange = (field: keyof CreateDatasetRequest, value: string) => {
    setFormData(prev => ({
      ...prev,
      [field]: value
    }))
  }

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogTrigger asChild>
        {trigger || (
          <Button>
            <Plus className="mr-2 h-4 w-4" />
            创建数据集
          </Button>
        )}
      </DialogTrigger>
      <DialogContent className="sm:max-w-[425px]">
        <form onSubmit={handleSubmit}>
          <DialogHeader>
            <DialogTitle className="flex items-center gap-2">
              <Book className="h-5 w-5" />
              创建数据集
            </DialogTitle>
            <DialogDescription>
              创建一个新的RAG数据集来管理您的知识文档
            </DialogDescription>
          </DialogHeader>
          
          <div className="grid gap-4 py-4">
            <div className="grid gap-2">
              <Label htmlFor="name">
                数据集名称 <span className="text-red-500">*</span>
              </Label>
              <Input
                id="name"
                placeholder="请输入数据集名称"
                value={formData.name}
                onChange={(e) => handleInputChange("name", e.target.value)}
                maxLength={100}
                required
              />
              <p className="text-xs text-muted-foreground">
                {formData.name.length}/100 字符
              </p>
            </div>
            
            <div className="grid gap-2">
              <Label htmlFor="icon">图标URL（可选）</Label>
              <Input
                id="icon"
                placeholder="请输入图标URL"
                value={formData.icon}
                onChange={(e) => handleInputChange("icon", e.target.value)}
                maxLength={500}
                type="url"
              />
              <p className="text-xs text-muted-foreground">
                {formData.icon?.length || 0}/500 字符
              </p>
            </div>
            
            <div className="grid gap-2">
              <Label htmlFor="description">数据集说明（可选）</Label>
              <Textarea
                id="description"
                placeholder="请输入数据集说明"
                value={formData.description}
                onChange={(e) => handleInputChange("description", e.target.value)}
                maxLength={1000}
                rows={3}
              />
              <p className="text-xs text-muted-foreground">
                {formData.description?.length || 0}/1000 字符
              </p>
            </div>

            <div className="grid gap-2">
              <Label htmlFor="embedding-model">嵌入模型（可选）</Label>
              <Select
                value={formData.embeddingModelId || ""}
                onValueChange={(value) => handleInputChange("embeddingModelId", value)}
                disabled={embeddingModelsLoading}
              >
                <SelectTrigger id="embedding-model">
                  <SelectValue
                    placeholder={embeddingModelsLoading ? "加载模型中..." : "选择该知识库绑定的嵌入模型"}
                  />
                </SelectTrigger>
                <SelectContent>
                  {embeddingModels.map((model) => (
                    <SelectItem key={model.id} value={model.id}>
                      <div className="flex items-center gap-2">
                        <span>{model.name}</span>
                        {model.providerName && (
                          <span className="text-xs text-muted-foreground">{model.providerName}</span>
                        )}
                      </div>
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
              <p className="text-xs text-muted-foreground">
                不选择时将回退到通用设置中的默认嵌入模型。
              </p>
            </div>
          </div>
          
          <DialogFooter>
            <Button 
              type="button" 
              variant="outline" 
              onClick={() => setOpen(false)}
              disabled={isSubmitting}
            >
              取消
            </Button>
            <Button type="submit" disabled={isSubmitting}>
              {isSubmitting ? "创建中..." : "创建"}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
