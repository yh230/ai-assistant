package com.aiassistant.service.impl;

import com.aiassistant.dto.WeatherResponse;
import com.aiassistant.service.WeatherService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * 天气服务实现 - 使用高德开放平台 API（Web服务 Key）
 * <p>
 * 变更点（替换 Open-Meteo，因网络出口上网行为管理设备拦截 open-meteo.com）：
 * <ul>
 *     <li>两步调用：地理编码（城市名→adcode）→ 天气查询（实况 + 4天预报）</li>
 *     <li>高德所有响应 HTTP 均为 200，成败需按 JSON 的 status 字段（"1"=成功）判断</li>
 *     <li>高德不提供体感温度/降水量/风速(km/h)/降水概率，对应字段置 null，调用方已判空容错</li>
 * </ul>
 * 注意：必须用 {@link URI} 对象传给 RestTemplate，不能用 String URL。
 * RestTemplate 对 String URL 会再次 URI 编码，导致中文城市名被双重编码。
 *
 * @author yh
 * @date 2026-10-10
 */
@Slf4j
@Service
public class WeatherServiceImpl implements WeatherService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public WeatherServiceImpl(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Value("${weather.amap-key}")
    private String amapKey;

    @Value("${weather.geocoding-url}")
    private String geocodingUrl;

    @Value("${weather.weather-url}")
    private String weatherUrl;

    @Override
    public WeatherResponse getWeather(String city) {
        if (city == null || city.trim().isEmpty()) {
            return WeatherResponse.builder()
                    .success(false)
                    .city(city)
                    .error("城市名不能为空")
                    .build();
        }
        String trimmedCity = city.trim();
        try {
            // 第一步：地理编码 - 城市名 → adcode
            // 用 UriComponentsBuilder 构建 URI，避免 RestTemplate 对 String URL 二次编码
            URI geoUri = UriComponentsBuilder
                    .fromHttpUrl(geocodingUrl)
                    .queryParam("address", trimmedCity)
                    .queryParam("key", amapKey)
                    .build()
                    .toUri();

            log.info("调用高德地理编码API: {}", geoUri);

            String geoResponse = restTemplate.getForObject(geoUri, String.class);
            JsonNode geoRoot = objectMapper.readTree(geoResponse);

            // 高德约定：HTTP 200 但 status="0" 表示业务失败（key 无效/城市不存在等）
            if (!"1".equals(geoRoot.path("status").asText())) {
                String info = geoRoot.path("info").asText("地理编码失败");
                log.error("高德地理编码业务失败: city={}, info={}", trimmedCity, info);
                return WeatherResponse.builder()
                        .success(false)
                        .city(trimmedCity)
                        .error("天气服务暂不可用：" + info)
                        .build();
            }

            JsonNode geocodes = geoRoot.path("geocodes");
            if (geocodes.isMissingNode() || geocodes.isEmpty()) {
                return WeatherResponse.builder()
                        .success(false)
                        .city(trimmedCity)
                        .error("未找到城市: " + trimmedCity + "，请确认城市名称是否正确")
                        .build();
            }

            JsonNode location = geocodes.get(0);
            String adcode = location.path("adcode").asText();
            String cityName = location.path("city").asText(trimmedCity);
            if (cityName == null || cityName.isEmpty() || "[]".equals(cityName)) {
                cityName = location.path("province").asText(trimmedCity);
            }
            String province = location.path("province").asText("");

            // 第二步：天气实况（extensions=base，默认）
            URI nowUri = UriComponentsBuilder
                    .fromHttpUrl(weatherUrl)
                    .queryParam("city", adcode)
                    .queryParam("key", amapKey)
                    .build()
                    .toUri();

            log.info("调用高德天气实况API: {}", nowUri);
            String nowResponse = restTemplate.getForObject(nowUri, String.class);
            JsonNode nowRoot = objectMapper.readTree(nowResponse);

            if (!"1".equals(nowRoot.path("status").asText())) {
                String info = nowRoot.path("info").asText("天气查询失败");
                log.error("高德天气实况业务失败: city={}, info={}", trimmedCity, info);
                return WeatherResponse.builder()
                        .success(false)
                        .city(trimmedCity)
                        .error("天气服务暂不可用：" + info)
                        .build();
            }

            JsonNode lives = nowRoot.path("lives");
            if (lives.isMissingNode() || lives.isEmpty()) {
                return WeatherResponse.builder()
                        .success(false)
                        .city(trimmedCity)
                        .error("未查询到 " + trimmedCity + " 的实况天气")
                        .build();
            }

            JsonNode live = lives.get(0);
            WeatherResponse response = WeatherResponse.builder()
                    .success(true)
                    .city(cityName)
                    .country(province)
                    // 高德字段均为字符串，手动转数值；风力为等级值（如 "≤3"），非 km/h，不填 windSpeed
                    .currentTemperature(parseDouble(live.path("temperature").asText()))
                    .humidity(parseInt(live.path("humidity").asText()))
                    .weatherDescription(live.path("weather").asText())
                    .windDirection(toWindDirection(live.path("winddirection").asText()))
                    .build();

            // 第三步：未来预报（extensions=all，返回今天起 4 天）
            URI forecastUri = UriComponentsBuilder
                    .fromHttpUrl(weatherUrl)
                    .queryParam("city", adcode)
                    .queryParam("key", amapKey)
                    .queryParam("extensions", "all")
                    .build()
                    .toUri();

            log.info("调用高德天气预报API: {}", forecastUri);
            String forecastResponse = restTemplate.getForObject(forecastUri, String.class);
            JsonNode forecastRoot = objectMapper.readTree(forecastResponse);

            if ("1".equals(forecastRoot.path("status").asText())) {
                JsonNode casts = forecastRoot.path("forecasts").path(0).path("casts");
                if (casts.isArray() && !casts.isEmpty()) {
                    List<WeatherResponse.DailyForecast> forecasts = new ArrayList<>();
                    for (JsonNode cast : casts) {
                        String dayWeather = cast.path("dayweather").asText();
                        String nightWeather = cast.path("nightweather").asText();
                        // 白天/夜间天气不同时拼成「晴转多云」
                        String description = dayWeather.equals(nightWeather)
                                ? dayWeather : dayWeather + "转" + nightWeather;
                        forecasts.add(WeatherResponse.DailyForecast.builder()
                                .date(cast.path("date").asText())
                                .maxTemperature(parseDouble(cast.path("daytemp").asText()))
                                .minTemperature(parseDouble(cast.path("nighttemp").asText()))
                                .description(description)
                                .build());
                    }
                    response.setDailyForecasts(forecasts);
                }
            }

            log.info("天气查询成功: city={}, temp={}°C, 天气={}",
                    cityName, response.getCurrentTemperature(), response.getWeatherDescription());
            return response;

        } catch (org.springframework.web.client.HttpClientErrorException | org.springframework.web.client.HttpServerErrorException e) {
            int status = e.getStatusCode().value();
            log.error("高德天气API返回HTTP错误: city={}, status={}, body={}", trimmedCity, status, e.getResponseBodyAsString());
            return WeatherResponse.builder()
                    .success(false)
                    .city(trimmedCity)
                    .error("天气服务返回错误(" + status + ")")
                    .build();
        } catch (Exception e) {
            log.error("查询天气失败: city={}, error={}", trimmedCity, e.getMessage(), e);
            return WeatherResponse.builder()
                    .success(false)
                    .city(trimmedCity)
                    .error("查询天气失败: " + e.getMessage())
                    .build();
        }
    }

    /**
     * 字符串转 Double，空/非法返回 null
     *
     * @param value 待解析字符串
     * @return 解析结果，空或非法时返回 null
     */
    private Double parseDouble(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            log.debug("数值解析失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 字符串转 Integer，空/非法返回 null
     *
     * @param value 待解析字符串
     * @return 解析结果，空或非法时返回 null
     */
    private Integer parseInt(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            log.debug("数值解析失败: {}", e.getMessage());
            return null;
        }
    }

    /** 高德风向（如"东北"）转"东北风"；空或"无风向"返回 null */
    private String toWindDirection(String direction) {
        if (direction == null || direction.isEmpty() || "无风向".equals(direction)
                || "旋转".equals(direction) || "静风".equals(direction)) {
            return null;
        }
        return direction + "风";
    }
}
