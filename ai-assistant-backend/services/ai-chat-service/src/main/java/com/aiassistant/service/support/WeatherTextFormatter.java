package com.aiassistant.service.support;

import com.aiassistant.dto.WeatherResponse;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 天气文本格式化：分别产出面向大模型的摘要与本地降级话术。
 *
 * @author yh
 * @date 2026-10-27
 */
@Component
public class WeatherTextFormatter {

    /**
     * 将结构化天气数据转换为适合大模型阅读的自然语言摘要。
     *
     * @param weather 天气数据
     * @return 模型可读摘要（查询失败时返回失败说明）
     */
    public String forAi(WeatherResponse weather) {
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

    /**
     * 将结构化天气数据转换为本地降级话术。
     *
     * @param weather 查询成功的天气数据
     * @return 面向用户的本地天气回复
     */
    public String forLocal(WeatherResponse weather) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%s现在%s，气温 %.1f℃", weather.getCity(), weather.getWeatherDescription(),
                weather.getCurrentTemperature()));
        if (weather.getApparentTemperature() != null) {
            sb.append(String.format("（体感 %.1f℃）", weather.getApparentTemperature()));
        }
        if (weather.getHumidity() != null) {
            sb.append(String.format("，湿度 %d%%", weather.getHumidity()));
        }
        if (weather.getWindSpeed() != null) {
            sb.append(String.format("，%s %.1fkm/h", weather.getWindDirection(), weather.getWindSpeed()));
        }
        sb.append("。");

        double temp = weather.getCurrentTemperature();
        if (weather.getPrecipitation() != null && weather.getPrecipitation() > 0) {
            sb.append("外面在下雨，出门记得带伞哦 ☔");
        } else if (temp <= 5) {
            sb.append("天儿挺冷的，多穿点别冻着~ 🧣");
        } else if (temp >= 32) {
            sb.append("有点热，注意防暑多喝水 🥤");
        } else {
            sb.append("天气还不错，适合出去走走 😊");
        }

        if (weather.getDailyForecasts() != null && weather.getDailyForecasts().size() > 1) {
            WeatherResponse.DailyForecast tomorrow = weather.getDailyForecasts().get(1);
            sb.append(String.format("明天%s，%.0f~%.0f℃。",
                    tomorrow.getDescription(), tomorrow.getMinTemperature(), tomorrow.getMaxTemperature()));
        }
        return sb.toString();
    }
}
