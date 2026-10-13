package com.aiassistant.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.util.List;

/**
 * 天气响应 DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WeatherResponse {

    private boolean success;
    private String city;
    private String country;
    private Double latitude;
    private Double longitude;

    /** 当前温度（摄氏度） */
    private Double currentTemperature;

    /** 体感温度 */
    private Double apparentTemperature;

    /** 相对湿度百分比 */
    private Integer humidity;

    /** 降水量（mm） */
    private Double precipitation;

    /** 天气代码 */
    private Integer weatherCode;

    /** 天气描述 */
    private String weatherDescription;

    /** 风速（km/h） */
    private Double windSpeed;

    /** 风向 */
    private String windDirection;

    /** 未来天气预报 */
    private List<DailyForecast> dailyForecasts;

    /** 错误信息 */
    private String error;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class DailyForecast {
        private String date;
        private Double maxTemperature;
        private Double minTemperature;
        private Integer weatherCode;
        private String description;
        private Double precipitationProbability;
    }
}
