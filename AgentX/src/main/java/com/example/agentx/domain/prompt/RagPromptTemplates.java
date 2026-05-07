package com.example.agentx.domain.prompt;

import org.apache.commons.lang3.StringUtils;

/** RAG 相关提示词模板 */
public final class RagPromptTemplates {

    private static final String RAG_SYSTEM_PROMPT = """
            你是一位专业的文档问答助手，只能基于给定的<context>回答用户问题。
            请遵循以下规则：
            1. 优先使用<context>中的内容作答，不要补充外部知识。
            2. 若<context>信息不足或无法支持结论，请明确说明。
            3. 回答使用清晰、简洁的 Markdown。
            4. 当多个上下文冲突时，优先级从高到低为：`<context>`、用户当前问题、`<retrieval_focus>`、会话摘要/其他背景。
            5. 若答案引用了具体事实、结论或数字，请在相关句末添加 `[^A1B2C3]` 形式的脚注。
            6. 脚注ID必须取自<context>中 `<doc id=\"...\"></doc>` 的 `id` 属性。
            7. 若多个文档共同支撑同一句，可连续给出多个脚注，例如 `[^A1B2C3][^B4C5D6]`。
            8. 若某个 `<doc truncated=\"true\">`，表示当前只提供了原文片段而非全文；引用该文档时请在脚注后追加“(片段)”。
            """;

    private static final String RAG_ANSWER_CONSTRAINTS = """
            <constraints>
            本轮回答只需要引用<context>中实际支撑答案的片段；若引用了具体事实、结论或数字，请在相关句末添加来自<context>文档 id 的脚注。
            示例：
            - 单文档引用：`这是结论。[^A1B2C3]`
            - 多文档引用：`这是综合结论。[^A1B2C3][^B4C5D6]`
            </constraints>
            """;

    public static final String OCR_PROMPT = """
            请识别图片中的内容，并遵循以下要求：

            一、普通文本与数学公式
            1. 普通文本保持原样，不要改写。
            2. 所有数学公式和数学符号都必须使用标准 LaTeX 格式。
            3. 行内公式使用单个 `$` 包裹，例如：`$x^2$`。
            4. 独立公式块使用双 `$` 包裹，例如：`$$\\sum_{i=1}^n i^2$$`。
            5. 保持原文段落结构和换行，明显换行使用 `\\n` 表示。

            二、验证码图片
            1. 只输出验证码字符，不要添加任何解释。
            2. 忽略干扰线、噪点和背景纹理。
            3. 注意区分相似字符，例如 `0/O`、`1/l`、`2/Z`。
            4. 验证码通常为 4-6 位字母数字组合。
            """;

    public static final String QUERY_REWRITE_SYSTEM_PROMPT = """
            你是一个检索查询改写助手。请根据给定的对话历史，将当前问题改写成适合知识库检索的独立查询。
            要求：
            1. 只输出最终查询文本，不要解释。
            2. 如果当前问题已经足够独立，原样输出即可。
            3. 保留关键实体、时间、限定条件和专业术语。
            4. 不要凭空补充对话里没有出现的事实。
            """;

    private RagPromptTemplates() {
    }

    public static PromptSpec buildQueryRewritePromptSpec(String historyContext, String currentQuestion) {
        String userPrompt = new PromptContextBuilder()
                .rag("<conversation_history>\n" + StringUtils.defaultString(historyContext).trim()
                        + "\n</conversation_history>")
                .supplement("<current_question>\n" + StringUtils.defaultString(currentQuestion).trim()
                        + "\n</current_question>")
                .build();
        return PromptSpec.of(QUERY_REWRITE_SYSTEM_PROMPT, userPrompt);
    }

    public static PromptSpec ocrPromptSpec() {
        return PromptSpec.instruction(OCR_PROMPT);
    }

    public static String buildRagAnswerPrompt(String contextXml, String question) {
        return buildRagAnswerUserPrompt(contextXml, question);
    }

    public static String buildRagAnswerUserPrompt(String contextXml, String question) {
        return buildRagAnswerUserPrompt(contextXml, question, "");
    }

    public static String buildRagAnswerUserPrompt(String contextXml, String question, String retrievalFocusSection) {
        return new PromptContextBuilder()
                .rag(StringUtils.defaultString(contextXml).trim())
                .supplement(StringUtils.defaultString(retrievalFocusSection).trim())
                .supplement(wrapQuestion(question))
                .constraints(ragAnswerConstraintsSection())
                .build();
    }

    public static String ragAnswerConstraintsSection() {
        return RAG_ANSWER_CONSTRAINTS;
    }

    public static String buildRagAgentSystemPrompt() {
        return new PromptContextBuilder()
                .persona(RAG_SYSTEM_PROMPT)
                .build();
    }

    public static String retrievalStartMessage() {
        return "";
    }

    public static String retrievalCompletedMessage(int documentCount) {
        return "";
    }

    public static String retrievalFailedMessage(String message) {
        return "文档检索失败: " + StringUtils.defaultString(message).trim();
    }

    public static String noDocumentsMessage() {
        return "没有搜索到相关文档，可以换个关键词或补充更多背景后再试。";
    }

    public static String answerStartMessage() {
        return "开始生成回答...";
    }

    public static String answerEndMessage() {
        return "回答生成完成";
    }

    private static String wrapQuestion(String question) {
        return "<question>\n" + StringUtils.defaultString(question).trim() + "\n</question>";
    }

    public static String wrapRetrievalFocus(String retrievalQuery) {
        if (StringUtils.isBlank(retrievalQuery)) {
            return "";
        }
        return "<retrieval_focus>\n"
                + "以下为本轮检索阶段实际使用的查询焦点，仅用于帮助你对齐文档证据与用户问题；若与用户当前明确提问冲突，以用户当前问题为准。\n"
                + StringUtils.defaultString(retrievalQuery).trim() + "\n"
                + "</retrieval_focus>";
    }
}
