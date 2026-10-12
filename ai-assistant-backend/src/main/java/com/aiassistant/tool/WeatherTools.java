package com.aiassistant.tool;

import com.aiassistant.dto.WeatherResponse;
import com.aiassistant.service.WeatherService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 天气工具 - 暴露给 Spring AI 的 Tool Calling 能力
 * AI 在对话中遇到天气相关问题时会自动调用该工具获取真实天气数据
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WeatherTools {

    private final WeatherService weatherService;

    @Tool(description = "查询指定城市或地区的实时天气和未来5天预报。当用户询问任何与天气、气温、下雨、穿什么衣服相关的问题时，必须调用此工具获取真实数据，例如：北京天气怎么样、上海今天多少度、深圳会下雨吗。")
    public String getWeather(
            @ToolParam(description = "城市或地区名称，如：北京、上海、广州、杭州西湖区") String city) {
        log.info("AI 工具调用：查询天气 city={}", city);
        try {
            WeatherResponse weather = weatherService.getWeather(city);
            return formatWeatherForAi(weather);
        } catch (Exception e) {
            log.error("天气工具调用失败: {}", e.getMessage());
            return "天气查询失败：" + e.getMessage() + "，建议换个城市名称再试试。";
        }
    }

    /**
     * 将结构化天气数据转换为适合大模型阅读的自然语言摘要
     */
    private String formatWeatherForAi(WeatherResponse weather) {
        if (weather == null || !weather.isSuccess()) {
            return "没有查到「" + (weather != null ? weather.getCity() : "") + "」的天气信息，"
                    + (weather != null && weather.getError() != null ? weather.getError() : "请确认城市名称是否正确。");
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("【%s%s实时天气】%s，气温 %.1f℃（体感 %.1f℃），湿度 %d%%",
                weather.getCity(),
                weather.getCountry() != null && !weather.getCountry().isEmpty() ? "(" + weather.getCountry() + ")" : "",
                weather.getWeatherDescription(),
                weather.getCurrentTemperature(),
                weather.getApparentTemperature() != null ? weather.getApparentTemperature() : weather.getCurrentTemperature(),
                weather.getHumidity() != null ? weather.getHumidity() : 0));

        if (weather.getPrecipitation() != null && weather.getPrecipitation() > 0) {
            sb.append(String.format("，当前降水量 %.1fmm", weather.getPrecipitation()));
        }
        if (weather.getWindSpeed() != null) {
            sb.append(String.format("，%s %.1fkm/h", weather.getWindDirection(), weather.getWindSpeed()));
        }

        List<WeatherResponse.DailyForecast> forecasts = weather.getDailyForecasts();
        if (forecasts != null && !forecasts.isEmpty()) {
            sb.append("。未来几天预报：");
            for (int i = 0; i < forecasts.size() && i < 3; i++) {
                WeatherResponse.DailyForecast f = forecasts.get(i);
                sb.append(String.format("%s %s %.0f~%.0f℃",
                        f.getDate(), f.getDescription(), f.getMinTemperature(), f.getMaxTemperature()));
                if (f.getPrecipitationProbability() != null && f.getPrecipitationProbability() > 30) {
                    sb.append(String.format("(降水概率%.0f%%)", f.getPrecipitationProbability()));
                }
                sb.append(i < Math.min(forecasts.size(), 3) - 1 ? "；" : "。");
            }
        }
        return sb.toString();
    }
}
