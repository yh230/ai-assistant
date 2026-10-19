package com.aiassistant.service.support;

/**
 * 聊天回复落库内容决策：把「输出风控/降级状态」到「持久化正文与引用」的映射抽成纯函数，便于单测覆盖
 * <p>
 * 设计要点：
 * <ul>
 *     <li><b>正文</b>：输出侧风控命中时只持久化标准拦截文案，绝不落原始（可能不安全的）部分正文；</li>
 *     <li><b>引用</b>：仅在模型作答、未降级、未被拦截且有引用时持久化，避免降级/拦截时来源误导。</li>
 * </ul>
 *
 * @author yh
 * @date 2026-10-19
 */
public final class ChatPersistContent {

    private ChatPersistContent() {
    }

    /**
     * 计算落库正文
     *
     * @param blocked        输出侧是否命中风控
     * @param blockedMessage 风控标准文案（命中时使用）
     * @param full           已生成的回复正文
     * @param disclaimer     免责声明
     * @return 落库正文：命中风控时为标准文案，否则为「正文 + 空行 + 免责」
     */
    public static String body(boolean blocked, String blockedMessage, String full, String disclaimer) {
        if (blocked) {
            return blockedMessage;
        }
        return (full == null ? "" : full) + "\n\n" + (disclaimer == null ? "" : disclaimer);
    }

    /**
     * 计算落库引用 JSON
     *
     * @param blocked       输出侧是否命中风控
     * @param fallback      是否降级本地引擎
     * @param referenceJson 引用来源 JSON（无来源为空串或 null）
     * @return 可持久化的引用 JSON；拦截、降级或无引用时返回 null
     */
    public static String references(boolean blocked, boolean fallback, String referenceJson) {
        if (blocked || fallback || referenceJson == null || referenceJson.isEmpty()) {
            return null;
        }
        return referenceJson;
    }
}
