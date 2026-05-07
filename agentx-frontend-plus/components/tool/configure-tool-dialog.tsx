"use client";

import { useEffect, useMemo, useState } from "react";
import { Eye, EyeOff, Settings } from "lucide-react";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { toast } from "@/hooks/use-toast";
import type { InstallFieldDefinition, InstallFieldsConfig } from "@/types/tool";
import { configureInstalledToolWithToast, getMarketToolVersionDetail } from "@/lib/tool-service";
import { getInstallFields, isSecretInstallField } from "@/lib/tool-install-template";

interface ConfigureToolDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  toolId?: string;
  version?: string;
  toolName?: string;
  installFields?: InstallFieldsConfig | null;
  onSuccess?: () => void;
}

export function ConfigureToolDialog({
  open,
  onOpenChange,
  toolId,
  version,
  toolName,
  installFields,
  onSuccess,
}: ConfigureToolDialogProps) {
  const [resolvedInstallFields, setResolvedInstallFields] = useState<InstallFieldsConfig | null | undefined>(installFields);
  const [loadingFields, setLoadingFields] = useState(false);
  const fields = useMemo(() => getInstallFields(resolvedInstallFields), [resolvedInstallFields]);
  const [values, setValues] = useState<Record<string, string>>({});
  const [visibleSecrets, setVisibleSecrets] = useState<Record<string, boolean>>({});
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    setResolvedInstallFields(installFields);
  }, [installFields]);

  useEffect(() => {
    if (!open || !toolId || !version || getInstallFields(resolvedInstallFields).length > 0) return;
    setLoadingFields(true);
    getMarketToolVersionDetail(toolId, version)
      .then(response => {
        if (response.code === 200) {
          setResolvedInstallFields(response.data?.installFields);
        }
      })
      .finally(() => setLoadingFields(false));
  }, [open, toolId, version]);

  useEffect(() => {
    if (!open) return;
    const initialValues: Record<string, string> = {};
    fields.forEach(field => {
      initialValues[field.name] = "";
    });
    setValues(initialValues);
    setVisibleSecrets({});
  }, [open, fields]);

  const handleSave = async () => {
    if (!toolId) return;

    for (const field of fields) {
      if (field.required && !String(values[field.name] || "").trim()) {
        toast({
          title: "配置不完整",
          description: `请填写 ${getFieldLabel(field)}`,
          variant: "destructive",
        });
        return;
      }
    }

    setSaving(true);
    try {
      const response = await configureInstalledToolWithToast(toolId, { installValues: values });
      if (response.code === 200) {
        onSuccess?.();
        onOpenChange(false);
      }
    } finally {
      setSaving(false);
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-[520px]">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <Settings className="h-5 w-5" />
            配置工具
          </DialogTitle>
          <DialogDescription>
            {toolName ? `为 ${toolName} 填写你的私有安装参数。` : "填写你的私有安装参数。"}
          </DialogDescription>
        </DialogHeader>

        {loadingFields ? (
          <div className="rounded-md border bg-muted/30 px-4 py-3 text-sm text-muted-foreground">
            正在加载配置字段...
          </div>
        ) : fields.length === 0 ? (
          <div className="rounded-md border bg-muted/30 px-4 py-3 text-sm text-muted-foreground">
            该工具没有声明需要配置的安装参数。
          </div>
        ) : (
          <div className="space-y-4 py-2">
            {fields.map(field => {
              const isSecret = isSecretInstallField(field);
              return (
                <div key={field.name} className="space-y-2">
                  <Label htmlFor={`install-field-${field.name}`}>
                    {getFieldLabel(field)}
                    {field.required && <span className="ml-1 text-red-500">*</span>}
                  </Label>
                  <div className="relative">
                    <Input
                      id={`install-field-${field.name}`}
                      type={isSecret && !visibleSecrets[field.name] ? "password" : "text"}
                      value={values[field.name] || ""}
                      placeholder={field.placeholder || `请输入 ${getFieldLabel(field)}`}
                      onChange={(event) => setValues(prev => ({ ...prev, [field.name]: event.target.value }))}
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
              );
            })}
          </div>
        )}

        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)} disabled={saving}>
            取消
          </Button>
          <Button onClick={handleSave} disabled={saving || loadingFields || fields.length === 0}>
            {saving ? "保存中..." : "保存配置"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function getFieldLabel(field: InstallFieldDefinition): string {
  return field.label || field.name;
}
