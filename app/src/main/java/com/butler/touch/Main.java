package com.butler.touch;

import com.butler.touch.ai.AiDecisionEngine;
import com.butler.touch.ai.TouchDecision;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.Executors;

/**
 * 触摸模拟服务主入口
 * 提供 HTTP API 供 Python Butler 插件调用
 */
public class Main {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static TouchSimulator touchSimulator;
    private static AiDecisionEngine aiEngine;
    private static AdbExecutor adbExecutor;

    public static void main(String[] args) throws IOException {
        int port = 8765;
        String apiKey = System.getenv("DEEPSEEK_API_KEY");

        // 解析命令行参数
        for (int i = 0; i < args.length; i++) {
            if ("--port".equals(args[i]) && i + 1 < args.length) {
                port = Integer.parseInt(args[i + 1]);
            } else if ("--api-key".equals(args[i]) && i + 1 < args.length) {
                apiKey = args[i + 1];
            }
        }

        // 初始化组件
        adbExecutor = new AdbExecutor();
        touchSimulator = new TouchSimulator(adbExecutor);

        if (apiKey != null && !apiKey.isEmpty()) {
            aiEngine = new AiDecisionEngine(apiKey);
            logger.info("AI 决策引擎已初始化");
        } else {
            logger.warn("未设置 DEEPSEEK_API_KEY，AI 功能不可用");
        }

        // 启动 HTTP 服务器
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newCachedThreadPool());

        // 注册路由
        server.createContext("/api/devices", new DevicesHandler());
        server.createContext("/api/connect", new ConnectHandler());
        server.createContext("/api/tap", new TapHandler());
        server.createContext("/api/swipe", new SwipeHandler());
        server.createContext("/api/longpress", new LongPressHandler());
        server.createContext("/api/doubletap", new DoubleTapHandler());
        server.createContext("/api/scroll", new ScrollHandler());
        server.createContext("/api/keyevent", new KeyEventHandler());
        server.createContext("/api/text", new TextHandler());
        server.createContext("/api/screenshot", new ScreenshotHandler());
        server.createContext("/api/ai/analyze", new AiAnalyzeHandler());
        server.createContext("/api/ai/execute", new AiExecuteHandler());
        server.createContext("/api/health", new HealthHandler());

        server.start();

