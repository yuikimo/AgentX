package com.example.agentx.domain.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/** RAG runtime tuning defaults. Environment-facing provider config stays in application.yml. */
@ConfigurationProperties(prefix = "rag")
public class RagProperties {

    private final QueryRewrite queryRewrite = new QueryRewrite();
    private final Retrieval retrieval = new Retrieval();
    private final Search search = new Search();
    private final Hyde hyde = new Hyde();
    private final Rerank rerank = new Rerank();
    private final Keyword keyword = new Keyword();
    private final Vector vector = new Vector();
    private final Doc doc = new Doc();

    public QueryRewrite getQueryRewrite() {
        return queryRewrite;
    }

    public Retrieval getRetrieval() {
        return retrieval;
    }

    public Search getSearch() {
        return search;
    }

    public Hyde getHyde() {
        return hyde;
    }

    public Rerank getRerank() {
        return rerank;
    }

    public Keyword getKeyword() {
        return keyword;
    }

    public Vector getVector() {
        return vector;
    }

    public Doc getDoc() {
        return doc;
    }

    public static class QueryRewrite {
        private long timeoutMs = 3000;
        private long graceWaitMs = 1000;
        private boolean skipWhenAttachmentsPresent = true;

        public long getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(long timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        public long getGraceWaitMs() {
            return graceWaitMs;
        }

        public void setGraceWaitMs(long graceWaitMs) {
            this.graceWaitMs = graceWaitMs;
        }

        public boolean isSkipWhenAttachmentsPresent() {
            return skipWhenAttachmentsPresent;
        }

        public void setSkipWhenAttachmentsPresent(boolean skipWhenAttachmentsPresent) {
            this.skipWhenAttachmentsPresent = skipWhenAttachmentsPresent;
        }
    }

    public static class Retrieval {
        private final Adjacent adjacent = new Adjacent();

        public Adjacent getAdjacent() {
            return adjacent;
        }
    }

    public static class Adjacent {
        private int maxSeeds = 4;
        private int maxDocuments = 4;
        private double highConfidenceMultiplier = 0.82;
        private double lowConfidenceMultiplier = 0.68;
        private double fallbackConfidenceMultiplier = 0.55;
        private double minMultiplier = 0.35;

        public int getMaxSeeds() {
            return maxSeeds;
        }

        public void setMaxSeeds(int maxSeeds) {
            this.maxSeeds = maxSeeds;
        }

        public int getMaxDocuments() {
            return maxDocuments;
        }

        public void setMaxDocuments(int maxDocuments) {
            this.maxDocuments = maxDocuments;
        }

        public double getHighConfidenceMultiplier() {
            return highConfidenceMultiplier;
        }

        public void setHighConfidenceMultiplier(double highConfidenceMultiplier) {
            this.highConfidenceMultiplier = highConfidenceMultiplier;
        }

        public double getLowConfidenceMultiplier() {
            return lowConfidenceMultiplier;
        }

        public void setLowConfidenceMultiplier(double lowConfidenceMultiplier) {
            this.lowConfidenceMultiplier = lowConfidenceMultiplier;
        }

        public double getFallbackConfidenceMultiplier() {
            return fallbackConfidenceMultiplier;
        }

        public void setFallbackConfidenceMultiplier(double fallbackConfidenceMultiplier) {
            this.fallbackConfidenceMultiplier = fallbackConfidenceMultiplier;
        }

        public double getMinMultiplier() {
            return minMultiplier;
        }

        public void setMinMultiplier(double minMultiplier) {
            this.minMultiplier = minMultiplier;
        }
    }

    public static class Search {
        private int rrfK = 60;
        private int rawCacheBaseLimit = 40;

        public int getRrfK() {
            return rrfK;
        }

        public void setRrfK(int rrfK) {
            this.rrfK = rrfK;
        }

        public int getRawCacheBaseLimit() {
            return rawCacheBaseLimit;
        }

        public void setRawCacheBaseLimit(int rawCacheBaseLimit) {
            this.rawCacheBaseLimit = rawCacheBaseLimit;
        }
    }

    public static class Hyde {
        private long syncTimeoutMs = 2500;
        private long generationTimeoutSeconds = 10;
        private long inFlightTtlMs = 60000;
        private String promptTemplate = "";
        private String directFactPattern = "";
        private String properNounPattern = "";
        private String chineseNumeralPattern = "";
        private final VectorFusion vectorFusion = new VectorFusion();

        public long getSyncTimeoutMs() {
            return syncTimeoutMs;
        }

        public void setSyncTimeoutMs(long syncTimeoutMs) {
            this.syncTimeoutMs = syncTimeoutMs;
        }

