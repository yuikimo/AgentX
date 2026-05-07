"use client";

import React, { useState, useEffect, useRef } from 'react';
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogFooter,
  DialogDescription,
} from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { UserTool } from "../../utils/types";
import { getToolDetail, getToolLatestVersion, publishToolToMarketWithToast } from "@/lib/tool-service";
import { toast } from "@/hooks/use-toast";
import { buildDefaultTemplateAndFields, formatJson, parseJsonObject } from "@/lib/tool-install-template";

interface PublishToolDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  tool: UserTool | null;
  onPublishSuccess?: () => void;
}

// Helper function to increment version
function incrementVersion(version: string): string {
  if (!version || version === "0.0.0" || !/^\d+(\.\d+){0,2}$/.test(version)) return "0.0.1"; // Basic validation and default for invalid

  const parts = version.split('.').map(Number);
  parts[parts.length - 1]++; // Increment the last part

  for (let i = parts.length - 1; i > 0; i--) {
    if (parts[i] >= 10) {
      parts[i] = 0;
      parts[i - 1]++;
    } else {
      break; // No more carry-over needed
    }
  }
  // Handle major version increment if first part becomes 10 (e.g. from 0.9.x)
  if (parts.length === 1 && parts[0] >=10) { // e.g. version "9" becomes "10"
      // or if we want to limit to x.y.z and 0.9.9 -> 1.0.0
  } else if (parts.length > 1 && parts[0] >= 10 && parts.length === 3 && version.startsWith('0.')){
    // This specific condition for 0.9.9 -> 1.0.0 is tricky if not constrained.
    // The general loop above handles 0.0.9 -> 0.1.0 correctly.
    // For simplicity, if the first part (major) was incremented due to chain reaction from minor/patch
    // and it was, for example, 0 before, it will become 1.
  }

  return parts.join('.');
}

