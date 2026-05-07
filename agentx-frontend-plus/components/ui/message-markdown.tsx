"use client";

import React from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { Button } from '@/components/ui/button';
import { Check, Copy } from 'lucide-react';
import { useCopy } from '@/hooks/use-copy';
import { CodeBlock } from '@/components/ui/code-block';
import { cn } from '@/lib/utils';

interface MessageMarkdownProps {
  content: string;
  showCopyButton?: boolean;
  isStreaming?: boolean;
  isError?: boolean;
  className?: string;
  stripFootnoteCitations?: boolean;
}

// 预处理文本内容，标准化反引号和特殊字符
const preprocessContent = (content: string, stripFootnoteCitations = false): string => {
  if (!content) return content;

  // 替换各种可能的引号字符为标准反引号
  let processedContent = content
    // 全角反引号 ｀ -> 标准反引号 `
    .replace(/｀/g, '`')
    // 左右单引号 ' ' -> 标准反引号 `（如果它们是成对出现的，可能是代码标记）
    .replace(/['']([^'']*?)[''] /g, '`$1` ')
    // 其他可能的引号字符
    .replace(/‛/g, '`')
    .replace(/′/g, '`')
    // 修复可能的零宽字符和不可见字符
    .replace(/[\u200B-\u200D\uFEFF]/g, '')
    // 标准化换行符
    .replace(/\r\n/g, '\n')
    .replace(/\r/g, '\n');

  if (stripFootnoteCitations) {
    processedContent = processedContent
      .replace(/\[\^[^\]]+]/g, '')
      .replace(/[ \t]+\n/g, '\n')
      .replace(/\n{3,}/g, '\n\n');
  }

  return processedContent;
};

const hasUnclosedCodeFence = (content: string): boolean => {
  if (!content) return false;
  const matches = content.match(/```/g);
  return Array.isArray(matches) && matches.length % 2 === 1;
};

const markdownComponents = {
  code: ({ inline, children, className, ...props }: any) => {
    const isInline = inline || !className?.includes('language-');

    if (isInline) {
      return (
        <code
          className="!bg-gray-100 dark:!bg-gray-800 !text-red-600 dark:!text-red-400 !px-1 !py-0.5 !rounded !text-sm !font-mono !border-0"
          {...props}
        >
          {children}
        </code>
      );
    }

    return <code className={className} {...props}>{children}</code>;
  },
  pre: ({ children, ...props }: any) => {
    const codeElement = children as React.ReactElement;
    const code = typeof codeElement?.props?.children === 'string'
      ? codeElement.props.children
      : '';

    return (
      <CodeBlock code={code}>
        <pre {...props}>{children}</pre>
      </CodeBlock>
    );
  },
  table: ({ children, ...props }: any) => (
    <div className="w-full my-4 rounded-lg border border-gray-200 dark:border-gray-700">
      <table
        className="w-full table-auto divide-y divide-gray-200 dark:divide-gray-700"
        {...props}
      >
        {children}
      </table>
    </div>
  ),
  thead: ({ children, ...props }: any) => (
    <thead className="bg-gray-50 dark:bg-gray-800" {...props}>
      {children}
    </thead>
  ),
  tbody: ({ children, ...props }: any) => (
    <tbody className="bg-white dark:bg-gray-900 divide-y divide-gray-200 dark:divide-gray-700" {...props}>
      {children}
    </tbody>
  ),
  th: ({ children, ...props }: any) => (
    <th
      className="px-3 py-2 text-left text-xs font-medium text-gray-500 dark:text-gray-400 uppercase tracking-wider break-words"
      {...props}
    >
      {children}
    </th>
  ),
  td: ({ children, ...props }: any) => (
    <td
      className="px-3 py-2 text-sm text-gray-900 dark:text-gray-100 break-words"
      {...props}
    >
      {children}
    </td>
  )
};

export const MessageMarkdown = React.memo(function MessageMarkdown({
  content, 
  showCopyButton = true,
  isStreaming = false, 
  isError = false,
  className,
  stripFootnoteCitations = false
}: MessageMarkdownProps) {
  const { copyMarkdown } = useCopy();
  const [copied, setCopied] = React.useState(false);
  
  const processedContent = React.useMemo(
    () => preprocessContent(content, stripFootnoteCitations),
    [content, stripFootnoteCitations]
  );

  // 仅使用显式错误标记，避免关键词误判导致正常Markdown被渲染成错误样式
  const shouldShowAsError = isError;
  const shouldUsePlainStreamingRenderer = isStreaming && hasUnclosedCodeFence(processedContent);
  
  const handleCopyMessage = async (event: React.MouseEvent<HTMLButtonElement>) => {
    event.preventDefault();
    event.stopPropagation();

    const success = await copyMarkdown(processedContent);
    if (success) {
      setCopied(true);
      window.setTimeout(() => setCopied(false), 2000);
    }
  };

  return (
    <div className={cn("relative group overflow-x-auto min-w-0", className)}>
      {/* Markdown 内容 */}
      {shouldShowAsError ? (
        // 错误消息使用简单文本显示
        <div className={cn(
          "text-sm whitespace-pre-wrap p-3 rounded-lg",
          "bg-red-50 text-red-700 border border-red-200"
        )}>
          {content}
          {isStreaming && (
            <span className="inline-block w-2 h-4 bg-current opacity-75 animate-pulse ml-1" />
          )}
        </div>
      ) : (
        // 正常消息使用 Markdown 渲染
        <div className={cn(
          "prose prose-sm dark:prose-invert w-full min-w-0 max-w-none",
          "prose-pre:bg-white prose-pre:border prose-pre:border-gray-200 prose-pre:text-gray-900"
        )}>
          {shouldUsePlainStreamingRenderer ? (
            <pre className="whitespace-pre-wrap break-words rounded-lg border border-gray-200 bg-gray-50 p-3 text-sm text-gray-900">
              {processedContent}
            </pre>
          ) : (
            <ReactMarkdown
              remarkPlugins={[remarkGfm]}
              components={markdownComponents}
            >
              {processedContent}
            </ReactMarkdown>
          )}
          {isStreaming && (
            <span className="inline-block w-1 h-4 ml-1 bg-current animate-pulse" />
          )}
        </div>
      )}
      
      {/* 底部复制按钮 */}
      {showCopyButton && (
        <div className="flex items-center gap-1 mt-1 opacity-60 hover:opacity-100 transition-opacity">
          <Button
            variant="ghost"
            size="sm"
            onClick={handleCopyMessage}
            type="button"
            className="h-6 w-6 p-0 hover:bg-gray-100 rounded"
            aria-label="复制消息"
          >
            {copied ? (
              <Check className="h-3 w-3 text-green-600" />
            ) : (
              <Copy className="h-3 w-3" />
            )}
          </Button>
        </div>
      )}
    </div>
  );
}, (prevProps, nextProps) => (
  prevProps.content === nextProps.content &&
  prevProps.showCopyButton === nextProps.showCopyButton &&
  prevProps.isStreaming === nextProps.isStreaming &&
  prevProps.isError === nextProps.isError &&
  prevProps.className === nextProps.className &&
  prevProps.stripFootnoteCitations === nextProps.stripFootnoteCitations
));