        public long getGenerationTimeoutSeconds() {
            return generationTimeoutSeconds;
        }

        public void setGenerationTimeoutSeconds(long generationTimeoutSeconds) {
            this.generationTimeoutSeconds = generationTimeoutSeconds;
        }

        public long getInFlightTtlMs() {
            return inFlightTtlMs;
        }

        public void setInFlightTtlMs(long inFlightTtlMs) {
            this.inFlightTtlMs = inFlightTtlMs;
        }

        public String getPromptTemplate() {
            return promptTemplate;
        }

        public void setPromptTemplate(String promptTemplate) {
            this.promptTemplate = promptTemplate;
        }

        public String getDirectFactPattern() {
            return directFactPattern;
        }

        public void setDirectFactPattern(String directFactPattern) {
            this.directFactPattern = directFactPattern;
        }

        public String getProperNounPattern() {
            return properNounPattern;
        }

        public void setProperNounPattern(String properNounPattern) {
            this.properNounPattern = properNounPattern;
        }

        public String getChineseNumeralPattern() {
            return chineseNumeralPattern;
        }

        public void setChineseNumeralPattern(String chineseNumeralPattern) {
            this.chineseNumeralPattern = chineseNumeralPattern;
        }

        public VectorFusion getVectorFusion() {
            return vectorFusion;
        }
    }

    public static class VectorFusion {
        private double originalWeight = 0.65;
        private double hypotheticalWeight = 0.35;

        public double getOriginalWeight() {
            return originalWeight;
        }

        public void setOriginalWeight(double originalWeight) {
            this.originalWeight = originalWeight;
        }

        public double getHypotheticalWeight() {
            return hypotheticalWeight;
        }

        public void setHypotheticalWeight(double hypotheticalWeight) {
            this.hypotheticalWeight = hypotheticalWeight;
        }
    }

    public static class Rerank {
        private int batchSize = 80;
        private int retryAttempts = 1;
        private final CircuitBreaker circuitBreaker = new CircuitBreaker();

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public int getRetryAttempts() {
            return retryAttempts;
        }

        public void setRetryAttempts(int retryAttempts) {
            this.retryAttempts = retryAttempts;
        }

        public CircuitBreaker getCircuitBreaker() {
            return circuitBreaker;
        }
    }

    public static class CircuitBreaker {
        private int failureThreshold = 5;
        private long openMs = 60000;

        public int getFailureThreshold() {
            return failureThreshold;
        }

        public void setFailureThreshold(int failureThreshold) {
            this.failureThreshold = failureThreshold;
        }

        public long getOpenMs() {
            return openMs;
        }

        public void setOpenMs(long openMs) {
            this.openMs = openMs;
        }
    }

    public static class Keyword {
        private double highThreshold = 0.72;
        private double lowThreshold = 0.25;
        private List<String> stopWords = new ArrayList<>(List.of("请", "请问", "帮我", "帮忙", "一下", "吗", "呢",
                "吧", "the", "a", "an", "please", "can", "could", "you"));

        public double getHighThreshold() {
            return highThreshold;
        }

        public void setHighThreshold(double highThreshold) {
            this.highThreshold = highThreshold;
        }

        public double getLowThreshold() {
            return lowThreshold;
        }

        public void setLowThreshold(double lowThreshold) {
            this.lowThreshold = lowThreshold;
        }

        public List<String> getStopWords() {
            return stopWords;
        }

        public void setStopWords(List<String> stopWords) {
            this.stopWords = stopWords;
        }
    }

    public static class Vector {
        private int maxLength = 1800;
        private int minLength = 200;
        private int overlapSize = 100;

        public int getMaxLength() {
            return maxLength;
        }

        public void setMaxLength(int maxLength) {
            this.maxLength = maxLength;
        }

        public int getMinLength() {
            return minLength;
        }

        public void setMinLength(int minLength) {
            this.minLength = minLength;
        }

        public int getOverlapSize() {
            return overlapSize;
        }

        public void setOverlapSize(int overlapSize) {
            this.overlapSize = overlapSize;
        }
    }

    public static class Doc {
        private final DocTask ocr = new DocTask();
        private final DocTask storage = new DocTask();

        public DocTask getOcr() {
            return ocr;
        }

        public DocTask getStorage() {
            return storage;
        }
    }

    public static class DocTask {
        private final Retry retry = new Retry();

        public Retry getRetry() {
            return retry;
        }
    }

    public static class Retry {
        private int maxAttempts = 3;
        private long delayMs = 2000;

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public long getDelayMs() {
            return delayMs;
        }

        public void setDelayMs(long delayMs) {
            this.delayMs = delayMs;
        }
    }
}