        logger.info("触摸模拟服务已启动，端口: {}", port);
        logger.info("API 文档:");
        logger.info("  GET  /api/devices      - 获取设备列表");
        logger.info("  POST /api/connect      - 连接设备");
        logger.info("  POST /api/tap          - 点击");
        logger.info("  POST /api/swipe        - 滑动");
        logger.info("  POST /api/longpress    - 长按");
        logger.info("  POST /api/doubletap    - 双击");
        logger.info("  POST /api/scroll       - 滚动");
        logger.info("  POST /api/keyevent     - 按键");
        logger.info("  POST /api/text         - 输入文本");
        logger.info("  GET  /api/screenshot   - 截图");
        logger.info("  POST /api/ai/analyze   - AI 分析");
        logger.info("  POST /api/ai/execute   - AI 执行");
        logger.info("  GET  /api/health       - 健康检查");
    }

    // ==================== 处理器 ====================

    static class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            sendResponse(exchange, 200, "{\"status\":\"ok\"}");
        }
    }

    static class DevicesHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                var devices = adbExecutor.getDevices();
                String json = objectMapper.writeValueAsString(devices);
                sendResponse(exchange, 200, json);
            } catch (Exception e) {
                sendResponse(exchange, 500, "{\"error\":\"" + e.getMessage() + "\"}");
            }
        }
    }

    static class ConnectHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                String body = readBody(exchange);
                var request = objectMapper.readTree(body);
                String deviceId = request.path("deviceId").asText();

                adbExecutor.setDeviceId(deviceId);
                touchSimulator.setDevice(deviceId).initialize();

                sendResponse(exchange, 200, "{\"success\":true,\"deviceId\":\"" + deviceId + "\"}");
            } catch (Exception e) {
                sendResponse(exchange, 500, "{\"error\":\"" + e.getMessage() + "\"}");
            }
        }
    }

    static class TapHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                String body = readBody(exchange);
                var request = objectMapper.readTree(body);
                int x = request.path("x").asInt();
                int y = request.path("y").asInt();

                TouchResult result = touchSimulator.tap(x, y);
                sendResponse(exchange, 200, objectMapper.writeValueAsString(result));
            } catch (Exception e) {
                sendResponse(exchange, 500, "{\"error\":\"" + e.getMessage() + "\"}");
            }
        }
    }

    static class SwipeHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                String body = readBody(exchange);
                var request = objectMapper.readTree(body);
                int startX = request.path("startX").asInt();
                int startY = request.path("startY").asInt();
                int endX = request.path("endX").asInt();
                int endY = request.path("endY").asInt();
                long duration = request.path("duration").asLong(300);

                TouchResult result = touchSimulator.swipe(startX, startY, endX, endY, duration);
                sendResponse(exchange, 200, objectMapper.writeValueAsString(result));
            } catch (Exception e) {
                sendResponse(exchange, 500, "{\"error\":\"" + e.getMessage() + "\"}");
            }
        }
    }

    static class LongPressHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                String body = readBody(exchange);
                var request = objectMapper.readTree(body);
                int x = request.path("x").asInt();
                int y = request.path("y").asInt();
                long duration = request.path("duration").asLong(500);

                TouchResult result = touchSimulator.longPress(x, y, duration);
                sendResponse(exchange, 200, objectMapper.writeValueAsString(result));
            } catch (Exception e) {
                sendResponse(exchange, 500, "{\"error\":\"" + e.getMessage() + "\"}");
            }
        }
    }

    static class DoubleTapHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                String body = readBody(exchange);
                var request = objectMapper.readTree(body);
                int x = request.path("x").asInt();
                int y = request.path("y").asInt();
                long interval = request.path("interval").asLong(100);

                TouchResult result = touchSimulator.doubleTap(x, y, interval);
                sendResponse(exchange, 200, objectMapper.writeValueAsString(result));
            } catch (Exception e) {
                sendResponse(exchange, 500, "{\"error\":\"" + e.getMessage() + "\"}");
            }
        }
    }

    static class ScrollHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                String body = readBody(exchange);
                var request = objectMapper.readTree(body);
                String direction = request.path("direction").asText("down");
                int distance = request.path("distance").asInt(300);

                TouchSimulator.ScrollDirection dir = "up".equals(direction)
                        ? TouchSimulator.ScrollDirection.UP
                        : TouchSimulator.ScrollDirection.DOWN;

                TouchResult result = touchSimulator.scroll(dir, distance);
                sendResponse(exchange, 200, objectMapper.writeValueAsString(result));
            } catch (Exception e) {
                sendResponse(exchange, 500, "{\"error\":\"" + e.getMessage() + "\"}");
            }
        }
    }

    static class KeyEventHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                String body = readBody(exchange);
                var request = objectMapper.readTree(body);
                int keyCode = request.path("keyCode").asInt();

                TouchResult result = touchSimulator.keyEvent(keyCode);
                sendResponse(exchange, 200, objectMapper.writeValueAsString(result));
            } catch (Exception e) {
                sendResponse(exchange, 500, "{\"error\":\"" + e.getMessage() + "\"}");
            }
        }
    }

    static class TextHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                String body = readBody(exchange);
                var request = objectMapper.readTree(body);
                String text = request.path("text").asText();

                TouchResult result = touchSimulator.text(text);
                sendResponse(exchange, 200, objectMapper.writeValueAsString(result));
            } catch (Exception e) {
                sendResponse(exchange, 500, "{\"error\":\"" + e.getMessage() + "\"}");
            }
        }
    }

    static class ScreenshotHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                String devicePath = "/sdcard/screenshot.png";
                String localPath = "/tmp/screenshot.png";

                adbExecutor.takeScreenshot(devicePath);
                adbExecutor.pullFile(devicePath, localPath);

                // 读取文件并转为 Base64
                java.nio.file.Path path = java.nio.file.Paths.get(localPath);
                byte[] bytes = java.nio.file.Files.readAllBytes(path);
                String base64 = Base64.getEncoder().encodeToString(bytes);

                sendResponse(exchange, 200, "{\"success\":true,\"base64\":\"" + base64 + "\"}");
            } catch (Exception e) {
                sendResponse(exchange, 500, "{\"error\":\"" + e.getMessage() + "\"}");
            }
        }
    }

    static class AiAnalyzeHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                if (aiEngine == null) {
                    sendResponse(exchange, 400, "{\"error\":\"AI 引擎未初始化，请设置 DEEPSEEK_API_KEY\"}");
                    return;
                }

                String body = readBody(exchange);
                var request = objectMapper.readTree(body);
                String instruction = request.path("instruction").asText();
                String screenshotBase64 = request.path("screenshot").asText(null);

                var screenSize = touchSimulator.getScreenSize();
                if (screenSize == null) {
                    sendResponse(exchange, 400, "{\"error\":\"请先连接设备\"}");
                    return;
                }

                TouchDecision decision = aiEngine.analyze(instruction, screenshotBase64,
                        screenSize.getWidth(), screenSize.getHeight());

                sendResponse(exchange, 200, objectMapper.writeValueAsString(decision));
            } catch (Exception e) {
                sendResponse(exchange, 500, "{\"error\":\"" + e.getMessage() + "\"}");
            }
        }
    }

    static class AiExecuteHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                if (aiEngine == null) {
                    sendResponse(exchange, 400, "{\"error\":\"AI 引擎未初始化，请设置 DEEPSEEK_API_KEY\"}");
                    return;
                }

                String body = readBody(exchange);
                var request = objectMapper.readTree(body);
                String instruction = request.path("instruction").asText();
                String screenshotBase64 = request.path("screenshot").asText(null);

                var screenSize = touchSimulator.getScreenSize();
                if (screenSize == null) {
                    sendResponse(exchange, 400, "{\"error\":\"请先连接设备\"}");
                    return;
                }

                // AI 分析
                TouchDecision decision = aiEngine.analyze(instruction, screenshotBase64,
                        screenSize.getWidth(), screenSize.getHeight());

                logger.info("AI 决策: {}", decision);

                // 执行操作
                TouchResult result = executeDecision(decision);

                var response = objectMapper.createObjectNode();
                response.set("decision", objectMapper.valueToTree(decision));
                response.set("result", objectMapper.valueToTree(result));

                sendResponse(exchange, 200, objectMapper.writeValueAsString(response));
            } catch (Exception e) {
                sendResponse(exchange, 500, "{\"error\":\"" + e.getMessage() + "\"}");
            }
        }

        private TouchResult executeDecision(TouchDecision decision) {
            String op = decision.getOperation();
            int x = decision.getX();
            int y = decision.getY();

            return switch (op) {
                case "tap" -> touchSimulator.tap(x, y);
                case "doubleTap" -> touchSimulator.doubleTap(x, y);
                case "longPress" -> touchSimulator.longPress(x, y, decision.getDuration() != null ? decision.getDuration() : 500);
                case "swipe" -> touchSimulator.swipe(x, y, decision.getEndX(), decision.getEndY(), decision.getDuration() != null ? decision.getDuration() : 300);
                case "scroll" -> {
                    var dir = "up".equals(decision.getDirection())
                            ? TouchSimulator.ScrollDirection.UP
                            : TouchSimulator.ScrollDirection.DOWN;
                    yield touchSimulator.scroll(dir, 300);
                }
                default -> TouchResult.failure(op, x, y, "未知操作类型: " + op);
            };
        }
    }

    // ==================== 工具方法 ====================

    static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream is = exchange.getRequestBody()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static void sendResponse(HttpExchange exchange, int statusCode, String response) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}