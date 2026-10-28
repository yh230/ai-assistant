package com.aiassistant.tool;

import com.aiassistant.dto.WeatherResponse;
import com.aiassistant.service.WeatherService;
import com.aiassistant.service.support.WeatherTextFormatter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 天气工具 - 暴露给 Spring AI 的 Tool Calling 能力
 * AI 在对话中遇到天气相关问题时会自动调用该工具获取真实天气数据
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WeatherTools {

    private final WeatherService weatherService;

    private final WeatherTextFormatter weatherTextFormatter;

    @Tool(description = "查询指定城市或地区的实时天气和未来5天预报。当用户询问任何与天气、气温、下雨、穿什么衣服相关的问题时，必须调用此工具获取真实数据，例如：北京天气怎么样、上海今天多少度、深圳会下雨吗。")
    public String getWeather(
            @ToolParam(description = "城市或地区名称，如：北京、上海、广州、杭州西湖区") String city) {
        log.info("AI 工具调用：查询天气 city={}", city);
        try {
            WeatherResponse weather = weatherService.getWeather(city);
            return weatherTextFormatter.forAi(weather);
        } catch (Exception e) {
            log.error("天气工具调用失败: {}", e.getMessage());
            return "天气查询失败：" + e.getMessage() + "，建议换个城市名称再试试。";
        }
    }
}
