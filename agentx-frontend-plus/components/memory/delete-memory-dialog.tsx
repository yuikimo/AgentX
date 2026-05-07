"use client"

import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import type { MemoryItem } from "@/types/memory";

interface DeleteMemoryDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  items: MemoryItem[];
  onConfirm: () => void;
  loading?: boolean;
}

export function DeleteMemoryDialog({ open, onOpenChange, items, onConfirm, loading }: DeleteMemoryDialogProps) {
  const isBatch = items.length > 1;
  const previewItems = items.slice(0, 5);

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{isBatch ? "批量删除记忆" : "删除记忆"}</DialogTitle>
          <DialogDescription>
            {isBatch
              ? `确认删除选中的 ${items.length} 条记忆吗？此操作为归档（软删除），可在后端恢复。`
              : "确认删除该条记忆吗？此操作为归档（软删除），可在后端恢复。"}
          </DialogDescription>
        </DialogHeader>
        <div className="text-sm text-muted-foreground whitespace-pre-wrap max-h-40 overflow-auto border rounded-md p-3 space-y-2">
          {previewItems.map((item) => (
            <div key={item.id}>{item.text}</div>
          ))}
          {items.length > previewItems.length && (
            <div>……其余 {items.length - previewItems.length} 条已省略</div>
          )}
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)} disabled={loading}>
            取消
          </Button>
          <Button variant="destructive" onClick={onConfirm} disabled={loading}>
            {loading ? "删除中..." : "确认删除"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
