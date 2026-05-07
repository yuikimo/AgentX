# AgentX RAG 对话优化分析

> 分析日期：2026-04-25
> 分析范围：RAG 对话链路，含查询改写、混合检索、上下文装配、答案生成、会话管理与前端展示

## 概述

AgentX 的 RAG 对话由 `RagMessageHandler` 串起 `RagChatOrchestrator → RagRetrievalCoordinator → RagAnswerGenerator` 三段：先做查询改写决策，再发起多数据集分组并行检索（含 HyDE / 重排 / 相邻片段补充），最后构造上下文 XML 并流式生成答案。整体能力较完整，但仍有可优化点。本文档汇总检索、Prompt、流式、缓存、会话、可观测性六大维度的优化点，按优先级排序。

---

## 一、查询准备 / 改写

### 1. 改写判断启发式过于简单
[RagQueryRewriter.java:92-105](../AgentX/src/main/java/org/xhy/application/conversation/service/message/rag/RagQueryRewriter.java#L92) `isLikelyFollowUpQuestion` 用：
- `length <= 20` 视为追问
- 中英文代词正则（`这|那|it|this|...`）

漏判场景：
- 长追问（"那么按照刚才说的方法，具体应该怎么做"超过 20 字）
- 多语种（日韩文里没有这些代词）
- 同义改述（"他怎么说的"vs"刚才那位说了什么"）

**建议**：用一次轻量 LLM 调用判定 follow-up 概率，或基于 query embedding 与上轮 query 的相似度。

---

### 2. 改写串行阻塞首字节延迟
[RagRetrievalCoordinator.java:104-117](../AgentX/src/main/java/org/xhy/application/conversation/service/message/rag/RagRetrievalCoordinator.java#L104) `buildSearchRequest` 同步调用 `ragQueryRewriter.rewriteQuestion`，内部 `strandClient.chat(...)` 完整阻塞。

用户感知：从"发送"到"开始检索"的延迟 = 改写延迟 + 检索延迟。

**建议**：
- 改写与检索并行起跑：先用原问题做 embedding，等改写返回后取胜出者
- 或者改写超过 1.5s 直接放弃用原问题

---

### 3. 改写无超时保护
[RagQueryRewriter.java:71-83](../AgentX/src/main/java/org/xhy/application/conversation/service/message/rag/RagQueryRewriter.java#L71) `strandClient.chat(messages)` 没有显式 timeout（依赖底层 LLMServiceFactory 的全局 chat timeout 120s）。

**建议**：包一层 `CompletableFuture.get(2, TimeUnit.SECONDS)`，超时回退原问题，并 metric 记录命中率。

---

### 4. 改写与 HyDE 串行执行
[RAGSearchAppService.java:275-279](../AgentX/src/main/java/org/xhy/application/rag/service/search/RAGSearchAppService.java#L275) 同样是 LLM 调用，紧跟在 query rewrite 之后串行。理论上 query rewrite 完成才有"最终问题"传给 HyDE，但很多场景两者输入相同（HyDE 用原问题也可）。

**建议**：query rewrite 与 HyDE 用原问题并行启动；改写完成后再决定是否用改写后的问题做第二次 HyDE。

---

## 二、检索阶段

### 5. 检索期间 SSE 静默
[RagRetrievalCoordinator.java:62-79](../AgentX/src/main/java/org/xhy/application/conversation/service/message/rag/RagRetrievalCoordinator.java#L62) 仅发 `RAG_RETRIEVAL_START` 一帧，然后等检索完整返回再发 `RAG_RETRIEVAL_END`。

复杂多数据集检索可能 5–15s 静默，前端只能显示 spinner，用户体验差。

**建议**：分阶段推送进度：
- 改写完成：`{stage: "rewritten", query: "..."}`
- HyDE 生成完成：`{stage: "hyde", queryHash: "..."}`
- 每个 dataset group 完成：`{stage: "group_done", group: 1, total: 3}`
- 重排完成：`{stage: "rerank_done", count: 8}`

---

### 6. 相邻片段补充使用 page 而非 chunk_index
[RagRetrievalCoordinator.java:181-191](../AgentX/src/main/java/org/xhy/application/conversation/service/message/rag/RagRetrievalCoordinator.java#L181) 取 `page ± 1` 作为相邻片段。`MAX_ADJACENT_SEEDS=4`、`MAX_ADJACENT_DOCUMENTS=4` 硬编码。

问题：
- 一页可能有多个 chunk，page-1 和 page+1 不一定连续语义
- 跨页结构（如表格、长公式）可能被切碎
- 硬编码常量难以按 dataset 调优

**建议**：
- 优先按 chunk_index ± 1 取邻；page 仅作 fallback
- 常量改为 `application.yml` 配置

---

### 7. citation ID 全局只用 D1..Dn
[RagRetrievalCoordinator.java:263-273](../AgentX/src/main/java/org/xhy/application/conversation/service/message/rag/RagRetrievalCoordinator.java#L263) `D1, D2, ...` 顺序编号。

后果：多 turn 之间 D1 永远指向当轮排序第一的文档；前端"上一轮 D1" vs "本轮 D1"指代不同的内容，用户混淆。

**建议**：citation ID 用 `doc_id` 短哈希（如前 6 位），保证跨 turn 唯一；或者把 `turnId` 作为前缀（`T3-D1`）。

---

### 8. 多组分组合并 RRF k 值固定
[RAGSearchAppService.java:427](../AgentX/src/main/java/org/xhy/application/rag/service/search/RAGSearchAppService.java#L427) `1.0 / (max(1, ragSearchRrfK) + rank + 1)`，k=60 默认。

RRF 的 k 越大对低排名权重相对越大；当 group 数仅 1–2 个时 k=60 偏保守。

**建议**：基于 group 数量与 maxResults 自适应（如 `k = max(10, maxResults * 4)`）。

---

### 9. 分组失败用户无感知
[RAGSearchAppService.java:314-323](../AgentX/src/main/java/org/xhy/application/rag/service/search/RAGSearchAppService.java#L314) `failureGroups > groups.size()/2` 才抛错，否则部分结果返回。

用户看到答案不完整时不知道是知识库本身没相关内容、还是某个数据集检索失败。

**建议**:在 RAG_RETRIEVAL_END payload 中包含 `failedGroups: [...]`，前端展示"知识库 X 暂时不可用"。

---

### 10. accessibleDataset 缓存不主动失效
[RAGSearchAppService.java:72](../AgentX/src/main/java/org/xhy/application/rag/service/search/RAGSearchAppService.java#L72) `ACCESSIBLE_DATASET_CACHE` 30s TTL。用户安装/卸载知识库后最多需要 30s 才生效。

**建议**：在 `UserRagDomainService.install / uninstall` 入口主动 `cache.invalidate(userId+...)`。

---

## 三、上下文 / Prompt 装配

### 11. 引用格式 `[^D1]` 模型遵守率不稳定
[RagPromptTemplates.java:14-16](../AgentX/src/main/java/org/xhy/domain/prompt/RagPromptTemplates.java#L14) 要求脚注 `[^D1]`，但 [RagAnswerGenerator.java:43](../AgentX/src/main/java/org/xhy/application/conversation/service/message/rag/RagAnswerGenerator.java#L43) 用 `\\[\\^([^\\]]+)]` 严格匹配 `[^...]`。

LLM 实际输出常见变体：`[D1]` / `[1]` / `[^doc1]` / `（D1）`。模型不严格遵守时，`citedCitationIds` 解析为空，前端无法高亮引用。

**建议**：
- 正则放宽：先 `[\^x]` 再 fallback `[Dxx]` / `[xx]`
- prompt 加 1–2 个 few-shot 示例
- 或改用 OpenAI tool calling 的 `cite` 工具，让模型显式声明引用而不是文本内嵌

---

### 12. context XML 文档元属性过多
[RagAnswerPromptBuilder.java:100-121](../AgentX/src/main/java/org/xhy/application/conversation/service/message/rag/RagAnswerPromptBuilder.java#L100) 每个 `<doc>` 携带 `id / source_id / file_id / filename / page / tier / score / truncated` 8 个属性。

模型大概率只用到 `id`+`content`+`filename`（用于 cite），其他属性消耗 token：每文档约 80–120 tokens × 8 文档 ≈ 800–1000 tokens 浪费。

**建议**：精简为 `id / filename / page`，其他放 metadata 表，前端从 `RagRetrievalDocumentDTO` 读取。

---

### 13. 文档内容截断用 token 二分搜索
[RagAnswerPromptBuilder.java:168-187](../AgentX/src/main/java/org/xhy/application/conversation/service/message/rag/RagAnswerPromptBuilder.java#L168) `clipTextToTokenBudget` 二分搜索符合 token 预算的字符数，每次都调 `tokenEstimatorService.estimateTextTokenCount`。

8 文档 × log2(2000) ≈ 88 次 token 估算调用，对 jtokkit / tiktoken 调用略重。

**建议**：先用 `chars / 3.5`（中英文混合经验比例）做近似估算定位，再做 1–2 次微调验证。

---

### 14. truncated="true" 标志模型不知道含义
模型可能把截断后的内容当作完整证据回答。

**建议**：system prompt 显式说明：
> 如果某 `<doc truncated="true">`，表示原文档更长，当前仅展示前部分；引用时请在脚注后追加 "(片段)"。

---

### 15. summary 与 RAG context 冲突时无指引
[RagPromptTemplates.RAG_SYSTEM_PROMPT](../AgentX/src/main/java/org/xhy/domain/prompt/RagPromptTemplates.java#L8-L17) 没有提及 summary。当 conversation_summary 与 context 内容矛盾时（例如用户上轮明确"忽略 X 文件"），模型可能优先 summary。

**建议**：system prompt 明确 "context > 当前用户问题 > summary > 长期记忆" 的优先级。

---

## 四、流式响应

### 16. RAG 答案分片缓冲粒度固定
[RagAnswerGenerator.java:407-408](../AgentX/src/main/java/org/xhy/application/conversation/service/message/rag/RagAnswerGenerator.java#L407) `FLUSH_CHARS=160 / FLUSH_INTERVAL_MS=48` 硬编码。

含代码块/表格的答案被切成多帧后，前端 markdown 解析失败几次才能正常渲染（"```python" 闭合前都是裸文本）。

**建议**：
- 在 buffer 内检测未闭合的 ```` ``` ```` / `|` 表格行，未闭合则不 flush
- 移到 `chat.context.fragmentEmitter.*` 配置

---

### 17. 思考过程消息无缓冲
[RagAnswerGenerator.java:381-388](../AgentX/src/main/java/org/xhy/application/conversation/service/message/rag/RagAnswerGenerator.java#L381) `ThinkingEventEmitter.onReasoning` 直接 `transport.sendMessage`，每个 reasoning chunk 一帧。

reasoning model 思考过程动辄几千字，N 次小帧浪费带宽，前端事件队列积压。

**建议**：复用 `StreamingFragmentEmitter` 缓冲机制（再单独实例化一个 `MessageType.RAG_THINKING_PROGRESS` 的 emitter）。

---

### 18. RAG 检索元信息巨帧
[RagRetrievalCoordinator.java:74-79](../AgentX/src/main/java/org/xhy/application/conversation/service/message/rag/RagRetrievalCoordinator.java#L74) 一次性 `objectMapper.writeValueAsString(lightweightDocuments)` 整个文档列表序列化为一个 SSE payload。

8–15 个文档的轻量 DTO 仍有数 KB；第一帧 SSE 体积过大可能触发反向代理 buffering。

**建议**：分批发送（每帧 3 个文档）；或先发 metadata header（数量/总分数），详情懒加载。

---

## 五、缓存与性能

### 19. RAG 三层缓存未开 stats
[RAGSearchAppService.java:66-73](../AgentX/src/main/java/org/xhy/application/rag/service/search/RAGSearchAppService.java#L66) `USER_CHAT_MODEL_CONFIG_CACHE` / `FINAL_RAG_RESULT_CACHE` / `RAW_RAG_RESULT_CACHE` 都没 `recordStats()`。

无法观测命中率，TTL（2min / 5min）是否合理无依据。

**建议**：开启 `recordStats()`，通过 `MeterRegistry` 暴露 `cache.gets / cache.misses`。

---

### 20. 缓存 key 未规范化 question
[RAGSearchAppService.java:484-497](../AgentX/src/main/java/org/xhy/application/rag/service/search/RAGSearchAppService.java#L484) 缓存 key 直接拼 question 文本。

`"今天天气"` 与 `"今天 天气"` / `"今天天气?"` 是不同 key，命中率低。

**建议**：先 trim + 全角半角统一 + 删除尾部标点 + 大小写统一，再进哈希。

---

### 21. snapshotDocumentUnitDTOs 深拷贝开销
[RAGSearchAppService.java:477-482](../AgentX/src/main/java/org/xhy/application/rag/service/search/RAGSearchAppService.java#L477) 每次 `cacheFinalResult` 都做 deep copy，命中读取时再恢复。

对热点查询（如 demo 数据集、共用知识库）每次写入都额外 CPU。

**建议**：改 `DocumentUnitDTO` 为不可变类（record 或显式 final 字段 + 无 setter），或承诺消费方不修改。

---

### 22. 用户 chat model 配置缓存不响应变更
[RAGSearchAppService.java:66-67](../AgentX/src/main/java/org/xhy/application/rag/service/search/RAGSearchAppService.java#L66) 5 分钟 TTL，用户切换 chat model 后最多 5 分钟旧配置仍生效。

**建议**：在 `UserSettingsAppService.updateDefaultChatModel` 入口主动 invalidate。

---

### 23. 检索任务执行器与工具线程池不统一
RAG 用 `ragSearchGroupTaskExecutor`（Spring TaskExecutor，配置驱动），但工具调用还在 `AgentToolManager.MCP_INIT_EXECUTOR` 等手写 ThreadPoolExecutor（见上一份分析）。

**建议**：统一异步执行规范，所有异步任务通过 Spring TaskExecutor 暴露。

---

## 六、会话管理

### 26. createSession + updateSession 两次 SQL
[RagSessionManager.java:144-148](../AgentX/src/main/java/org/xhy/application/conversation/service/RagSessionManager.java#L144) 先 insert，再 update title。

**建议**：`SessionDomainService.createSession` 增加 `title` 入参重载。

---

## 七、可观测性

## 八、前端 / 调试体验

---

### 31. 重复"开始检索 / 检索完成"等文案
[RagPromptTemplates.java:95-101](../AgentX/src/main/java/org/xhy/domain/prompt/RagPromptTemplates.java#L95) 检索阶段用文案 "开始检索相关文档..."，前端可能直接渲染。

如果消息类型已经是 `RAG_RETRIEVAL_START`，前端完全可以用图标+进度条展示，文案重复占空间。

**建议**：消息阶段类型本身已经是语义信号，content 留空。

---

## 九、优先级建议

| 优先级 | 优化项 | 收益 | 风险 |
| --- | --- | --- | --- |
| **P0** | #5 检索阶段 SSE 进度推送 | 显著降低用户感知延迟 | 低 |
| **P0** | #11 引用解析正则放宽 + few-shot | 引用高亮率提升 | 低 |
| **P0** | #2 / #3 改写并行 + 超时回退 | 首字节延迟改善 | 中（并发正确性） |
| P1 | #12 context XML 属性精简 | 节省 token / 模型聚焦 | 低 |
| P1 | #19 / #20 缓存 stats + key 规范化 | 命中率可观测、提升 | 低 |
| P1 | #16 / #17 流式缓冲适配代码块/表格 | Markdown 渲染稳定性 | 中（需测试边界） |
| P2 | #6 相邻片段按 chunk_index | 检索完整性 | 低 |
| P2 | #13 token 截断改近似估算 | 检索 prompt 耗时下降 | 低 |
| P2 | #7 citation ID 跨 turn 唯一 | 多轮引用一致性 | 低 |
| P3 | #1 改写判断改 LLM 决策 | 改写召回率 | 中（增加 LLM 调用） |
| P3 | #4 改写与 HyDE 并行 | 首字节延迟 | 低 |

---

## 十、关键文件参考

### 后端 RAG 核心
- [RagMessageHandler.java](../AgentX/src/main/java/org/xhy/application/conversation/service/message/RagMessageHandler.java)
- [RagChatOrchestrator.java](../AgentX/src/main/java/org/xhy/application/conversation/service/message/rag/RagChatOrchestrator.java)
- [RagRetrievalCoordinator.java](../AgentX/src/main/java/org/xhy/application/conversation/service/message/rag/RagRetrievalCoordinator.java)
- [RagAnswerGenerator.java](../AgentX/src/main/java/org/xhy/application/conversation/service/message/rag/RagAnswerGenerator.java)
- [RagAnswerPromptBuilder.java](../AgentX/src/main/java/org/xhy/application/conversation/service/message/rag/RagAnswerPromptBuilder.java)
- [RagQueryRewriter.java](../AgentX/src/main/java/org/xhy/application/conversation/service/message/rag/RagQueryRewriter.java)
- [RagChatContext.java](../AgentX/src/main/java/org/xhy/application/conversation/service/message/rag/RagChatContext.java)
- [RagRetrievalResult.java](../AgentX/src/main/java/org/xhy/application/conversation/service/message/rag/RagRetrievalResult.java)
- [RAGSearchAppService.java](../AgentX/src/main/java/org/xhy/application/rag/service/search/RAGSearchAppService.java)
- [RagSessionManager.java](../AgentX/src/main/java/org/xhy/application/conversation/service/RagSessionManager.java)
- [RagBuiltInToolProvider.java](../AgentX/src/main/java/org/xhy/application/conversation/service/message/builtin/rag/RagBuiltInToolProvider.java)
- [RagPromptTemplates.java](../AgentX/src/main/java/org/xhy/domain/prompt/RagPromptTemplates.java)
- [RagSearchRequest.java](../AgentX/src/main/java/org/xhy/application/rag/dto/RagSearchRequest.java)

---

## 十一、推进建议

如果想推进，建议先聚焦 P0 三项之一：

1. **#5 检索阶段 SSE 进度推送**：改造范围明确（仅 `RagRetrievalCoordinator` + 前端 RAG 消息渲染），用户体验立竿见影
2. **#11 引用解析放宽**：单点改动，但提升 RAG "证据高亮"功能可用性
3. **#2 改写并行 / 超时回退**：让首字节延迟下降可观测百分比，但需要并发正确性测试

每项都需要：
- 单元测试覆盖（`RagRetrievalCoordinatorTest` / `RagQueryRewriterTest` / `RagAnswerGeneratorTest`）
- 手工回归（数据集 RAG / 已安装知识库 RAG / Widget RAG / 跨多 dataset 检索）
- 性能基线对比（首字节 TTFB、整轮回答耗时、引用解析命中率）
