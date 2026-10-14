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
        /** 敏感词/句式列表（命中即拦截） */
        private List<String> keywords = new ArrayList<>();
        /** 命中后的标准提示文案 */
        private String blockedMessage = "该问题涉及诊疗建议，我仅能提供导诊参考，不能提供诊断或用药建议，请以执业医师面诊为准。";
    }

    @Data
    public static class Rag {
        /** 切片大小（token） */
        private int chunkSize = 800;
        /** 相似度阈值（低于该值不采用） */
        private double similarityThreshold = 0.5;
        /** 检索片段数上限 */
        private int topK = 4;
        /** 向量文件目录（按医院分文件 hospital_{id}.json） */
        private String vectorPath = "data/vector";
        /** 本地嵌入模型文件路径（空=使用框架默认并自动下载） */
        private String embeddingModelPath = "";
    }
}
