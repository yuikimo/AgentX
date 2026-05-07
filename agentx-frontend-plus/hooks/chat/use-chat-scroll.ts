"use client"

import { useCallback, useEffect, useState, type RefObject } from "react"

interface UseChatScrollOptions {
  containerRef: RefObject<HTMLDivElement | null>
  onReachTop?: () => void
}

export function useChatScroll({ containerRef, onReachTop }: UseChatScrollOptions) {
  const [autoScroll, setAutoScroll] = useState(true)

  useEffect(() => {
    const chatContainer = containerRef.current
    if (!chatContainer) return

    const handleScroll = () => {
      const { scrollTop, scrollHeight, clientHeight } = chatContainer
      const isAtBottom = scrollHeight - scrollTop - clientHeight < 20
      setAutoScroll(isAtBottom)

      if (scrollTop <= 48) {
        onReachTop?.()
      }
    }

    chatContainer.addEventListener("scroll", handleScroll)
    return () => chatContainer.removeEventListener("scroll", handleScroll)
  }, [containerRef, onReachTop])

  const scrollToBottom = useCallback((behavior: ScrollBehavior = "smooth") => {
    const chatContainer = containerRef.current
    if (!chatContainer) {
      return
    }
    setAutoScroll(true)
    chatContainer.scrollTo({ top: chatContainer.scrollHeight, behavior })
  }, [containerRef])

  return {
    autoScroll,
    setAutoScroll,
    scrollToBottom,
  }
}
