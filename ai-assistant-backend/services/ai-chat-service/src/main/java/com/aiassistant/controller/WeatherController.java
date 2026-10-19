package com.aiassistant.controller;

import com.common.result.Result;
import com.aiassistant.dto.WeatherResponse;
import com.aiassistant.service.WeatherService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 天气查询接口
 *
 * @author yh
 * @date 2026-10-10
 */
@Slf4j
@RestController
@RequestMapping("/api/weather")
@RequiredArgsConstructor
public class WeatherController {

    private final WeatherService weatherService;

    /**
     * 查询指定城市的天气
     * GET /api/weather?city=北京
     *
     * @param city 城市名称
     * @return 天气数据
     */
    @GetMapping
    public Result<WeatherResponse> getWeather(@RequestParam String city) {
        log.info("查询天气: city={}", city);
        if (city == null || city.trim().isEmpty()) {
            return Result.fail(400, "城市名不能为空");
        }
        WeatherResponse response = weatherService.getWeather(city.trim());
        return Result.success(response);
    }
}
