export function splitSseBlocks(buffer: string): { blocks: string[]; rest: string } {
  const normalizedBuffer = buffer.replace(/\r\n/g, "\n").replace(/\r/g, "\n")
  const blocks = normalizedBuffer.split("\n\n")
  return {
    blocks: blocks.slice(0, -1).filter(block => block.trim() !== ""),
    rest: blocks[blocks.length - 1] || "",
  }
}

export function extractSseData(block: string): string | null {
  if (!block) {
    return null
  }

  const dataLines: string[] = []
  const lines = block.split("\n")
  for (const line of lines) {
    if (!line || line.startsWith(":")) {
      continue
    }
    if (line.startsWith("data:")) {
      dataLines.push(line.startsWith("data: ") ? line.slice(6) : line.slice(5))
    }
  }

  if (dataLines.length === 0) {
    return null
  }

  return dataLines.join("\n").trim()
}
