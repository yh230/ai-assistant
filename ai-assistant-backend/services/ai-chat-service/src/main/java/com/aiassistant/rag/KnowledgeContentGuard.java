package com.aiassistant.rag;

import com.common.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 知识库内容注入防护：拒绝试图覆盖系统指令的文档
 *
 * @author yh
 * @date 2026-10-19
 */
@Component
public class KnowledgeContentGuard {

    /** 常见提示词注入特征 */
    private static final List<Pattern> PATTERNS = List.of(
            Pattern.compile("忽略(以上|之前|所有|前面).{0,10}(指令|规则)"),
            Pattern.compile("ignore\\s+(all\\s+)?(previous|above)\\s+instructions", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(system\\s*prompt|系统提示词)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("你现在是.{0,10}(医生|管理员|系统)"),
            Pattern.compile("(执行|运行).{0,6}(命令|脚本)"));

    /**
     * 校验文档文本，命中注入特征则拒绝上传
     *
     * @param text 文档全文
     * @throws BusinessException 命中疑似提示词注入内容
     */
    public void assertSafe(String text) {
        if (text == null) {
            return;
        }
        for (Pattern pattern : PATTERNS) {
            if (pattern.matcher(text).find()) {
                throw new BusinessException("文档包含疑似提示词注入内容，已拒绝上传");
            }
        }
    }
}
