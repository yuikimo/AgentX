"use client"

import { useState, useEffect } from "react"
import { Edit } from "lucide-react"

import { Button } from "@/components/ui/button"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Textarea } from "@/components/ui/textarea"
import { Badge } from "@/components/ui/badge"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { toast } from "@/hooks/use-toast"

import {
  switchDatasetEmbeddingModelWithToast,
  updateDatasetWithToast,
} from "@/lib/rag-dataset-service"
import { getEmbeddingModelsWithToast, type Model } from "@/lib/user-settings-service"
import type { RagDataset, UpdateDatasetRequest } from "@/types/rag-dataset"

interface EditDatasetDialogProps {
  dataset: RagDataset | null
  open: boolean
  onOpenChange: (open: boolean) => void
  onSuccess?: () => void
}

export function EditDatasetDialog({ 
  dataset, 
  open, 
  onOpenChange, 
  onSuccess 
}: EditDatasetDialogProps) {
  const normalizeMigrationStatus = (status?: string) => (status || "").trim().toUpperCase()

  const getMigrationStatusText = (status?: string) => {
    const normalizedStatus = normalizeMigrationStatus(status)
    switch (normalizedStatus) {
      case "MIGRATING":
        return "迁移中"
      case "FAILED":
        return "迁移失败"
      case "READY":
        return "已就绪"
      default:
        return "未知状态"
    }
  }

  const [isSubmitting, setIsSubmitting] = useState(false)
  const [embeddingModelsLoading, setEmbeddingModelsLoading] = useState(false)
  const [embeddingModels, setEmbeddingModels] = useState<Model[]>([])
  const [formData, setFormData] = useState<UpdateDatasetRequest>({
    name: "",
    icon: "",
    description: "",
    embeddingModelId: "",
  })

  // 当数据集变化时更新表单数据
  useEffect(() => {
    if (dataset) {
      setFormData({
        name: dataset.name || "",
        icon: dataset.icon || "",
        description: dataset.description || "",
        embeddingModelId: dataset.embeddingModelId || "",
      })
    }
  }, [dataset])

  useEffect(() => {
    async function loadEmbeddingModels() {
      if (!open) {
        return
      }
      try {
        setEmbeddingModelsLoading(true)
        const response = await getEmbeddingModelsWithToast()
        if (response.code === 200 && Array.isArray(response.data)) {
          setEmbeddingModels(response.data.filter((model) => model.status))
        } else {
          setEmbeddingModels([])
        }
      } catch (error) {
        setEmbeddingModels([])
      } finally {
        setEmbeddingModelsLoading(false)
      }
    }

    loadEmbeddingModels()
  }, [open])

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()

    if (!dataset) return

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

      const response = await updateDatasetWithToast(dataset.id, {
        name: formData.name.trim(),
        icon: formData.icon?.trim() || undefined,
        description: formData.description?.trim() || undefined,
      })

      if (response.code !== 200) {
        return
      }

      const selectedEmbeddingModelId = formData.embeddingModelId?.trim()
      const currentEmbeddingModelId = dataset.embeddingModelId || ""
      if (selectedEmbeddingModelId && selectedEmbeddingModelId !== currentEmbeddingModelId) {
        const switchResponse = await switchDatasetEmbeddingModelWithToast(dataset.id, {
          embeddingModelId: selectedEmbeddingModelId,
        })
        if (switchResponse.code !== 200) {
          return
        }
      }

      onOpenChange(false)
      onSuccess?.()
    } catch (error) {
      // 错误已由withToast处理
    } finally {
      setIsSubmitting(false)
    }
  }

  const handleInputChange = (field: keyof UpdateDatasetRequest, value: string) => {
    setFormData(prev => ({
      ...prev,
      [field]: value
    }))
  }

  if (!dataset) {
    return null
  }
  const migrationStatus = normalizeMigrationStatus(dataset.embeddingMigrationStatus)

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-[425px]">
        <form onSubmit={handleSubmit}>
          <DialogHeader>
            <DialogTitle className="flex items-center gap-2">
              <Edit className="h-5 w-5" />
              编辑数据集
            </DialogTitle>
            <DialogDescription>
              修改数据集的基本信息
            </DialogDescription>
          </DialogHeader>
          
          <div className="grid gap-4 py-4">
            <div className="grid gap-2">
              <Label htmlFor="edit-name">
                数据集名称 <span className="text-red-500">*</span>
              </Label>
              <Input
                id="edit-name"
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
              <Label htmlFor="edit-icon">图标URL（可选）</Label>
              <Input
                id="edit-icon"
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
              <Label htmlFor="edit-description">数据集说明（可选）</Label>
              <Textarea
                id="edit-description"
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
              <Label htmlFor="edit-embedding-model">嵌入模型</Label>
              <Select
                value={formData.embeddingModelId || ""}
                onValueChange={(value) => handleInputChange("embeddingModelId", value)}
                disabled={embeddingModelsLoading || migrationStatus === "MIGRATING"}
              >
                <SelectTrigger id="edit-embedding-model">
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
              <div className="flex items-center gap-2">
                <Badge variant="outline">
                  迁移状态：{getMigrationStatusText(dataset.embeddingMigrationStatus)}
                </Badge>
                {migrationStatus === "MIGRATING" && (
                  <span className="text-xs text-muted-foreground">迁移中，旧索引继续服务</span>
                )}
              </div>
              {migrationStatus === "FAILED" && dataset.embeddingMigrationError && (
                <p className="text-xs text-red-500">{dataset.embeddingMigrationError}</p>
              )}
            </div>
          </div>
          
          <DialogFooter>
            <Button 
              type="button" 
              variant="outline" 
              onClick={() => onOpenChange(false)}
              disabled={isSubmitting}
            >
              取消
            </Button>
            <Button type="submit" disabled={isSubmitting}>
              {isSubmitting ? "保存中..." : "保存"}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
