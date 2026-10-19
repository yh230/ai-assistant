package com.aiassistant.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * AI 业务配置（Nacos common-shared.yaml 下发，禁止业务代码硬编码 prompt/文案/参数）
 *
 * @author yh
 * @date 2026-10-13
 */
@Data
@ConfigurationProperties(prefix = "ai")
public class AiProperties {

    /** 会话类型人设与约束模板 */
    private Prompt prompt = new Prompt();

    /** 免责声明文案（每次 AI 回答末尾强制追加） */
    private String disclaimer = "> AI内容仅供参考，不能替代执业医师诊断，请前往线下医院就诊。";

    /** 输出风控配置 */
    private Safety safety = new Safety();

    /** RAG 检索与切片参数 */
    private Rag rag = new Rag();

    /** 对话运行参数 */
    private Chat chat = new Chat();

    @Data
    public static class Prompt {
        /** 普通闲聊人设 */
        private String chat = "你是医院场景的 AI 助手，请友好、简洁地回应患者。";
        /** AI 导诊人设与硬性约束 */
        private String triage = "你是医院 AI 导诊助手...（完整约束见 Nacos 配置）";
        /** 知识库问答人设 */
        private String rag = "你是医院知识库问答助手...";
    }

    @Data
    public static class Safety {
        /** 敏感词/句式列表（命中即拦截；内置默认与 Nacos 样例一致，未装 Nacos 亦生效） */
        private List<String> keywords = new ArrayList<>(List.of(
                "给我开药", "开点药", "开处方", "确诊", "诊断为", "吃什么药", "用药建议", "开什么药"));

        /** 命中后的标准提示文案 */
        private String blockedMessage = "该问题涉及诊疗建议，我仅能提供导诊参考，不能提供诊断或用药建议，请以执业医师面诊为准。";

        /** 急症红线词表（命中即短路返回紧急就医指引；口语化变体与书面词并存） */
        private List<String> emergencyKeywords = new ArrayList<>(List.of(
                "胸痛", "胸口疼", "心梗", "呼吸困难", "喘不上气", "喘不过气", "窒息",
                "意识不清", "昏迷", "抽搐", "偏瘫", "口齿不清",
                "大出血", "咯血", "呕血", "剧烈头痛", "农药中毒", "服毒", "自杀", "割腕",
                "车祸", "高处坠落"));

        /** 急症红线标准指引文案 */
        private String emergencyMessage = "【紧急提示】您描述的情况可能属于急症，请立即拨打 120 或尽快前往最近医院急诊科就诊，不要等待线上回复。";
    }

    @Data
    public static class Rag {
        /** 切片大小（token） */
        private int chunkSize = 800;
        /** 相似度阈值（低于该值不采用） */
        private double similarityThreshold = 0.5;
        /** 检索片段数上限 */
        private int topK = 4;
        /** 引用来源条数上限（RAG 回答附带的参考来源条数） */
        private int maxReferences = 3;
        /** 向量文件目录（按医院分文件 hospital_{id}.json） */
        private String vectorPath = "data/vector";
        /** 本地嵌入模型文件路径（空=使用框架默认并自动下载） */
        private String embeddingModelPath = "";
        /** 向量存储实现：simple | elasticsearch */
        private String store = "simple";
        /** 切片重叠比例（0~0.5） */
        private double chunkOverlap = 0.15;
        /** 嵌入维度（与索引 mapping 一致） */
        private int embeddingDimensions = 384;
        /** groundedness 覆盖率阈值 */
        private double groundednessThreshold = 0.35;
        /** ES 配置 */
        private Elasticsearch elasticsearch = new Elasticsearch();
    }

    /**
     * Elasticsearch 连接与索引配置。
     *
     * @author yh
     * @date 2026-10-19
     */
    @Data
    public static class Elasticsearch {
        /** ES 服务地址 */
        private String uri = "http://localhost:9200";
        /** 知识片段索引名 */
        private String indexName = "ai_knowledge_chunk_v1";
        /** 分词器名称 */
        private String analyzer = "cjk_ngram";
    }

    /**
     * 对话运行参数（流式超时、token 预算、工具调用上限）。
     *
     * @author yh
     * @date 2026-10-19
     */
    @Data
    public static class Chat {
        /** token 间隔超时（秒） */
        private int idleTimeoutSeconds = 60;
        /** 单次回答总超时（秒） */
        private int overallTimeoutSeconds = 180;
        /** 会话上下文 token 预算 */
        private int contextTokenBudget = 3000;
        /** 单条用户消息最大字符数 */
        private int maxInputChars = 4000;
        /** 单次请求工具调用轮数上限 */
        private int toolMaxIterations = 5;
        /** 单工具执行超时（秒） */
        private int toolTimeoutSeconds = 5;
        /** 单次回答最大输出 token */
        private int maxTokens = 1024;
    }
}
