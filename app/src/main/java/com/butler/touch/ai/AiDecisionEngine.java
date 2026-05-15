package com.butler.touch.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI 决策引擎
 * 使用 DeepSeek API 分析屏幕截图并决定触摸操作位置
 */
public class AiDecisionEngine {
    private static final Logger logger = LoggerFactory.getLogger(AiDecisionEngine.class);

    private static final String DEEPSEEK_API_URL = "https://api.deepseek.com/v1/chat/completions";
    private static final String DEEPSEEK_VISION_URL = "https://api.deepseek.com/v1/chat/completions";

    private final String apiKey;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public AiDecisionEngine(String apiKey) {
        this.apiKey = apiKey;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 分析用户指令并返回触摸操作决策
     * @param userInstruction 用户指令（如"点击登录按钮"）
     * @param screenshotBase64 屏幕截图的 Base64 编码（可选）
     * @param screenWidth 屏幕宽度
     * @param screenHeight 屏幕高度
     * @return 触摸决策结果
     */
    public TouchDecision analyze(String userInstruction, String screenshotBase64,
                                  int screenWidth, int screenHeight) throws IOException, InterruptedException {
        logger.info("分析用户指令: {}", userInstruction);

        // 构建系统提示词
        String systemPrompt = buildSystemPrompt(screenWidth, screenHeight);

        // 构建用户消息
        String userMessage;
        if (screenshotBase64 != null && !screenshotBase64.isEmpty()) {
            userMessage = "请分析以下屏幕截图，并根据指令执行操作: " + userInstruction;
        } else {
            userMessage = "请根据以下指令决定触摸操作（注意：没有屏幕截图，请根据常识推断位置）: " + userInstruction;
        }

        // 构建 API 请求
        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("model", "deepseek-chat");
        requestBody.put("temperature", 0.1);

        var messages = requestBody.putArray("messages");

        // 系统消息
        var systemMsg = messages.addObject();
        systemMsg.put("role", "system");
        systemMsg.put("content", systemPrompt);

        // 用户消息
        var userMsg = messages.addObject();
        userMsg.put("role", "user");

        if (screenshotBase64 != null && !screenshotBase64.isEmpty()) {
            var content = userMsg.putArray("content");
            var textPart = content.addObject();
            textPart.put("type", "text");
            textPart.put("text", userMessage);

            var imagePart = content.addObject();
            imagePart.put("type", "image_url");
            var imageUrl = imagePart.putObject("image_url");
            imageUrl.put("url", "data:image/png;base64," + screenshotBase64);
        } else {
            userMsg.put("content", userMessage);
        }

        // 发送请求
        String requestJson = objectMapper.writeValueAsString(requestBody);
        logger.debug("API 请求: {}", requestJson);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(DEEPSEEK_API_URL))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                .timeout(Duration.ofSeconds(60))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            logger.error("API 调用失败: {} - {}", response.statusCode(), response.body());
            throw new RuntimeException("AI API 调用失败: " + response.statusCode());
        }

        // 解析响应
        JsonNode responseJson = objectMapper.readTree(response.body());
        String aiResponse = responseJson.path("choices").get(0).path("message").path("content").asText();

        logger.info("AI 响应: {}", aiResponse);

        return parseAiResponse(aiResponse, screenWidth, screenHeight);
    }

    /**
     * 构建系统提示词
     */
    private String buildSystemPrompt(int screenWidth, int screenHeight) {
        return """
                你是一个 Android 设备操作助手。你的任务是根据用户的指令，决定在屏幕上执行什么触摸操作。

                屏幕尺寸: %d x %d

                你需要返回一个 JSON 格式的决策结果，包含以下字段：
                - operation: 操作类型，可选值: "tap"(点击), "swipe"(滑动), "longPress"(长按), "doubleTap"(双击), "scroll"(滚动)
                - x: X 坐标 (0-%d)
                - y: Y 坐标 (0-%d)
                - endX: 滑动终点 X 坐标 (仅 swipe 操作需要)
                - endY: 滑动终点 Y 坐标 (仅 swipe 操作需要)
                - duration: 持续时间，毫秒 (仅 swipe/longPress 操作需要)
                - direction: 滚动方向，"up" 或 "down" (仅 scroll 操作需要)
                - description: 操作描述

                常见 UI 元素位置参考：
                - 顶部状态栏: y ≈ 0-50
                - 底部导航栏: y ≈ 屏幕高度 - 100 到 屏幕高度
                - 中央区域: x ≈ 屏幕宽度/2, y ≈ 屏幕高度/2
                - 返回按钮通常在左上角: x ≈ 50, y ≈ 100
                - 确认/下一步按钮通常在右下角

                请只返回 JSON，不要包含其他文字。
                """.formatted(screenWidth, screenHeight, screenWidth, screenHeight);
    }

    /**
     * 解析 AI 响应
     */
    private TouchDecision parseAiResponse(String aiResponse, int screenWidth, int screenHeight) {
        // 尝试提取 JSON
        String jsonStr = extractJson(aiResponse);

        try {
            JsonNode json = objectMapper.readTree(jsonStr);

            String operation = json.path("operation").asText("tap");
            int x = json.path("x").asInt(screenWidth / 2);
            int y = json.path("y").asInt(screenHeight / 2);
            String description = json.path("description").asText("");

            TouchDecision decision = new TouchDecision();
            decision.setOperation(operation);
            decision.setX(x);
            decision.setY(y);
            decision.setDescription(description);

            if ("swipe".equals(operation)) {
                decision.setEndX(json.path("endX").asInt(x));
                decision.setEndY(json.path("endY").asInt(y));
                decision.setDuration(json.path("duration").asLong(300));
            } else if ("longPress".equals(operation)) {
                decision.setDuration(json.path("duration").asLong(500));
            } else if ("scroll".equals(operation)) {
                decision.setDirection(json.path("direction").asText("down"));
            }

            return decision;
        } catch (Exception e) {
            logger.warn("解析 AI 响应失败，使用默认值: {}", e.getMessage());

            // 返回一个默认的点击操作
            TouchDecision decision = new TouchDecision();
            decision.setOperation("tap");
            decision.setX(screenWidth / 2);
            decision.setY(screenHeight / 2);
            decision.setDescription("默认点击屏幕中央");
            return decision;
        }
    }

    /**
     * 从文本中提取 JSON
     */
    private String extractJson(String text) {
        // 尝试找到 JSON 块
        Pattern pattern = Pattern.compile("\\{[^{}]*\\}", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            return matcher.group();
        }

        // 如果没有找到，尝试更复杂的匹配
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }

        return "{}";
    }
}