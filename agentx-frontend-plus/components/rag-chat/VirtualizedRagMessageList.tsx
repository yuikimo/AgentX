"use client";

import { memo, useEffect, useMemo, useRef, useState, type RefObject } from "react";
import { MessageItem } from "./MessageItem";
import type { Message } from "@/hooks/rag-chat/useRagChatSession";
import type { RetrievedFileInfo, DocumentSegment } from "@/types/rag-dataset";

const DEFAULT_ITEM_HEIGHT = 180;
const OVERSCAN_PX = 600;

interface VirtualizedRagMessageListProps {
  messages: Message[];
  scrollAreaRef: RefObject<HTMLDivElement | null>;
  expandedThinking: Record<string, boolean>;
  onToggleThinking: (messageId: string) => void;
  onFileClick?: (file: RetrievedFileInfo) => void;
  onSegmentClick?: (segment: DocumentSegment) => void;
  selectedFileId?: string;
  selectedSegmentId?: string;
}

interface VirtualRowProps {
  index: number;
  top: number;
  message: Message;
  expandedThinking: boolean;
  onToggleThinking: () => void;
  onHeightChange: (index: number, height: number) => void;
  onFileClick?: (file: RetrievedFileInfo) => void;
  onSegmentClick?: (segment: DocumentSegment) => void;
  selectedFileId?: string;
  selectedSegmentId?: string;
}

const VirtualRow = memo(function VirtualRow({
  index,
  top,
  message,
  expandedThinking,
  onToggleThinking,
  onHeightChange,
  onFileClick,
  onSegmentClick,
  selectedFileId,
  selectedSegmentId,
}: VirtualRowProps) {
  const rowRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    const element = rowRef.current;
    if (!element) {
      return;
    }

    const measure = () => {
      const nextHeight = element.getBoundingClientRect().height;
      if (nextHeight > 0) {
        onHeightChange(index, nextHeight);
      }
    };

    measure();
    const observer = new ResizeObserver(measure);
    observer.observe(element);
    return () => observer.disconnect();
  }, [expandedThinking, index, message.content, message.isStreaming, message.thinkingContent, onHeightChange]);

  return (
    <div
      ref={rowRef}
      className="absolute left-0 top-0 w-full pb-4"
      style={{ transform: `translateY(${top}px)` }}
    >
      <MessageItem
        message={message}
        onFileClick={onFileClick}
        onSegmentClick={onSegmentClick}
        selectedFileId={selectedFileId}
        selectedSegmentId={selectedSegmentId}
        expandedThinking={expandedThinking}
        onToggleThinking={onToggleThinking}
      />
    </div>
  );
});

export function VirtualizedRagMessageList({
  messages,
  scrollAreaRef,
  expandedThinking,
  onToggleThinking,
  onFileClick,
  onSegmentClick,
  selectedFileId,
  selectedSegmentId,
}: VirtualizedRagMessageListProps) {
  const listRef = useRef<HTMLDivElement | null>(null);
  const [scrollTop, setScrollTop] = useState(0);
  const [containerHeight, setContainerHeight] = useState(0);
  const [itemHeights, setItemHeights] = useState<Record<string, number>>({});

  useEffect(() => {
    const scrollViewport = scrollAreaRef.current?.querySelector("[data-radix-scroll-area-viewport]") as HTMLDivElement | null;
    if (!scrollViewport) {
      return;
    }

    const updateMetrics = () => {
      setScrollTop(scrollViewport.scrollTop);
      setContainerHeight(scrollViewport.clientHeight);
    };

    updateMetrics();
    scrollViewport.addEventListener("scroll", updateMetrics);
    const resizeObserver = new ResizeObserver(updateMetrics);
    resizeObserver.observe(scrollViewport);

    return () => {
      scrollViewport.removeEventListener("scroll", updateMetrics);
      resizeObserver.disconnect();
    };
  }, [scrollAreaRef]);

  const listOffsetTop = listRef.current?.offsetTop ?? 0;

  const layout = useMemo(() => {
    const positions: number[] = new Array(messages.length);
    let totalHeight = 0;

    for (let index = 0; index < messages.length; index += 1) {
      positions[index] = totalHeight;
      totalHeight += itemHeights[messages[index].id] ?? DEFAULT_ITEM_HEIGHT;
    }

    const viewportTop = Math.max(0, scrollTop - listOffsetTop);
    const viewportBottom = Math.max(viewportTop + containerHeight, 0);
    const startBoundary = Math.max(0, viewportTop - OVERSCAN_PX);
    const endBoundary = viewportBottom + OVERSCAN_PX;

    let startIndex = 0;
    while (startIndex < messages.length && positions[startIndex] + (itemHeights[messages[startIndex].id] ?? DEFAULT_ITEM_HEIGHT) < startBoundary) {
      startIndex += 1;
    }

    let endIndex = startIndex;
    while (endIndex < messages.length && positions[endIndex] < endBoundary) {
      endIndex += 1;
    }

    return {
      positions,
      totalHeight,
      startIndex: Math.max(0, startIndex),
      endIndex: Math.min(messages.length, endIndex + 1),
    };
  }, [containerHeight, itemHeights, listOffsetTop, messages, scrollTop]);

  const visibleMessages = useMemo(() => (
    messages.slice(layout.startIndex, layout.endIndex).map((message, offset) => {
      const index = layout.startIndex + offset;
      return {
        index,
        message,
        top: layout.positions[index] ?? 0,
      };
    })
  ), [layout.endIndex, layout.positions, layout.startIndex, messages]);

  const handleHeightChange = (index: number, height: number) => {
    const message = messages[index];
    if (!message || height <= 0) {
      return;
    }

    setItemHeights(previousHeights => {
      if (previousHeights[message.id] === height) {
        return previousHeights;
      }
      return {
        ...previousHeights,
        [message.id]: height,
      };
    });
  };

  return (
    <div ref={listRef} className="relative w-full" style={{ height: layout.totalHeight }}>
      {visibleMessages.map(({ index, message, top }) => (
        <VirtualRow
          key={message.id}
          index={index}
          top={top}
          message={message}
          expandedThinking={expandedThinking[message.id] !== false}
          onToggleThinking={() => onToggleThinking(message.id)}
          onHeightChange={handleHeightChange}
          onFileClick={onFileClick}
          onSegmentClick={onSegmentClick}
          selectedFileId={selectedFileId}
          selectedSegmentId={selectedSegmentId}
        />
      ))}
    </div>
  );
}
