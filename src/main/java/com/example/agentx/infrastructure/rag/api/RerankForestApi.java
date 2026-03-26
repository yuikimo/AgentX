package com.example.agentx.infrastructure.rag.api;

import com.dtflys.forest.annotation.Body;
import com.dtflys.forest.annotation.Post;
import com.dtflys.forest.annotation.Var;
import com.example.agentx.domain.rag.dto.req.RerankRequest;
import com.example.agentx.domain.rag.dto.resp.RerankResponse;

/**
 * Rerank API Forest接口
 */
public interface RerankForestApi {

    /**
     * 调用Rerank API
     *
     * @param apiUrl        API地址
     * @param apiKey        API密钥
     * @param rerankRequest 请求参数
     * @return Rerank响应
     */
    @Post(url = "${apiUrl}", headers = {"Authorization: Bearer ${apiKey}",
            "Content-Type: application/json; charset=utf-8"})
    RerankResponse rerank(@Var("apiUrl") String apiUrl, @Var("apiKey") String apiKey,
                          @Body RerankRequest rerankRequest);
}