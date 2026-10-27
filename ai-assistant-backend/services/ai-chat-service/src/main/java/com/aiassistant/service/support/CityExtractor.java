package com.aiassistant.service.support;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 城市名提取：从天气问句中识别城市，供本地天气回答与用户画像共用。
 *
 * @author yh
 * @date 2026-10-27
 */
public final class CityExtractor {

    /** 天气问句中的城市提取正则 */
    private static final Pattern CITY_PATTERN = Pattern.compile(
            "([\\u4e00-\\u9fa5]{2,8})(?:市|地区)?的?(?:今天|现在|明天|当前)?(?:实时)?(?:的)?天气");

    /** 疑问词/代词等伪城市 */
    private static final Pattern NOISE_CITY =
            Pattern.compile("(查|问|看|找|这|那|个|的|我|你|他|她|今|明|后|白|晚)+");

    private CityExtractor() {
    }

    /**
     * 提取消息中的首个城市名。
     *
     * @param message 用户消息
     * @return 城市名；未匹配或命中噪音词时为 null
     */
    public static String firstCity(String message) {
        if (message == null) {
            return null;
        }
        Matcher matcher = CITY_PATTERN.matcher(message);
        if (!matcher.find()) {
            return null;
        }
        String city = matcher.group(1);
        return isNoiseCity(city) ? null : city;
    }

    /**
     * 提取文本中出现的所有天气问句城市。
     *
     * @param text 待扫描文本
     * @return 城市列表（按出现顺序，已过滤噪音词，未去重）
     */
    public static List<String> findWeatherCities(String text) {
        List<String> cities = new ArrayList<>();
        if (text == null) {
            return cities;
        }
        Matcher matcher = CITY_PATTERN.matcher(text);
        while (matcher.find()) {
            String city = matcher.group(1);
            if (!isNoiseCity(city)) {
                cities.add(city);
            }
        }
        return cities;
    }

    /**
     * 判断提取到的片段是否为疑问词/代词等伪城市。
     *
     * @param city 候选城市
     * @return true=噪音词
     */
    public static boolean isNoiseCity(String city) {
        return city != null && NOISE_CITY.matcher(city).matches();
    }
}
