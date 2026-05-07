"use client"

import { useEffect, useMemo, useState } from "react";
import { Plus, Trash2 } from "lucide-react";

import { CreateMemoryDialog } from "@/components/memory/create-memory-dialog";
import { DeleteMemoryDialog } from "@/components/memory/delete-memory-dialog";
import { MemoryFilters } from "@/components/memory/memory-filters";
import { MemoryList } from "@/components/memory/memory-list";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import {
  Pagination,
  PaginationContent,
  PaginationEllipsis,
  PaginationItem,
  PaginationLink,
  PaginationNext,
  PaginationPrevious,
} from "@/components/ui/pagination";

import {
  batchDeleteMemoryWithToast,
  createMemoryWithToast,
  deleteMemoryWithToast,
  getMemoriesWithToast,
} from "@/lib/memory-service";
import type { CreateMemoryRequest, MemoryItem, MemoryType, PageResponse } from "@/types/memory";

export default function MemorySettingsPage() {
  const [records, setRecords] = useState<MemoryItem[]>([]);
  const [pagination, setPagination] = useState<{ total: number; size: number; current: number; pages: number }>({
    total: 0,
    size: 15,
    current: 1,
    pages: 0,
  });
  const [typeFilter, setTypeFilter] = useState<MemoryType | "ALL" | string>("ALL");
  const [keywordInput, setKeywordInput] = useState("");
  const [keyword, setKeyword] = useState("");
  const [loading, setLoading] = useState(false);

  const [createOpen, setCreateOpen] = useState(false);
  const [deleteOpen, setDeleteOpen] = useState(false);
  const [deleteTargets, setDeleteTargets] = useState<MemoryItem[]>([]);
  const [selectedIds, setSelectedIds] = useState<string[]>([]);
  const [deleting, setDeleting] = useState(false);

  const fetchList = async () => {
    setLoading(true);
    try {
      const params = {
        page: pagination.current,
        pageSize: pagination.size,
        type: typeFilter !== "ALL" ? (typeFilter as string) : undefined,
        keyword: keyword || undefined,
      };
      const response = await getMemoriesWithToast(params);
      if (response.code === 200) {
        const data = response.data as PageResponse<MemoryItem>;
        setRecords(data.records || []);
        setSelectedIds([]);
        setPagination({
          total: data.total,
          size: data.size,
          current: data.current,
          pages: data.pages,
        });
      }
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchList();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pagination.current, pagination.size, typeFilter, keyword]);

  const handleCreate = async (req: CreateMemoryRequest) => {
    const res = await createMemoryWithToast(req);
    if (res.code === 200) {
      setPagination((p) => ({ ...p, current: 1 }));
      await fetchList();
      return true;
    }
    return false;
  };

  const handleDelete = (item: MemoryItem) => {
    setDeleteTargets([item]);
    setDeleteOpen(true);
  };

  const handleBatchDelete = () => {
    const targets = records.filter((item) => selectedIds.includes(item.id));
    if (targets.length === 0) return;
    setDeleteTargets(targets);
    setDeleteOpen(true);
  };

  const confirmDelete = async () => {
    if (deleteTargets.length === 0) return;
    try {
      setDeleting(true);
      if (deleteTargets.length === 1) {
        const res = await deleteMemoryWithToast(deleteTargets[0].id);
        if (res.code !== 200) return;
      } else {
        const res = await batchDeleteMemoryWithToast({
          itemIds: deleteTargets.map((item) => item.id),
        });
        if (res.code !== 200) return;
      }
      setDeleteOpen(false);
      setDeleteTargets([]);
      setSelectedIds([]);
      await fetchList();
    } finally {
      setDeleting(false);
    }
  };

  const handleSearch = () => {
    const nextKeyword = keywordInput.trim();
    if (pagination.current !== 1) {
      setPagination((p) => ({ ...p, current: 1 }));
    }
    if (nextKeyword !== keyword) {
      setKeyword(nextKeyword);
      return;
    }
    fetchList();
  };

  const handleTypeFilterChange = (nextType: MemoryType | "ALL" | string) => {
    setTypeFilter(nextType);
    setPagination((p) => ({ ...p, current: 1 }));
  };

  const handlePageChange = (page: number) => {
    if (page < 1 || page > pagination.pages || page === pagination.current) return;
    setPagination((p) => ({ ...p, current: page }));
  };

  const handleToggleSelect = (itemId: string, checked: boolean) => {
    setSelectedIds((prev) => {
      if (checked) {
        return prev.includes(itemId) ? prev : [...prev, itemId];
      }
      return prev.filter((id) => id !== itemId);
    });
  };

  const handleToggleSelectAll = (checked: boolean) => {
    setSelectedIds(checked ? records.map((item) => item.id) : []);
  };

  const pageNumbers = useMemo(() => {
    const pages: number[] = [];
    const { current, pages: totalPages } = pagination;
    if (totalPages <= 7) {
      for (let i = 1; i <= totalPages; i++) pages.push(i);
    } else {
      const start = Math.max(2, current - 1);
      const end = Math.min(totalPages - 1, current + 1);
      pages.push(1);
      if (start > 2) pages.push(-1);
      for (let i = start; i <= end; i++) pages.push(i);
      if (end < totalPages - 1) pages.push(-2);
      pages.push(totalPages);
    }
    return pages;
  }, [pagination]);

  return (
    <div className="container py-6">
      <div className="mb-6 flex items-center justify-between">
        <div>
          <h1 className="text-3xl font-bold tracking-tight">记忆管理</h1>
          <p className="text-muted-foreground">查看、搜索、批量清理并管理您的长期记忆</p>
        </div>
        <div className="flex items-center gap-3">
          {selectedIds.length > 0 && (
            <Button variant="outline" onClick={handleBatchDelete}>
              <Trash2 className="mr-2 h-4 w-4" /> 删除选中 ({selectedIds.length})
            </Button>
          )}
          <Button onClick={() => setCreateOpen(true)}>
            <Plus className="mr-2 h-4 w-4" /> 新增记忆
          </Button>
        </div>
      </div>

      <div className="mb-6">
        <MemoryFilters
          typeFilter={typeFilter}
          onTypeFilterChange={handleTypeFilterChange}
          keyword={keywordInput}
          onKeywordChange={setKeywordInput}
          onSearch={handleSearch}
          pageSize={pagination.size}
          onPageSizeChange={(size) => setPagination((p) => ({ ...p, size, current: 1 }))}
          loading={loading}
          onRefresh={fetchList}
        />
      </div>

      <Card className="mb-6">
        <CardHeader className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
          <div>
            <CardTitle>记忆列表</CardTitle>
            <CardDescription>
              共 {pagination.total} 条记忆{keyword ? `，当前关键词“${keyword}”` : ""}
            </CardDescription>
          </div>
          {selectedIds.length > 0 && (
            <div className="text-sm text-muted-foreground">
              已选中当前页 {selectedIds.length} 条
            </div>
          )}
        </CardHeader>
        <CardContent>
          <MemoryList
            items={records}
            loading={loading}
            selectedIds={selectedIds}
            onToggleSelect={handleToggleSelect}
            onToggleSelectAll={handleToggleSelectAll}
            onDelete={handleDelete}
          />

          {pagination.pages > 1 && (
            <div className="mt-4">
              <Pagination>
                <PaginationContent>
                  <PaginationItem>
                    <PaginationPrevious
                      className={pagination.current <= 1 ? "pointer-events-none opacity-50" : "cursor-pointer"}
                      onClick={() => handlePageChange(pagination.current - 1)}
                    />
                  </PaginationItem>

                  {pageNumbers.map((n, idx) => (
                    <PaginationItem key={idx}>
                      {n < 0 ? (
                        <PaginationEllipsis />
                      ) : (
                        <PaginationLink
                          isActive={n === pagination.current}
                          onClick={() => handlePageChange(n)}
                          className="cursor-pointer"
                        >
                          {n}
                        </PaginationLink>
                      )}
                    </PaginationItem>
                  ))}

                  <PaginationItem>
                    <PaginationNext
                      className={pagination.current >= pagination.pages ? "pointer-events-none opacity-50" : "cursor-pointer"}
                      onClick={() => handlePageChange(pagination.current + 1)}
                    />
                  </PaginationItem>
                </PaginationContent>
              </Pagination>
            </div>
          )}
        </CardContent>
      </Card>

      <CreateMemoryDialog open={createOpen} onOpenChange={setCreateOpen} onCreate={handleCreate} />
      <DeleteMemoryDialog
        open={deleteOpen}
        onOpenChange={setDeleteOpen}
        items={deleteTargets}
        onConfirm={confirmDelete}
        loading={deleting}
      />
    </div>
  );
}