export function PublishToolDialog({
  open,
  onOpenChange,
  tool,
  onPublishSuccess,
}: PublishToolDialogProps) {
  const [currentVersionInput, setCurrentVersionInput] = useState<string>("0.0.1");
  const [changeLog, setChangeLog] = useState<string>("");
  const [installTemplateInput, setInstallTemplateInput] = useState<string>("");
  const [installFieldsInput, setInstallFieldsInput] = useState<string>("");
  const [templateWarning, setTemplateWarning] = useState<string>("");
  const [isFetchingVersion, setIsFetchingVersion] = useState<boolean>(false);
  const [isPublishing, setIsPublishing] = useState<boolean>(false);
  const initialVersionSet = useRef(false); // To ensure version is set only once on open

  useEffect(() => {
    if (open && tool?.toolId && !initialVersionSet.current) {
      setIsFetchingVersion(true);
      getToolLatestVersion(tool.toolId)
        .then((response) => {
          let nextVersion = "0.0.1";
          if (response.code === 200 && response.data?.version) {
            nextVersion = incrementVersion(response.data.version);
          } else if (response.code !== 200){
            // Handle case where API call fails but not due to 'not found'
            // Still suggest 0.0.1 or show error
            toast({
              title: "获取最新版本失败",
              description: response.message || "无法获取该工具的最新版本号，将使用默认版本。",
              variant: "destructive",
            });
          }
          // If tool has no versions yet, API might return error or specific code.
          // Defaulting to 0.0.1 is a common practice for the first version.
          setCurrentVersionInput(nextVersion);
        })
        .catch((error) => {
          toast({
            title: "获取最新版本出错",
            description: error.message || "加载最新版本时发生网络错误，将使用默认版本。",
            variant: "destructive",
          });
          setCurrentVersionInput("0.0.1"); // Default on error
        })
        .finally(() => {
          setIsFetchingVersion(false);
          initialVersionSet.current = true; // Mark as set
        });
    } else if (!open) {
      // Reset states when dialog closes
      setCurrentVersionInput("0.0.1"); // Reset to default for next open
      setChangeLog("");
      setInstallTemplateInput("");
      setInstallFieldsInput("");
      setTemplateWarning("");
      initialVersionSet.current = false; // Reset ref for next open
    }
  }, [open, tool]);

  useEffect(() => {
    if (!open || !tool) return;

    let cancelled = false;

    async function initInstallTemplate() {
      const toolId = tool?.toolId || tool?.id;
      let installCommand = getToolInstallCommand(tool);

      if (!installCommand && toolId) {
        const response = await getToolDetail(toolId);
        if (response.code === 200) {
          installCommand = getToolInstallCommand(response.data as any);
        }
      }

      if (cancelled) return;

      const { installTemplate, installFields } = buildDefaultTemplateAndFields(installCommand);
      setInstallTemplateInput(formatJson(installTemplate));
      setInstallFieldsInput(formatJson(installFields));
      setTemplateWarning(getInstallTemplateWarning(installCommand));
    }

    initInstallTemplate();
    return () => {
      cancelled = true;
    };
  }, [open, tool]);

  const handleSubmit = async () => {
    if (!tool?.toolId) return; // Should not happen if dialog is open with a tool

    if (!currentVersionInput.trim() || !/^\d+(\.\d+){0,2}$/.test(currentVersionInput.trim())) {
      toast({
        title: "版本号无效",
        description: "请输入有效的版本号，例如 1.0.0 或 0.0.1",
        variant: "destructive",
      });
      return;
    }

    if (!changeLog.trim()) {
      toast({
        title: "上架失败",
        description: "请填写更新日志。",
        variant: "destructive",
      });
      return;
    }

    const installTemplate = parseJsonObject(installTemplateInput);
    if (!installTemplate) {
      toast({
        title: "安装模板无效",
        description: "请填写合法的 JSON 对象格式安装模板。",
        variant: "destructive",
      });
      return;
    }

    const installFields = parseJsonObject(installFieldsInput);
    if (!installFields) {
      toast({
        title: "配置字段无效",
        description: "请填写合法的 JSON 对象格式配置字段。",
        variant: "destructive",
      });
      return;
    }

    setIsPublishing(true);
    try {
      const response = await publishToolToMarketWithToast({
        toolId: tool.toolId,
        version: currentVersionInput.trim(),
        changeLog: changeLog,
        installTemplate,
        installFields,
      });

      if (response.code === 200) {
        toast({
          title: "上架成功",
          description: `工具 ${tool.name} (版本 ${currentVersionInput.trim()}) 已成功上架到市场。`,
        });
        onOpenChange(false); // 关闭对话框
        if (onPublishSuccess) {
          onPublishSuccess();
        }
      }
      // Errors are handled by publishToolToMarketWithToast
    } catch (error) {
      // Errors are handled by publishToolToMarketWithToast
 
    } finally {
      setIsPublishing(false);
    }
  };

  if (!tool) return null;

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-[720px] max-h-[85vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle>上架工具到市场: {tool.name}</DialogTitle>
          <DialogDescription>
            为本次市场版本确认公开安装模板、用户可配置字段、版本号和更新日志。
          </DialogDescription>
        </DialogHeader>
        <div className="space-y-4 py-4">
          <div className="space-y-3 rounded-md border p-4">
            <div>
              <h3 className="text-sm font-medium">安装模板配置</h3>
              <p className="text-xs text-muted-foreground">
                这些内容会随市场版本公开。请只保留占位变量，不要写入真实 Token、API Key 或用户名。
              </p>
            </div>
            <div className="space-y-2">
              <Label htmlFor="install-template">公开安装模板</Label>
              <Textarea
                id="install-template"
                value={installTemplateInput}
                onChange={(e) => setInstallTemplateInput(e.target.value)}
                rows={8}
                disabled={isPublishing || isFetchingVersion}
                className="font-mono text-xs"
              />
              <p className="text-xs text-muted-foreground">
                例如 {"\"ANILIST_USERNAME\": \"${username}\""}，模板里写占位符，下方 fields 里声明同名字段。
              </p>
            </div>
            <div className="space-y-2">
              <Label htmlFor="install-fields">用户可配置字段</Label>
              <Textarea
                id="install-fields"
                value={installFieldsInput}
                onChange={(e) => setInstallFieldsInput(e.target.value)}
                rows={8}
                disabled={isPublishing || isFetchingVersion}
                className="font-mono text-xs"
              />
              <p className="text-xs text-muted-foreground">
                例如 {"\"name\": \"username\""}，对应公开安装模板里的 {"\"ANILIST_USERNAME\": \"${username}\""}。
              </p>
              {templateWarning && (
                <p className="text-xs text-amber-600">
                  {templateWarning}
                </p>
              )}
            </div>
          </div>
          <div className="space-y-2">
            <Label htmlFor="tool-version-input">版本号</Label>
            {isFetchingVersion ? (
              <p>正在获取最新版本号...</p>
            ) : (
              <Input
                id="tool-version-input"
                value={currentVersionInput}
                onChange={(e) => setCurrentVersionInput(e.target.value)}
                placeholder="例如: 0.0.1"
                disabled={isPublishing}
              />
            )}
          </div>
          <div className="space-y-2">
            <Label htmlFor="change-log">更新日志</Label>
            <Textarea
              id="change-log"
              placeholder="例如：修复了XX问题，新增了YY功能..."
              value={changeLog}
              onChange={(e) => setChangeLog(e.target.value)}
              rows={4}
              disabled={isPublishing || isFetchingVersion}
            />
          </div>
        </div>
        <DialogFooter>
          <Button
            variant="outline"
            onClick={() => onOpenChange(false)}
            disabled={isPublishing}
          >
            取消
          </Button>
          <Button 
            onClick={handleSubmit} 
            disabled={isPublishing || isFetchingVersion}
          >
            {isPublishing ? "上架中..." : "确认上架"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function getToolInstallCommand(value: any): unknown {
  return value?.installCommand || value?.install_command || null;
}

function getInstallTemplateWarning(installCommand: unknown): string {
  if (!installCommand) {
    return "未从工具列表或详情中读取到安装命令，请检查该工具是否已保存安装命令。";
  }
  return "";
}
