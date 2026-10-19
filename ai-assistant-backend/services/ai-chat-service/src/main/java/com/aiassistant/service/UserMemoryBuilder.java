package com.aiassistant.service;

import com.aiassistant.entity.ChatMessage;
import com.aiassistant.repository.ChatMessageRepository;
import com.aiassistant.repository.ChatSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 用户画像构建器 - 从当前登录人的历史对话中提取画像
 * <p>
 * 画像查询严格限定归属人（userId + identity），不做任何全库统计，
 * 避免把他人消息内容注入当前会话的系统提示词。
 *
 * @author yh
 * @date 2026-10-16
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class UserMemoryBuilder {

    /** 城市提取正则（天气问句） */
    private static final Pattern CITY_PATTERN =
            Pattern.compile("([\\u4e00-\\u9fa5]{2,8})(?:市|地区)?的?(?:今天|现在|明天|当前)?(?:实时)?天气");

    /** 常住城市提取正则（在某地工作/生活等陈述） */
    private static final Pattern RESIDENCE_PATTERN =
            Pattern.compile("(?:在|到|去)([\\u4e00-\\u9fa5]{2,4})(?:市|地区)?(?:工作|上班|生活|读书|上学)");

    /** 用户名提取正则 */
    private static final Pattern NAME_PATTERN =
            Pattern.compile("(?:我叫|我是|我的名字是|叫我)\\s*([\\u4e00-\\u9fa5]{2,4})");

    private final ChatMessageRepository messageRepository;
    private final ChatSessionRepository sessionRepository;

    /**
     * 构建当前登录人的跨会话画像（严格限定本人）
     *
     * @param userId 登录人 ID
     * @param identity 身份（staff/patient）
     * @return 画像文本；无数据或异常返回空串
     */
    public String build(Long userId, String identity) {
        try {
            long sessionCount = sessionRepository.countByUserIdAndIdentity(userId, identity);
            long messageCount = messageRepository.countByRoleAndOwner("user", userId, identity);
            if (sessionCount == 0) {
                return "";
            }

            List<ChatMessage> recentUserMessages =
                    messageRepository.findRecentByRoleAndOwner("user", userId, identity, Pageable.ofSize(50));
            Set<String> cities = new LinkedHashSet<>();
            Set<String> topics = new LinkedHashSet<>();
            String userName = null;

            for (ChatMessage m : recentUserMessages) {
                String content = m.getContent();
                if (content == null) {
                    continue;
                }

                Matcher cityMatcher = CITY_PATTERN.matcher(content);
                while (cityMatcher.find()) {
                    String city = cityMatcher.group(1);
                    if (!isNoiseCity(city)) {
                        cities.add(city);
                    }
                }

                Matcher residenceMatcher = RESIDENCE_PATTERN.matcher(content);
                while (residenceMatcher.find()) {
                    String city = residenceMatcher.group(1);
                    if (!isNoiseCity(city)) {
                        cities.add(city);
                    }
                }

                if (userName == null) {
                    Matcher nameMatcher = NAME_PATTERN.matcher(content);
                    if (nameMatcher.find()) {
                        userName = nameMatcher.group(1);
                    }
                }

                if (content.contains("游戏")) topics.add("游戏");
                if (content.contains("电影")) topics.add("电影");
                if (content.contains("工作")) topics.add("工作");
                if (content.contains("学习")) topics.add("学习");
                if (content.contains("美食") || content.contains("吃")) topics.add("美食");
                if (content.contains("旅游") || content.contains("旅行")) topics.add("旅游");
                if (content.contains("音乐")) topics.add("音乐");
                if (content.contains("运动") || content.contains("健身")) topics.add("运动");
                if (content.contains("代码") || content.contains("编程") || content.contains("程序")) topics.add("编程");
            }

            StringBuilder sb = new StringBuilder();
            sb.append("- 你们之前已经聊过 ").append(sessionCount).append(" 次，共 ").append(messageCount).append(" 条对话");
            if (userName != null) {
                sb.append("\n- 用户可能叫「").append(userName).append("」，可以在合适的时候自然地称呼");
            }
            if (!cities.isEmpty()) {
                List<String> cityList = new ArrayList<>(cities);
                sb.append("\n- 用户经常关心的城市：")
                        .append(String.join("、", cityList.size() > 5 ? cityList.subList(0, 5) : cityList));
            }
            if (!topics.isEmpty()) {
                sb.append("\n- 用户感兴趣的话题：").append(String.join("、", topics));
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("构建用户记忆失败: {}", e.getMessage());
            return "";
        }
    }

    /**
     * 判断提取到的片段是否为疑问词/代词等伪城市
     *
     * @param city 提取到的候选城市
     * @return 命中噪音词返回 true
     */
    private boolean isNoiseCity(String city) {
        return city.matches("(查|问|看|找|这|那|个|的|我|你|他|她|今|明|后|白|晚)+");
    }
}
