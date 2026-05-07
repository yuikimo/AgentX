"use client"

import { useEffect, useRef, useState } from "react"
import { useParams, useRouter, useSearchParams } from "next/navigation"
import Link from "next/link"
import { 
  ArrowLeft, 
  Upload, 
  File, 
  FileText, 
  Image, 
  Video, 
  Trash, 
  Search, 
  RefreshCw, 
  X,
  CheckCircle,
  Clock,
  AlertCircle,
  Download,
  Loader2,
  FileSearch,
  BookOpen,
  MessageSquare
} from "lucide-react"

import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Skeleton } from "@/components/ui/skeleton"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"
import {
  Pagination,
  PaginationContent,
  PaginationEllipsis,
  PaginationItem,
  PaginationLink,
  PaginationNext,
  PaginationPrevious,
} from "@/components/ui/pagination"
import { Badge } from "@/components/ui/badge"
import { Progress } from "@/components/ui/progress"
import { Textarea } from "@/components/ui/textarea"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { toast } from "@/hooks/use-toast"

import {
  getDatasetDetailWithToast,
  getDatasetFilesWithToast,
  uploadFileWithToast,
  deleteFileWithToast,
  processFileWithToast,
  getDatasetFilesProgressWithToast,
  ragSearchWithToast,
  retryDatasetEmbeddingMigrationWithToast,
  switchDatasetEmbeddingModelWithToast,
} from "@/lib/rag-dataset-service"
import { getEmbeddingModelsWithToast, type Model } from "@/lib/user-settings-service"
import type { 
  RagDataset, 
  FileDetail, 
  PageResponse,
  FileProcessProgressDTO,
  ProcessType,
  DocumentUnitDTO 
} from "@/types/rag-dataset"
import { FileProcessingStatusEnum } from "@/types/rag-dataset"
import { getFileStatusConfig as getFileStatusInfo } from "@/lib/file-status-utils"
import { RagChatDialog } from "@/components/knowledge/RagChatDialog"
import { DocumentUnitsDialog } from "@/components/knowledge/DocumentUnitsDialog"
import { useWorkspace } from "@/contexts/workspace-context"

