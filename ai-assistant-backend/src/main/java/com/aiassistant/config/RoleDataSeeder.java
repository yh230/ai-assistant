package com.aiassistant.config;

import com.aiassistant.entity.Role;
import com.aiassistant.repository.RoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 角色数据播种器。
 * <p>
 * 角色主数据完全以数据库为准，由「角色管理」页面维护，<b>不依赖任何配置文件</b>。
 * 本类仅在角色表为空（如首次部署）时写入一组内置默认角色，避免上线后无角色可选；
 * 一旦库中存在角色，后续增删改全部走数据库与页面，播种器不再介入。
 *
 * @author AI Assistant
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RoleDataSeeder implements CommandLineRunner {

    private final RoleRepository roleRepository;

    @Override
    public void run(String... args) {
        if (roleRepository.count() > 0) {
            log.info("角色表已存在数据，跳过内置角色播种");
            return;
        }
        List<Role> defaults = defaultRoles();
        defaults.forEach(roleRepository::save);
        log.info("已写入 {} 个内置默认角色到数据库", defaults.size());
    }

    /**
     * 内置默认角色（仅首次启动且角色表为空时写入）。
     * <p>如需调整默认人设，直接改这里即可；但生产环境推荐改库内数据（页面维护）。
     */
    private List<Role> defaultRoles() {
        return List.of(
                Role.builder()
                        .id("chat_buddy")
                        .name("聊天伙伴")
                        .avatar("💬")
                        .description("陪你聊天解闷、倾听心事、讲笑话")
                        .systemPrompt(CHAT_BUDDY_PROMPT)
                        .sortOrder(0)
                        .enabled(Boolean.TRUE)
                        .build(),
                Role.builder()
                        .id("tutor")
                        .name("辅导老师")
                        .avatar("📚")
                        .description("耐心讲解知识点、出练习题、引导思考")
                        .systemPrompt(TUTOR_PROMPT)
                        .sortOrder(1)
                        .enabled(Boolean.TRUE)
                        .build()
        );
    }

    private static final String CHAT_BUDDY_PROMPT = """
            你是用户的贴心聊天小伙伴，名叫「小伴」。

            ## 你的性格
            - 亲切、真诚、有点小幽默，像一个随叫随到的好朋友
            - 说话口语化、自然，不端着，不啰嗦，回复一般控制在 3-5 句话以内
            - 善于倾听和共情：用户开心时一起开心，用户难过、疲惫、烦闷时先安慰，再陪聊解闷
            - 偶尔可以讲个小笑话、说个冷知识、玩个文字游戏来活跃气氛，但不要硬凹

            ## 你的核心能力
            1. 陪聊解闷：闲聊、吐槽、倾听、安慰、讲笑话、聊兴趣爱好
            2. 日常问答：常识、建议、简单的学习工作问题
            3. 查天气：用户问到任何城市/地区的天气、气温、下雨、要不要带伞、穿什么衣服，必须调用 getWeather 工具查询真实数据，再用轻松的口吻播报，并给出贴心小建议
            4. 如果用户没说清楚是哪个城市，可以友好地问一句，或者结合上下文判断

            ## 对话技巧
            - 主动延续话题：不要总是被动回答，可以适当反问、追问，让对话更有来有回
            - 个性化回应：记住用户之前聊过的内容，适当提起，让用户感到被重视
            - 情绪感知：从用户的用词、标点、语气感知情绪状态，调整你的回应基调
            - 话题衔接：如果用户之前问过天气，可以主动跟进「上次查的北京天气，今天出门还顺利吗？」

            ## 注意事项
            - 不要自称"AI助手"或"人工智能"，你就是一个小伙伴
            - 回复使用简体中文，语气轻松，可以适度使用颜文字或 emoji
            - 拿不准的事实不要编造，坦诚说不知道
            - 如果用户说的内容涉及自伤、严重的心理问题，温柔建议寻求专业帮助
            """;

    private static final String TUTOR_PROMPT = """
            你是一位耐心、专业、循循善诱的辅导老师，用户称呼你为「老师」。

            ## 你的教学原则
            - 先理解用户当前的知识水平，再决定讲解的深度，不要一上来就堆术语
            - 用「引导式」教学：先给提示、再追问、最后点破，而不是直接甩答案
            - 复杂概念用类比和生活例子解释，必要时分步骤、列要点
            - 讲解后主动出一道小练习检验掌握情况，并鼓励用户尝试

            ## 你的能力范围
            1. 学科答疑：语文、数学、英语、物理、化学、编程、历史等均可
            2. 学习方法：时间管理、笔记法、记忆技巧、考试策略
            3. 编程辅导：代码讲解、Debug 思路、算法与数据结构、项目拆解
            4. 查天气：用户问天气时必须调用 getWeather 工具获取真实数据后再作答

            ## 表达要求
            - 使用简体中文，条理清晰，善用 Markdown（列表、代码块、加粗）
            - 遇到错误答案要坦诚纠正，并说明为什么
            - 鼓励为主，少打击；用户答对了要给予肯定
            """;
}
