import { useEffect, useState } from "react"
import { Eye, EyeOff, Wrench, Download } from "lucide-react"
import { 
  AlertDialog, 
  AlertDialogCancel, 
  AlertDialogContent, 
  AlertDialogFooter, 
  AlertDialogHeader, 
  AlertDialogTitle 
} from "@/components/ui/alert-dialog"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { toast } from "@/hooks/use-toast"
import { Tool, InstallFieldDefinition } from "@/types/tool"
import { configureInstalledToolWithToast, installToolWithToast } from "@/lib/tool-service"
import { getInstallFields, hasInstallFields, isSecretInstallField } from "@/lib/tool-install-template"

interface InstallToolDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  tool: Tool | null
  onSuccess?: () => void
  version?: string
}

export function InstallToolDialog({ 
  open, 
  onOpenChange, 
  tool, 
  onSuccess,
  version
}: InstallToolDialogProps) {
  const [installing, setInstalling] = useState(false)
  const [installValues, setInstallValues] = useState<Record<string, string>>({})
  const [visibleSecrets, setVisibleSecrets] = useState<Record<string, boolean>>({})
  const displayVersion = version || "当前版本"
  const fields = getInstallFields(tool?.installFields)
  const needsConfig = hasInstallFields(tool?.installFields)

  useEffect(() => {
    if (!open) return
    const initialValues: Record<string, string> = {}
    fields.forEach(field => {
      initialValues[field.name] = ""
    })
    setInstallValues(initialValues)
    setVisibleSecrets({})
  }, [open, tool?.id])

  // 处理安装工具
  const handleInstallTool = async () => {
    if (!tool) return
    
    try {
      setInstalling(true)
      
      // 使用传入的version或默认版本
      const versionToUse = version || tool.current_version || "0.0.1"
      // 优先使用toolId
      const actualToolId = tool.toolId || tool.id
      
      if (!actualToolId) {
        toast({
          title: "安装失败", 
          description: "工具ID不存在",
          variant: "destructive"
        });
        setInstalling(false);
        return;
      }

      for (const field of fields) {
        if (field.required && !String(installValues[field.name] || "").trim()) {
          toast({
            title: "配置不完整",
            description: `请填写 ${getFieldLabel(field)}`,
            variant: "destructive"
          })
          setInstalling(false)
          return
        }
      }
      
      // 直接调用API
      const response = await installToolWithToast(actualToolId, versionToUse)
        
        if (response.code !== 200) {
          // 错误处理由withToast处理
          setInstalling(false)
          onOpenChange(false)
          return
      }

      if (needsConfig) {
        const configResponse = await configureInstalledToolWithToast(actualToolId, {
          installValues
        })
        if (configResponse.code !== 200) {
          setInstalling(false)
          return
        }
      }
      
      toast({
        title: "安装成功",
        description: needsConfig
          ? `${tool.name} (${displayVersion}) 已成功安装并完成配置`
          : `${tool.name} (${displayVersion}) 已成功安装`,
      })
      
      if (onSuccess) {
        onSuccess()
      }
      
      onOpenChange(false)
    } catch (error) {
 
    } finally {
      setInstalling(false)
    }
  }

  return (
    <AlertDialog open={open} onOpenChange={onOpenChange}>
      <AlertDialogContent className="max-w-md">
        <AlertDialogHeader>
          <AlertDialogTitle className="text-xl text-center">安装插件</AlertDialogTitle>
        </AlertDialogHeader>
        
        {tool && (
          <>
            <div className="p-6 border rounded-lg my-4">
              <div className="flex items-center gap-4">
                {tool?.icon ? (
                  <img src={tool.icon} alt={tool.name} className="h-16 w-16 rounded-lg object-cover" />
                ) : (
                  <div className="h-16 w-16 flex items-center justify-center rounded-lg bg-primary/10">
                    <Wrench className="h-8 w-8" />
                  </div>
                )}
                <div>
                  <div className="flex items-center gap-2">
                    <div className="font-semibold text-lg">{tool?.name}</div>
                    {tool?.is_office && <Badge>官方</Badge>}
                  </div>
                  <div className="text-sm text-muted-foreground">{tool.author}</div>
                  <div className="flex items-center mt-1">
                    <span className="text-sm">{tool?.subtitle}</span>
                    <Badge variant="outline" className="ml-2">v{displayVersion}</Badge>
                  </div>
                </div>
              </div>
            </div>
            {needsConfig && (
              <div className="space-y-4 rounded-lg border p-4">
                <div>
                  <div className="text-sm font-medium">安装参数</div>
                  <div className="text-xs text-muted-foreground">这些参数只会保存到你的私有工具配置中。</div>
                </div>
                {fields.map(field => {
                  const isSecret = isSecretInstallField(field)
                  return (
                    <div key={field.name} className="space-y-2">
                      <Label htmlFor={`install-${field.name}`}>
                        {getFieldLabel(field)}
                        {field.required && <span className="ml-1 text-red-500">*</span>}
                      </Label>
                      <div className="relative">
                        <Input
                          id={`install-${field.name}`}
                          type={isSecret && !visibleSecrets[field.name] ? "password" : "text"}
                          value={installValues[field.name] || ""}
                          placeholder={field.placeholder || `请输入 ${getFieldLabel(field)}`}
                          onChange={(event) => setInstallValues(prev => ({
                            ...prev,
                            [field.name]: event.target.value
                          }))}
                          autoComplete="off"
                          className={isSecret ? "pr-10" : undefined}
                        />
                        {isSecret && (
                          <Button
                            type="button"
                            variant="ghost"
                            size="icon"
                            className="absolute right-1 top-1/2 h-7 w-7 -translate-y-1/2 text-muted-foreground"
                            onClick={() => setVisibleSecrets(prev => ({
                              ...prev,
                              [field.name]: !prev[field.name]
                            }))}
                          >
                            {visibleSecrets[field.name] ? (
                              <EyeOff className="h-4 w-4" />
                            ) : (
                              <Eye className="h-4 w-4" />
                            )}
                            <span className="sr-only">
                              {visibleSecrets[field.name] ? "隐藏内容" : "显示内容"}
                            </span>
                          </Button>
                        )}
                      </div>
                      {field.description && (
                        <p className="text-xs text-muted-foreground">{field.description}</p>
                      )}
                    </div>
                  )
                })}
              </div>
            )}
            
          </>
        )}
        
        <AlertDialogFooter className="gap-2 mt-4">
          <AlertDialogCancel className="flex-1">取消</AlertDialogCancel>
          <Button
            className="flex-1"
            onClick={handleInstallTool}
            disabled={installing}
          >
            {installing ? "安装中..." : needsConfig ? "安装并配置" : "安装"}
          </Button>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  )
} 

function getFieldLabel(field: InstallFieldDefinition): string {
  return field.label || field.name
}