export default function DatasetDetailPage() {
  const params = useParams()
  const router = useRouter()
  const searchParams = useSearchParams()
  const { selectedConversationId } = useWorkspace()
  const datasetId = params.id as string

  const currentSessionId =
    searchParams?.get("sessionId") ||
    (selectedConversationId && !selectedConversationId.startsWith("conv-") ? selectedConversationId : undefined)

  const [dataset, setDataset] = useState<RagDataset | null>(null)
  const [files, setFiles] = useState<FileDetail[]>([])
  const [loading, setLoading] = useState(true)
  const [filesLoading, setFilesLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [searchQuery, setSearchQuery] = useState("")
  const [debouncedQuery, setDebouncedQuery] = useState("")
  const [fileToDelete, setFileToDelete] = useState<FileDetail | null>(null)
  const [isDeleting, setIsDeleting] = useState(false)
  const [isUploading, setIsUploading] = useState(false)
  const [embeddingModels, setEmbeddingModels] = useState<Model[]>([])
  const [embeddingModelsLoading, setEmbeddingModelsLoading] = useState(false)
  const [switchingEmbeddingModel, setSwitchingEmbeddingModel] = useState(false)
  const [retryingEmbeddingMigration, setRetryingEmbeddingMigration] = useState(false)
  const [selectedEmbeddingModelId, setSelectedEmbeddingModelId] = useState("")
  
  // 新增状态：文件处理进度
  const [filesProgress, setFilesProgress] = useState<FileProcessProgressDTO[]>([])
  const [isProcessing, setIsProcessing] = useState<{ [fileId: string]: boolean }>({})
  const latestFilesProgressRef = useRef<FileProcessProgressDTO[]>([])
  const progressInitializedRef = useRef(false)
  const progressSnapshotRef = useRef<Record<string, number>>({})
  
  // 新增状态：RAG搜索
  const [searchDocuments, setSearchDocuments] = useState<DocumentUnitDTO[]>([])
  const [ragSearchQuery, setRagSearchQuery] = useState("")
  const [isRagSearching, setIsRagSearching] = useState(false)
  const [showRagResults, setShowRagResults] = useState(false)
  
  // RAG聊天对话框状态
  const [showRagChat, setShowRagChat] = useState(false)
  
  // 文档单元对话框状态
  const [selectedFileForUnits, setSelectedFileForUnits] = useState<FileDetail | null>(null)

  // 分页状态
  const [pageData, setPageData] = useState<PageResponse<FileDetail>>({
    records: [],
    total: 0,
    size: 15,
    current: 1,
    pages: 0
  })

  // 防抖处理搜索查询
  useEffect(() => {
    const timer = setTimeout(() => {
      setDebouncedQuery(searchQuery)
    }, 500)

    return () => clearTimeout(timer)
  }, [searchQuery])

  // 获取数据集详情
  useEffect(() => {
    if (datasetId) {
      loadDatasetDetail()
    }
  }, [datasetId])

  useEffect(() => {
    if (datasetId) {
      loadEmbeddingModels()
    }
  }, [datasetId])

  // 获取文件列表
  useEffect(() => {
    if (datasetId) {
      loadFiles(1, debouncedQuery)
      loadFilesProgress() // 同时加载文件处理进度
    }
  }, [datasetId, debouncedQuery])

  useEffect(() => {
    progressInitializedRef.current = false
    progressSnapshotRef.current = {}
    latestFilesProgressRef.current = []
    setFilesProgress([])
  }, [datasetId])

  // 定期刷新文件处理进度（智能刷新）
  useEffect(() => {
    if (!datasetId) return

    const interval = setInterval(() => {
      // 只有当有文件正在处理时才刷新进度
      const hasProcessingFiles = filesProgress.some((p) => {
        if (p.processingStatusEnum) {
          return [
            FileProcessingStatusEnum.UPLOADED,
            FileProcessingStatusEnum.OCR_PROCESSING,
            FileProcessingStatusEnum.OCR_COMPLETED,
            FileProcessingStatusEnum.EMBEDDING_PROCESSING,
          ].includes(p.processingStatusEnum)
        }

        const progressValue =
          p.processProgress ?? p.ocrProcessProgress ?? p.embeddingProcessProgress
        return progressValue !== undefined && progressValue < 100
      })
      
      if (hasProcessingFiles || Object.keys(isProcessing).some(key => isProcessing[key])) {
        loadFilesProgress()
      }
    }, 3000) // 缩短为3秒刷新一次

    return () => clearInterval(interval)
  }, [datasetId, filesProgress, isProcessing])

  // 监控进度变化，智能刷新文件列表
  useEffect(() => {
    const nextSnapshot: Record<string, number> = {}
    let hasNewlyCompletedFile = false

    filesProgress.forEach((progress) => {
      const currentProgress = progress.processProgress ?? 0
      nextSnapshot[progress.fileId] = currentProgress
    })

    if (!progressInitializedRef.current) {
      progressInitializedRef.current = true
      progressSnapshotRef.current = nextSnapshot
      return
    }

    Object.entries(nextSnapshot).forEach(([fileId, currentProgress]) => {
      const prevProgress = progressSnapshotRef.current[fileId] ?? 0
      if (prevProgress < 100 && currentProgress >= 100) {
        hasNewlyCompletedFile = true
      }
    })

    progressSnapshotRef.current = nextSnapshot

    if (!hasNewlyCompletedFile) return

    const timeoutId = setTimeout(() => {
      loadFiles(pageData.current, debouncedQuery, true)
    }, 600)

    return () => clearTimeout(timeoutId)
  }, [filesProgress, pageData.current, debouncedQuery])

  useEffect(() => {
    if (!datasetId || dataset?.embeddingMigrationStatus !== "MIGRATING") {
      return
    }
    const timer = setInterval(() => {
      loadDatasetDetail(true)
    }, 5000)
    return () => clearInterval(timer)
  }, [datasetId, dataset?.embeddingMigrationStatus])

  // 加载数据集详情
  const loadDatasetDetail = async (silent: boolean = false) => {
    try {
      if (!silent) {
        setLoading(true)
        setError(null)
      }

      const response = await getDatasetDetailWithToast(datasetId)

      if (response.code === 200) {
        setDataset(response.data)
        setSelectedEmbeddingModelId(response.data.embeddingModelId || "")
      } else if (!silent) {
        setError(response.message)
      }
    } catch (error) {
      if (!silent) {
        const errorMessage = error instanceof Error ? error.message : "未知错误"
        setError(errorMessage)
      }
    } finally {
      if (!silent) {
        setLoading(false)
      }
    }
  }

  const loadEmbeddingModels = async () => {
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

  const handleSwitchEmbeddingModel = async () => {
    if (!selectedEmbeddingModelId) {
      toast({
        title: "请选择嵌入模型",
        variant: "destructive",
      })
      return
    }
    if (!dataset || selectedEmbeddingModelId === (dataset.embeddingModelId || "")) {
      toast({
        title: "嵌入模型未变化",
        description: "当前知识库已使用该模型",
      })
      return
    }

    try {
      setSwitchingEmbeddingModel(true)
      const response = await switchDatasetEmbeddingModelWithToast(datasetId, {
        embeddingModelId: selectedEmbeddingModelId,
      })
      if (response.code === 200 && response.data) {
        setDataset(response.data)
      }
      loadDatasetDetail(true)
    } catch (error) {
 
    } finally {
      setSwitchingEmbeddingModel(false)
    }
  }

  const handleRetryEmbeddingMigration = async () => {
    try {
      setRetryingEmbeddingMigration(true)
      const response = await retryDatasetEmbeddingMigrationWithToast(datasetId)
      if (response.code === 200 && response.data) {
        setDataset(response.data)
      }
      loadDatasetDetail(true)
    } catch (error) {
 
    } finally {
      setRetryingEmbeddingMigration(false)
    }
  }

  // 加载文件列表
  const loadFiles = async (page: number = 1, keyword?: string, silent: boolean = false) => {
    try {
      if (!silent) {
        setFilesLoading(true)
      }

      const response = await getDatasetFilesWithToast(datasetId, {
        page,
        pageSize: 15,
        keyword: keyword?.trim() || undefined
      })

      if (response.code === 200) {
        setPageData(response.data)
        setFiles(response.data.records || [])
      }
    } catch (error) {
 
    } finally {
      if (!silent) {
        setFilesLoading(false)
      }
    }
  }

  // 处理文件上传
  const handleFileUpload = async (event: React.ChangeEvent<HTMLInputElement>) => {
    const selectedFiles = event.target.files
    if (!selectedFiles || selectedFiles.length === 0) return

    try {
      setIsUploading(true)

      // 上传所有选中的文件
      for (const file of Array.from(selectedFiles)) {
        const response = await uploadFileWithToast(datasetId, file)
        
        if (response.code === 200) {
          // 重新加载文件列表和数据集信息
          loadFiles(pageData.current, debouncedQuery)
          loadFilesProgress()
          loadDatasetDetail(true)
        }
      }
    } catch (error) {
 
    } finally {
      setIsUploading(false)
      // 清空文件输入
      event.target.value = ""
    }
  }

  // 处理删除文件
  const handleDeleteFile = async () => {
    if (!fileToDelete) return

    try {
      setIsDeleting(true)
      const response = await deleteFileWithToast(datasetId, fileToDelete.id)

      if (response.code === 200) {
        // 重新加载文件列表和数据集信息
        loadFiles(pageData.current, debouncedQuery)
        loadDatasetDetail(true)
      }
    } catch (error) {
 
    } finally {
      setIsDeleting(false)
      setFileToDelete(null)
    }
  }

  // 分页处理
  const handlePageChange = (page: number) => {
    if (page < 1 || page > pageData.pages) return
    loadFiles(page, debouncedQuery)
  }

  // 生成分页数字
  const generatePageNumbers = () => {
    const pages: (number | string)[] = []
    const current = pageData.current
    const total = pageData.pages

    if (total <= 7) {
      for (let i = 1; i <= total; i++) {
        pages.push(i)
      }
    } else {
      if (current <= 4) {
        for (let i = 1; i <= 5; i++) {
          pages.push(i)
        }
        pages.push('...')
        pages.push(total)
      } else if (current >= total - 3) {
        pages.push(1)
        pages.push('...')
        for (let i = total - 4; i <= total; i++) {
          pages.push(i)
        }
      } else {
        pages.push(1)
        pages.push('...')
        for (let i = current - 1; i <= current + 1; i++) {
          pages.push(i)
        }
        pages.push('...')
        pages.push(total)
      }
    }

    return pages
  }

  // 获取文件图标
  const getFileIcon = (contentType: string, ext: string) => {
    if (contentType.startsWith('image/')) {
      return <Image className="h-4 w-4" />
    } else if (contentType.startsWith('video/')) {
      return <Video className="h-4 w-4" />
    } else if (ext === 'pdf' || contentType === 'application/pdf') {
      return <FileText className="h-4 w-4" />
    } else {
      return <File className="h-4 w-4" />
    }
  }

  // 格式化文件大小
  const formatFileSize = (bytes: number) => {
    if (bytes === 0) return '0 B'
    const k = 1024
    const sizes = ['B', 'KB', 'MB', 'GB']
    const i = Math.floor(Math.log(bytes) / Math.log(k))
    return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i]
  }

  // 获取文件状态配置（使用新的统一状态逻辑）
  const getFileStatusDisplay = (file: FileDetail) => {
    const progressInfo = getFileProgressInfo(file.id)
    return getFileStatusInfo(file, progressInfo)
  }

  // 格式化时间
  const formatDate = (dateString: string) => {
    return new Date(dateString).toLocaleString('zh-CN')
  }

  // 清除搜索
  const clearSearch = () => {
    setSearchQuery("")
  }

  // ========== 新增方法：文件处理进度相关 ==========

  // 加载文件处理进度
  const loadFilesProgress = async () => {
    try {
      const response = await getDatasetFilesProgressWithToast(datasetId)
      if (response.code === 200) {
        // 避免不必要的状态更新
        const newProgress = [...response.data].sort((a, b) => a.fileId.localeCompare(b.fileId))
        const hasChanged =
          JSON.stringify(newProgress) !== JSON.stringify(latestFilesProgressRef.current)
        
        if (hasChanged) {
          latestFilesProgressRef.current = newProgress
          setFilesProgress(newProgress)
 
        }
      }
    } catch (error) {
 
    }
  }

  // 启动文件预处理
  const handleProcessFile = async (fileId: string, processType: ProcessType) => {
    try {
      setIsProcessing(prev => ({ ...prev, [fileId]: true }))
      
      const response = await processFileWithToast({
        fileId,
        datasetId,
        processType,
        sessionId: currentSessionId
      })
      
      if (response.code === 200) {
        // 立即刷新进度
        setTimeout(() => {
          loadFilesProgress()
        }, 1000)
        
        // 重新加载文件列表以更新状态
        setTimeout(() => {
          loadFiles(pageData.current, debouncedQuery, true)
        }, 2000)
      }
    } catch (error) {
 
    } finally {
      setIsProcessing(prev => ({ ...prev, [fileId]: false }))
    }
  }
  
  // 这些函数已经被新的统一状态逻辑替代，不再需要

  // 获取文件处理进度信息
  const getFileProgressInfo = (fileId: string) => {
    return filesProgress.find(progress => progress.fileId === fileId)
  }

  const getDisplayProgressValue = (progressInfo?: FileProcessProgressDTO) => {
    if (!progressInfo) return undefined

    if (progressInfo.processingStatusEnum === FileProcessingStatusEnum.OCR_PROCESSING) {
      return progressInfo.ocrProcessProgress ?? progressInfo.processProgress
    }

    if (progressInfo.processingStatusEnum === FileProcessingStatusEnum.EMBEDDING_PROCESSING) {
      return progressInfo.embeddingProcessProgress ?? progressInfo.processProgress
    }

    if (progressInfo.processingStatusEnum === FileProcessingStatusEnum.COMPLETED) {
      return 100
    }

    return progressInfo.processProgress ?? progressInfo.ocrProcessProgress ?? progressInfo.embeddingProcessProgress
  }

  const getDisplayPageInfo = (progressInfo?: FileProcessProgressDTO) => {
    if (!progressInfo) return null

    if (
      progressInfo.processingStatusEnum === FileProcessingStatusEnum.OCR_PROCESSING &&
      progressInfo.currentOcrPageNumber &&
      progressInfo.filePageSize
    ) {
      return `${progressInfo.currentOcrPageNumber}/${progressInfo.filePageSize}`
    }

    if (
      progressInfo.processingStatusEnum === FileProcessingStatusEnum.EMBEDDING_PROCESSING &&
      progressInfo.currentEmbeddingPageNumber &&
      progressInfo.filePageSize
    ) {
      return `${progressInfo.currentEmbeddingPageNumber}/${progressInfo.filePageSize}`
    }

    if (progressInfo.currentPageNumber && progressInfo.filePageSize) {
      return `${progressInfo.currentPageNumber}/${progressInfo.filePageSize}`
    }

    return null
  }

  // 获取状态图标
  const getStatusIcon = (iconType: string) => {
    switch (iconType) {
      case "check":
        return <CheckCircle className="h-3 w-3" />
      case "clock":
        return <Clock className="h-3 w-3" />
      case "alert":
        return <AlertCircle className="h-3 w-3" />
      case "loading":
        return <Loader2 className="h-3 w-3 animate-spin" />
      default:
        return <Clock className="h-3 w-3" />
    }
  }

  const getMigrationStatusBadge = (status?: string) => {
    switch (status) {
      case "MIGRATING":
        return <Badge variant="secondary">迁移中</Badge>
      case "FAILED":
        return <Badge variant="destructive">迁移失败</Badge>
      case "READY":
      default:
        return <Badge variant="outline">已就绪</Badge>
    }
  }

  const getMigrationStatusText = (status?: string) => {
    switch (status) {
      case "MIGRATING":
        return "迁移中"
      case "FAILED":
        return "迁移失败"
      case "READY":
      default:
        return "已就绪"
    }
  }

  // ========== 新增方法：RAG搜索相关 ==========

  // 执行RAG搜索
  const handleRagSearch = async () => {
    if (!ragSearchQuery.trim()) {
      toast({
        title: "请输入搜索内容",
        variant: "destructive",
      })
      return
    }

    try {
      setIsRagSearching(true)
      setShowRagResults(true)
      
      const response = await ragSearchWithToast({
        datasetIds: [datasetId],
        question: ragSearchQuery.trim(),
        maxResults: 15
      })
      
      if (response.code === 200) {
        setSearchDocuments(response.data)
      } else {
        setSearchDocuments([])
      }
    } catch (error) {
 
      setSearchDocuments([])
    } finally {
      setIsRagSearching(false)
    }
  }

  // 清除RAG搜索
  const clearRagSearch = () => {
    setRagSearchQuery("")
    setSearchDocuments([])
    setShowRagResults(false)
  }

  if (loading) {
    return (
      <div className="container py-6">
        <div className="flex items-center gap-4 mb-6">
          <Skeleton className="h-10 w-10 rounded-md" />
          <div>
            <Skeleton className="h-8 w-48 mb-2" />
            <Skeleton className="h-4 w-32" />
          </div>
        </div>
        <div className="grid grid-cols-1 lg:grid-cols-4 gap-6">
          <div className="lg:col-span-1">
            <Card>
              <CardHeader>
                <Skeleton className="h-6 w-24" />
              </CardHeader>
              <CardContent className="space-y-4">
                <Skeleton className="h-4 w-full" />
                <Skeleton className="h-4 w-3/4" />
                <Skeleton className="h-4 w-1/2" />
              </CardContent>
            </Card>
          </div>
          <div className="lg:col-span-3">
            <Card>
              <CardHeader>
                <Skeleton className="h-6 w-32" />
              </CardHeader>
              <CardContent>
                <Skeleton className="h-64 w-full" />
              </CardContent>
            </Card>
          </div>
        </div>
      </div>
    )
  }

  if (error || !dataset) {
    return (
      <div className="container py-6">
        <div className="text-center py-10">
          <AlertCircle className="h-12 w-12 mx-auto text-red-500 mb-4" />
          <div className="text-red-500 mb-4">{error || "数据集不存在"}</div>
          <div className="flex gap-2 justify-center">
            <Button variant="outline" onClick={() => router.back()}>
              <ArrowLeft className="mr-2 h-4 w-4" />
              返回
            </Button>
            <Button variant="outline" onClick={() => loadDatasetDetail()}>
              <RefreshCw className="mr-2 h-4 w-4" />
              重试
            </Button>
          </div>
        </div>
      </div>
    )
  }

  return (
    <div className="container py-6">
      {/* 面包屑导航 */}
      <div className="flex items-center gap-4 mb-6">
        <Button variant="ghost" size="icon" onClick={() => router.back()}>
          <ArrowLeft className="h-4 w-4" />
        </Button>
        <div>
          <h1 className="text-2xl font-bold tracking-tight">{dataset.name}</h1>
          <p className="text-muted-foreground">
            <Link href="/knowledge" className="hover:underline">知识库</Link>
            <span className="mx-2">/</span>
            <span>{dataset.name}</span>
          </p>
        </div>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-4 gap-6">
        <div className="lg:col-span-1">
          <Card className="h-full">
            <CardHeader className="pb-3">
              <div className="flex items-start justify-between gap-3">
                <div className="min-w-0">
                  <CardTitle className="text-lg">数据集控制台</CardTitle>
                  <CardDescription className="mt-1 break-all">{dataset.name}</CardDescription>
                </div>
                {getMigrationStatusBadge(dataset.embeddingMigrationStatus)}
              </div>
            </CardHeader>
            <CardContent className="pt-0">
              <Tabs defaultValue="dataset" className="space-y-4">
                <TabsList className="grid w-full grid-cols-2">
                  <TabsTrigger value="dataset">数据集信息</TabsTrigger>
                  <TabsTrigger value="capability">能力配置</TabsTrigger>
                </TabsList>

                <TabsContent value="dataset" className="mt-0 space-y-4">
                  <div className="rounded-lg border bg-muted/20 p-3">
                    <div className="text-sm text-muted-foreground">ID</div>
                    <p className="text-xs break-all mt-1">{dataset.id}</p>
                  </div>

                  <div className="rounded-lg border p-3">
                    <div className="text-xs text-muted-foreground mb-1">描述</div>
                    <p className="text-sm">{dataset.description || "暂无描述"}</p>
                  </div>

                  <div className="grid grid-cols-2 gap-3">
                    <div className="rounded-lg border bg-muted/20 p-3">
                      <div className="text-xs text-muted-foreground">文件数量</div>
                      <div className="mt-1 text-base font-semibold">{dataset.fileCount} 个</div>
                    </div>
                    <div className="rounded-lg border bg-muted/20 p-3">
                      <div className="text-xs text-muted-foreground">迁移状态</div>
                      <div className="mt-1 text-sm">{getMigrationStatusText(dataset.embeddingMigrationStatus)}</div>
                    </div>
                  </div>

                  <div className="rounded-lg border p-3 space-y-2">
                    <div>
                      <div className="text-xs text-muted-foreground">当前嵌入模型</div>
                      <p className="text-sm break-all font-medium">{dataset.embeddingModelId || "未绑定"}</p>
                    </div>
                    <div>
                      <div className="text-xs text-muted-foreground">Embedding Profile</div>
                      <p className="text-sm break-all">{dataset.activeEmbeddingProfileId || "未生成"}</p>
                    </div>
                  </div>

                  <div className="grid grid-cols-1 gap-2">
                    <div className="rounded-lg border p-3">
                      <div className="text-xs text-muted-foreground">创建时间</div>
                      <p className="text-sm">{formatDate(dataset.createdAt)}</p>
                    </div>
                    <div className="rounded-lg border p-3">
                      <div className="text-xs text-muted-foreground">更新时间</div>
                      <p className="text-sm">{formatDate(dataset.updatedAt)}</p>
                    </div>
                  </div>

                  <div className="pt-2 border-t">
                    <label htmlFor="file-upload" className="block">
                      <Button
                        variant="outline"
                        className="w-full"
                        disabled={isUploading}
                        asChild
                      >
                        <span>
                          <Upload className="mr-2 h-4 w-4" />
                          {isUploading ? "上传中..." : "上传文件"}
                        </span>
                      </Button>
                    </label>
                    <input
                      id="file-upload"
                      type="file"
                      multiple
                      className="hidden"
                      onChange={handleFileUpload}
                      accept=".pdf,.doc,.docx,.txt,.md,.html,.json,.csv,.xlsx,.xls"
                    />
                    <p className="text-xs text-muted-foreground mt-2">支持 PDF、Word、文本等格式</p>
                  </div>
                </TabsContent>

                <TabsContent value="capability" className="mt-0 space-y-4">
                  <div className="rounded-lg border p-4 space-y-3">
                    <div className="flex items-center justify-between gap-3">
                      <label className="text-sm font-medium text-muted-foreground">切换嵌入模型</label>
                      {dataset.embeddingMigrationStatus === "MIGRATING" && (
                        <span className="text-xs text-muted-foreground">旧索引继续服务</span>
                      )}
                    </div>

                    <Select
                      value={selectedEmbeddingModelId}
                      onValueChange={setSelectedEmbeddingModelId}
                      disabled={embeddingModelsLoading || dataset.embeddingMigrationStatus === "MIGRATING"}
                    >
                      <SelectTrigger>
                        <SelectValue placeholder={embeddingModelsLoading ? "加载模型中..." : "选择嵌入模型"} />
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

                    <div className="space-y-2">
                      <Button
                        variant="outline"
                        className="w-full"
                        onClick={handleSwitchEmbeddingModel}
                        disabled={
                          !selectedEmbeddingModelId ||
                          switchingEmbeddingModel ||
                          dataset.embeddingMigrationStatus === "MIGRATING"
                        }
                      >
                        {switchingEmbeddingModel ? "切换中..." : "切换并迁移"}
                      </Button>
                      {dataset.embeddingMigrationStatus === "FAILED" && (
                        <Button
                          variant="secondary"
                          className="w-full"
                          onClick={handleRetryEmbeddingMigration}
                          disabled={retryingEmbeddingMigration}
                        >
                          {retryingEmbeddingMigration ? "重试中..." : "重试迁移"}
                        </Button>
                      )}
                    </div>

                    {dataset.embeddingMigrationStatus === "FAILED" && dataset.embeddingMigrationError && (
                      <p className="text-xs text-red-500">{dataset.embeddingMigrationError}</p>
                    )}
                  </div>

                  <div className="rounded-lg border p-4">
                    <label className="text-sm font-medium text-muted-foreground mb-2 flex items-center gap-2">
                      <MessageSquare className="h-4 w-4" />
                      智能问答
                    </label>
                    <p className="text-xs text-muted-foreground mb-3">基于当前数据集发起对话</p>
                    <Button
                      onClick={() => setShowRagChat(true)}
                      variant="outline"
                      className="w-full"
                    >
                      <MessageSquare className="mr-2 h-4 w-4" />
                      开始对话
                    </Button>
                  </div>

                  <div className="rounded-lg border p-4">
                    <label className="text-sm font-medium text-muted-foreground mb-2 flex items-center gap-2">
                      <FileSearch className="h-4 w-4" />
                      文档搜索
                    </label>
                    <div className="space-y-2">
                      <Textarea
                        placeholder="输入问题进行文档搜索..."
                        value={ragSearchQuery}
                        onChange={(e) => setRagSearchQuery(e.target.value)}
                        className="min-h-[80px] resize-none"
                      />
                      <div className="flex gap-2">
                        <Button
                          onClick={handleRagSearch}
                          disabled={isRagSearching || !ragSearchQuery.trim()}
                          className="flex-1"
                          size="sm"
                        >
                          {isRagSearching ? (
                            <>
                              <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                              搜索中...
                            </>
                          ) : (
                            <>
                              <Search className="mr-2 h-4 w-4" />
                              搜索文档
                            </>
                          )}
                        </Button>
                        {ragSearchQuery && (
                          <Button
                            variant="outline"
                            size="sm"
                            onClick={clearRagSearch}
                          >
                            <X className="h-4 w-4" />
                          </Button>
                        )}
                      </div>
                    </div>
                  </div>
                </TabsContent>
              </Tabs>
            </CardContent>
          </Card>
        </div>

        <div className="lg:col-span-3">
          <Card>
            <CardHeader>
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-2">
                  <CardTitle className="text-lg">文件列表</CardTitle>
                  {filesLoading && files.length > 0 && (
                    <Loader2 className="h-4 w-4 animate-spin text-muted-foreground" />
                  )}
                </div>
                <div className="flex items-center gap-2">
                  <div className="relative">
                    <Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-muted-foreground" />
                    <Input
                      type="search"
                      placeholder="搜索文件..."
                      className="pl-10 pr-10 w-64"
                      value={searchQuery}
                      onChange={(e) => setSearchQuery(e.target.value)}
                    />
                    {searchQuery && (
                      <Button
                        variant="ghost"
                        size="icon"
                        className="absolute right-1 top-1/2 -translate-y-1/2 h-7 w-7"
                        onClick={clearSearch}
                      >
                        <X className="h-4 w-4" />
                      </Button>
                    )}
                  </div>
                </div>
              </div>
            </CardHeader>
            <CardContent>
              {filesLoading && files.length === 0 ? (
                <div className="space-y-3">
                  {Array.from({ length: 5 }).map((_, index) => (
                    <div key={index} className="flex items-center gap-4 p-3 border rounded">
                      <Skeleton className="h-8 w-8" />
                      <div className="flex-1">
                        <Skeleton className="h-4 w-48 mb-2" />
                        <Skeleton className="h-3 w-32" />
                      </div>
                      <Skeleton className="h-6 w-16" />
                      <Skeleton className="h-6 w-16" />
                      <Skeleton className="h-8 w-8" />
                    </div>
                  ))}
                </div>
              ) : files.length === 0 ? (
                <div className="text-center py-8">
                  <File className="h-12 w-12 mx-auto text-gray-400 mb-4" />
                  <h3 className="text-lg font-medium mb-2">
                    {searchQuery ? "未找到匹配的文件" : "还没有上传任何文件"}
                  </h3>
                  <p className="text-muted-foreground mb-4">
                    {searchQuery ? "尝试使用不同的搜索词" : "上传文件开始构建您的知识库"}
                  </p>
                </div>
              ) : (
                <>
                  <Table>
                    <TableHeader>
                      <TableRow>
                        <TableHead className="w-12"></TableHead>
                        <TableHead>文件名</TableHead>
                        <TableHead>大小</TableHead>
                        <TableHead>处理状态</TableHead>
                        <TableHead>处理进度</TableHead>
                        <TableHead>上传时间</TableHead>
                        <TableHead className="w-20">操作</TableHead>
                      </TableRow>
                    </TableHeader>
                    <TableBody>
                      {files.map((file) => {
                        const fileStatusDisplay = getFileStatusDisplay(file)
                        const progressInfo = getFileProgressInfo(file.id)
                        const displayProgressValue = getDisplayProgressValue(progressInfo)
                        const displayPageInfo = getDisplayPageInfo(progressInfo)
                        
                        return (
                          <TableRow key={file.id}>
                            <TableCell>
                              {getFileIcon(file.contentType, file.ext)}
                            </TableCell>
                            <TableCell>
                              <div>
                                <p className="font-medium">{file.originalFilename}</p>
                                <p className="text-xs text-muted-foreground">{file.ext.toUpperCase()}</p>
                              </div>
                            </TableCell>
                            <TableCell className="text-sm">
                              {formatFileSize(file.size)}
                            </TableCell>
                            <TableCell>
                              <div className="flex items-center gap-1">
                                {getStatusIcon(fileStatusDisplay.status.iconType)}
                                <Badge 
                                  variant={fileStatusDisplay.status.variant}
                                  className={`text-xs ${fileStatusDisplay.status.color}`}
                                >
                                  {fileStatusDisplay.status.text}
                                </Badge>
                              </div>
                            </TableCell>
                            <TableCell>
                              {progressInfo && displayProgressValue !== undefined ? (
                                <div className="space-y-1">
                                  <div className="flex items-center justify-between">
                                    <span className="text-xs text-muted-foreground">
                                      {Math.round(displayProgressValue)}%
                                    </span>
                                    {displayPageInfo && (
                                      <span className="text-xs text-muted-foreground">
                                        {displayPageInfo}
                                      </span>
                                    )}
                                  </div>
                                  <Progress value={displayProgressValue} className="h-2" />
                                  {(progressInfo.statusDescription || progressInfo.processingStatusDescription) && (
                                    <p className="text-xs text-muted-foreground">
                                      {progressInfo.statusDescription || progressInfo.processingStatusDescription}
                                    </p>
                                  )}
                                </div>
                              ) : (
                                <span className="text-xs text-muted-foreground">-</span>
                              )}
                            </TableCell>
                            <TableCell className="text-sm">
                              {formatDate(file.createdAt)}
                            </TableCell>
                            <TableCell>
                              <div className="flex items-center gap-1">
                                
                                {(fileStatusDisplay.status.text === "处理完成" || fileStatusDisplay.status.text === "OCR处理完成") && (
                                  <Button
                                    variant="ghost"
                                    size="icon"
                                    className="h-8 w-8"
                                    onClick={() => setSelectedFileForUnits(file)}
                                    title="查看语料"
                                  >
                                    <FileText className="h-4 w-4" />
                                  </Button>
                                )}
                                <Button
                                  variant="ghost"
                                  size="icon"
                                  className="h-8 w-8"
                                  onClick={() => window.open(file.url, '_blank')}
                                  title="下载文件"
                                >
                                  <Download className="h-4 w-4" />
                                </Button>
                                <Button
                                  variant="ghost"
                                  size="icon"
                                  className="h-8 w-8 text-red-600 hover:text-red-700"
                                  onClick={() => setFileToDelete(file)}
                                  title="删除文件"
                                >
                                  <Trash className="h-4 w-4" />
                                </Button>
                              </div>
                            </TableCell>
                          </TableRow>
                        )
                      })}
                    </TableBody>
                  </Table>

                  {/* 分页 */}
                  {pageData.pages > 1 && (
                    <div className="flex justify-center mt-6">
                      <Pagination>
                        <PaginationContent>
                          <PaginationItem>
                            <PaginationPrevious 
                              onClick={() => handlePageChange(pageData.current - 1)}
                              className={pageData.current <= 1 ? "pointer-events-none opacity-50" : "cursor-pointer"}
                            />
                          </PaginationItem>
                          
                          {generatePageNumbers().map((page, index) => (
                            <PaginationItem key={index}>
                              {page === '...' ? (
                                <PaginationEllipsis />
                              ) : (
                                <PaginationLink
                                  onClick={() => handlePageChange(page as number)}
                                  isActive={page === pageData.current}
                                  className="cursor-pointer"
                                >
                                  {page}
                                </PaginationLink>
                              )}
                            </PaginationItem>
                          ))}
                          
                          <PaginationItem>
                            <PaginationNext 
                              onClick={() => handlePageChange(pageData.current + 1)}
                              className={pageData.current >= pageData.pages ? "pointer-events-none opacity-50" : "cursor-pointer"}
                            />
                          </PaginationItem>
                        </PaginationContent>
                      </Pagination>
                    </div>
                  )}
                </>
              )}
            </CardContent>
          </Card>
        </div>
      </div>

      {/* RAG搜索结果对话框 */}
      <Dialog open={showRagResults} onOpenChange={(open) => !open && clearRagSearch()}>
        <DialogContent className="max-w-4xl max-h-[80vh]">
          <DialogHeader>
            <DialogTitle className="flex items-center gap-2">
              <BookOpen className="h-5 w-5" />
              搜索结果
            </DialogTitle>
            <DialogDescription>
              针对问题 "{ragSearchQuery}" 的文档搜索结果
            </DialogDescription>
          </DialogHeader>
          
          <div className="max-h-[60vh] overflow-y-auto">
            {isRagSearching ? (
              <div className="flex items-center justify-center py-8">
                <Loader2 className="h-8 w-8 animate-spin" />
                <span className="ml-2">搜索中...</span>
              </div>
            ) : searchDocuments.length === 0 ? (
              <div className="text-center py-8">
                <FileSearch className="h-12 w-12 mx-auto text-gray-400 mb-4" />
                <h3 className="text-lg font-medium mb-2">未找到相关文档</h3>
                <p className="text-muted-foreground">
                  尝试使用不同的关键词或检查文档是否已完成向量化处理
                </p>
              </div>
            ) : (
              <div className="space-y-4">
                {searchDocuments.map((doc, index) => (
                  <Card key={doc.id} className="p-4">
                    <div className="flex items-start justify-between mb-2">
                      <div className="flex items-center gap-2">
                        <Badge variant="outline" className="text-xs">
                          第 {doc.page} 页
                        </Badge>
                        <Badge variant={doc.isVector ? "default" : "secondary"} className="text-xs">
                          {doc.isVector ? "已向量化" : "未向量化"}
                        </Badge>
                        {doc.isOcr && (
                          <Badge variant="outline" className="text-xs">
                            OCR处理
                          </Badge>
                        )}
                      </div>
                      <span className="text-xs text-muted-foreground">
                        #{index + 1}
                      </span>
                    </div>
                    <div className="text-sm leading-relaxed">
                      {doc.content.length > 500 
                        ? `${doc.content.substring(0, 500)}...` 
                        : doc.content}
                    </div>
                    <div className="flex items-center justify-between mt-3 pt-3 border-t">
                      <span className="text-xs text-muted-foreground">
                        文档ID: {doc.fileId}
                      </span>
                      <span className="text-xs text-muted-foreground">
                        {formatDate(doc.updatedAt)}
                      </span>
                    </div>
                  </Card>
                ))}
              </div>
            )}
          </div>
          
          <DialogFooter>
            <Button variant="outline" onClick={clearRagSearch}>
              关闭
            </Button>
            {searchDocuments.length > 0 && (
              <Button onClick={() => {
                // 可以添加导出功能
                toast({
                  title: "搜索完成",
                  description: `找到 ${searchDocuments.length} 个相关文档片段`,
                })
              }}>
                <Download className="mr-2 h-4 w-4" />
                导出结果
              </Button>
            )}
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* 删除文件确认对话框 */}
      <Dialog open={!!fileToDelete} onOpenChange={(open) => !open && setFileToDelete(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>确认删除</DialogTitle>
            <DialogDescription>
              您确定要删除文件 "{fileToDelete?.originalFilename}" 吗？此操作无法撤销。
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="outline" onClick={() => setFileToDelete(null)}>
              取消
            </Button>
            <Button variant="destructive" onClick={handleDeleteFile} disabled={isDeleting}>
              {isDeleting ? "删除中..." : "确认删除"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* RAG聊天对话框 */}
      <RagChatDialog 
        open={showRagChat}
        onOpenChange={setShowRagChat}
        dataset={dataset}
      />

      {/* 文档单元对话框 */}
      {selectedFileForUnits && (
        <DocumentUnitsDialog
          open={!!selectedFileForUnits}
          onOpenChange={(open) => !open && setSelectedFileForUnits(null)}
          file={selectedFileForUnits}
        />
      )}
    </div>
  )
}
