"use client"

import { MoreHorizontal, Trash2 } from "lucide-react";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from "@/components/ui/dropdown-menu";
import { Skeleton } from "@/components/ui/skeleton";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import type { MemoryItem } from "@/types/memory";

interface MemoryListProps {
  items: MemoryItem[];
  loading?: boolean;
  selectedIds: string[];
  onToggleSelect: (itemId: string, checked: boolean) => void;
  onToggleSelectAll: (checked: boolean) => void;
  onDelete: (item: MemoryItem) => void;
}

export function MemoryList({
  items,
  loading,
  selectedIds,
  onToggleSelect,
  onToggleSelectAll,
  onDelete,
}: MemoryListProps) {
  const formatDate = (date?: string) => (date ? new Date(date).toLocaleString("zh-CN") : "-");
  const formatText = (text: string) => (text.length > 140 ? text.slice(0, 140) + "…" : text);
  const selectedOnPage = items.filter((item) => selectedIds.includes(item.id)).length;
  const allSelected = items.length > 0 && selectedOnPage === items.length;

  const typeLabel = (t: string) => {
    switch (t) {
      case "PROFILE":
        return "档案";
      case "TASK":
        return "任务";
      case "FACT":
        return "事实";
      case "EPISODIC":
        return "情景";
      default:
        return t;
    }
  };

  const formatRelativeTime = (date?: string) => {
    if (!date) return "尚未命中";
    const diffMs = Date.now() - new Date(date).getTime();
    const diffMinutes = Math.max(0, Math.floor(diffMs / 60000));
    if (diffMinutes < 60) {
      return `${diffMinutes} 分钟前`;
    }
    const diffHours = Math.floor(diffMinutes / 60);
    if (diffHours < 24) {
      return `${diffHours} 小时前`;
    }
    const diffDays = Math.floor(diffHours / 24);
    return `${diffDays} 天前`;
  };

  if (loading) {
    return (
      <div className="space-y-3">
        {[...Array(3)].map((_, i) => (
          <div key={i} className="flex items-center space-x-4">
            <Skeleton className="h-12 w-12 rounded" />
            <div className="space-y-2 flex-1">
              <Skeleton className="h-4 w-[70%]" />
              <Skeleton className="h-4 w-[40%]" />
            </div>
          </div>
        ))}
      </div>
    );
  }

  return (
    <div className="border rounded-md">
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead className="w-[52px]">
              <Checkbox
                checked={allSelected}
                onCheckedChange={(checked) => onToggleSelectAll(Boolean(checked))}
                aria-label="全选当前页记忆"
              />
            </TableHead>
            <TableHead className="w-[100px]">类型</TableHead>
            <TableHead className="w-[220px]">健康度</TableHead>
            <TableHead>内容</TableHead>
            <TableHead className="w-[200px]">更新时间</TableHead>
            <TableHead className="text-right w-[80px]">操作</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {items.length === 0 && (
            <TableRow>
              <TableCell colSpan={6} className="h-28 text-center text-sm text-muted-foreground">
                暂无符合条件的记忆
              </TableCell>
            </TableRow>
          )}

          {items.map((item) => {
            const importance = Math.round((item.importance ?? 0) * 100);

            return (
              <TableRow key={item.id}>
                <TableCell>
                  <Checkbox
                    checked={selectedIds.includes(item.id)}
                    onCheckedChange={(checked) => onToggleSelect(item.id, Boolean(checked))}
                    aria-label={`选择记忆 ${item.id}`}
                  />
                </TableCell>
                <TableCell className="font-medium">{typeLabel(item.type)}</TableCell>
                <TableCell>
                  <div className="space-y-2">
                    <div>
                      <div className="mb-1 flex items-center justify-between text-xs text-muted-foreground">
                        <span>重要性</span>
                        <span>{importance}%</span>
                      </div>
                      <div className="h-2 rounded-full bg-muted">
                        <div
                          className="h-2 rounded-full bg-primary transition-all"
                          style={{ width: `${importance}%` }}
                        />
                      </div>
                    </div>
                    <div className="text-xs text-muted-foreground space-y-1">
                      <div>命中 {item.hitCount ?? 0} 次</div>
                      <div>上次命中：{formatRelativeTime(item.lastHitAt)}</div>
                      <div className="truncate">来源会话：{item.sourceSessionId || "-"}</div>
                    </div>
                  </div>
                </TableCell>
                <TableCell className="max-w-[560px]">
                  <div className="space-y-2">
                    <div className="whitespace-pre-wrap break-words">{formatText(item.text)}</div>
                    {item.tags && item.tags.length > 0 && (
                      <div className="flex flex-wrap gap-2">
                        {item.tags.slice(0, 4).map((tag) => (
                          <Badge key={tag} variant="secondary">
                            {tag}
                          </Badge>
                        ))}
                      </div>
                    )}
                  </div>
                </TableCell>
                <TableCell>
                  <div className="text-sm">{formatDate(item.updatedAt || item.createdAt)}</div>
                  <div className="text-xs text-muted-foreground">创建于 {formatDate(item.createdAt)}</div>
                </TableCell>
                <TableCell className="text-right">
                  <DropdownMenu>
                    <DropdownMenuTrigger asChild>
                      <Button variant="ghost" size="icon">
                        <MoreHorizontal className="h-4 w-4" />
                      </Button>
                    </DropdownMenuTrigger>
                    <DropdownMenuContent align="end">
                      <DropdownMenuItem className="text-red-600" onClick={() => onDelete(item)}>
                        <Trash2 className="mr-2 h-4 w-4" /> 删除
                      </DropdownMenuItem>
                    </DropdownMenuContent>
                  </DropdownMenu>
                </TableCell>
              </TableRow>
            );
          })}
        </TableBody>
      </Table>
    </div>
  );
}
