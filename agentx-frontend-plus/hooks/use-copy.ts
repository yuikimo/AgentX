"use client";

import { useToast } from "@/hooks/use-toast";

export function useCopy() {
  const { toast } = useToast();

  const fallbackCopyToClipboard = (text: string) => {
    const textArea = document.createElement("textarea");
    textArea.value = text;
    textArea.setAttribute("readonly", "true");
    textArea.style.position = "fixed";
    textArea.style.left = "-999999px";
    textArea.style.top = "-999999px";
    textArea.style.opacity = "0";
    document.body.appendChild(textArea);

    const selection = document.getSelection();
    const originalRange = selection && selection.rangeCount > 0 ? selection.getRangeAt(0) : null;

    textArea.focus();
    textArea.select();
    textArea.setSelectionRange(0, text.length);

    let copied = false;
    try {
      copied = document.execCommand("copy");
    } finally {
      textArea.remove();
      if (originalRange && selection) {
        selection.removeAllRanges();
        selection.addRange(originalRange);
      }
    }

    return copied;
  };
  
  const copyToClipboard = async (text: string, successMessage = "已复制") => {
    try {
      if (typeof text !== "string" || text.length === 0) {
        throw new Error("empty_text");
      }

      if (typeof navigator !== "undefined" && navigator.clipboard && window.isSecureContext) {
        await navigator.clipboard.writeText(text);
      } else {
        const copied = fallbackCopyToClipboard(text);
        if (!copied) {
          throw new Error("fallback_copy_failed");
        }
      }

      toast({
        title: successMessage,
        duration: 2000,
      });
      return true;
    } catch (error) {
      try {
        const copied = fallbackCopyToClipboard(text);
        if (!copied) {
          throw new Error("fallback_copy_failed");
        }

        toast({
          title: successMessage,
          duration: 2000,
        });
        return true;
      } catch (fallbackError) {
        toast({
          title: "复制失败",
          description: "您的浏览器不支持复制功能",
          variant: "destructive",
          duration: 3000,
        });
        return false;
      }
    }
  };
  
  const copyMarkdown = async (content: string) => {
    return copyToClipboard(content, "消息已复制");
  };
  
  const copyCode = async (code: string) => {
    return copyToClipboard(code, "代码已复制");
  };
  
  return {
    copyToClipboard,
    copyMarkdown,
    copyCode,
  };
}
