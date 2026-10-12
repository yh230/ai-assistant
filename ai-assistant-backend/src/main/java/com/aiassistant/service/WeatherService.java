package com.aiassistant.service;

import com.aiassistant.dto.WeatherResponse;

/**
 * 天气服务接口
 */
public interface WeatherService {

    /**
     * 查询指定城市的天气
     */
    WeatherResponse getWeather(String city);
}
