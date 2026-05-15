package com.butler.touch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * 触摸模拟器
 * 通过 ADB 实现模拟手指触摸操作，包括点击、滑动、长按等
 */
public class TouchSimulator {
    private static final Logger logger = LoggerFactory.getLogger(TouchSimulator.class);

    private final AdbExecutor adbExecutor;
    private AdbExecutor.ScreenSize screenSize;

    public TouchSimulator() {
        this.adbExecutor = new AdbExecutor();
    }

    public TouchSimulator(String adbPath) {
        this.adbExecutor = new AdbExecutor(adbPath);
    }

    public TouchSimulator(AdbExecutor adbExecutor) {
        this.adbExecutor = adbExecutor;
    }

    /**
     * 设置目标设备
     */
    public TouchSimulator setDevice(String deviceId) {
        adbExecutor.setDeviceId(deviceId);
        return this;
    }

    /**
     * 初始化 - 获取屏幕尺寸等信息
     */
    public TouchSimulator initialize() throws IOException, InterruptedException {
        this.screenSize = adbExecutor.getScreenSize();
        logger.info("初始化完成，屏幕尺寸: {}", screenSize);
        return this;
    }

    /**
     * 获取屏幕尺寸
     */
    public AdbExecutor.ScreenSize getScreenSize() {
        return screenSize;
    }

    /**
     * 模拟点击 - 在指定坐标点击
     * @param x X 坐标
     * @param y Y 坐标
     */
    public TouchResult tap(int x, int y) {
        try {
            validateCoordinates(x, y);
            logger.info("执行点击操作: ({}, {})", x, y);

            AdbExecutor.AdbResult result = adbExecutor.executeShell("input", "tap",
                    String.valueOf(x), String.valueOf(y));

            if (result.isSuccess()) {
                return TouchResult.success("tap", x, y);
            } else {
                return TouchResult.failure("tap", x, y, result.getOutput());
            }
        } catch (Exception e) {
            logger.error("点击操作失败", e);
            return TouchResult.failure("tap", x, y, e.getMessage());
        }
    }

    /**
     * 模拟滑动 - 从起点滑动到终点
     * @param startX 起点X坐标
     * @param startY 起点Y坐标
     * @param endX 终点X坐标
     * @param endY 终点Y坐标
     * @param durationMs 滑动持续时间(毫秒)
     */
    public TouchResult swipe(int startX, int startY, int endX, int endY, long durationMs) {
        try {
            validateCoordinates(startX, startY);
            validateCoordinates(endX, endY);
            logger.info("执行滑动操作: ({}, {}) -> ({}, {}), 时长: {}ms",
                    startX, startY, endX, endY, durationMs);

            AdbExecutor.AdbResult result = adbExecutor.executeShell("input", "swipe",
                    String.valueOf(startX), String.valueOf(startY),
                    String.valueOf(endX), String.valueOf(endY),
                    String.valueOf(durationMs));

            if (result.isSuccess()) {
                return TouchResult.successSwipe(startX, startY, endX, endY, durationMs);
            } else {
                return TouchResult.failureSwipe(startX, startY, endX, endY, durationMs, result.getOutput());
            }
        } catch (Exception e) {
            logger.error("滑动操作失败", e);
            return TouchResult.failureSwipe(startX, startY, endX, endY, durationMs, e.getMessage());
        }
    }

    /**
     * 模拟滑动 - 使用默认时长 300ms
     */
    public TouchResult swipe(int startX, int startY, int endX, int endY) {
        return swipe(startX, startY, endX, endY, 300);
    }

    /**
     * 模拟长按 - 在指定位置长按
     * @param x X 坐标
     * @param y Y 坐标
     * @param durationMs 长按持续时间(毫秒)
     */
    public TouchResult longPress(int x, int y, long durationMs) {
        try {
            validateCoordinates(x, y);
            logger.info("执行长按操作: ({}, {}), 时长: {}ms", x, y, durationMs);

            // 长按实际上是滑动到同一点
            AdbExecutor.AdbResult result = adbExecutor.executeShell("input", "swipe",
                    String.valueOf(x), String.valueOf(y),
                    String.valueOf(x), String.valueOf(y),
                    String.valueOf(durationMs));

            if (result.isSuccess()) {
                return TouchResult.successLongPress(x, y, durationMs);
            } else {
                return TouchResult.failureLongPress(x, y, durationMs, result.getOutput());
            }
        } catch (Exception e) {
            logger.error("长按操作失败", e);
            return TouchResult.failureLongPress(x, y, durationMs, e.getMessage());
        }
    }

    /**
     * 模拟双击
     * @param x X 坐标
     * @param y Y 坐标
     * @param intervalMs 两次点击间隔(毫秒)
     */
    public TouchResult doubleTap(int x, int y, long intervalMs) {
        try {
            validateCoordinates(x, y);
            logger.info("执行双击操作: ({}, {}), 间隔: {}ms", x, y, intervalMs);

            TouchResult first = tap(x, y);
            if (!first.isSuccess()) {
                return first;
            }

            Thread.sleep(intervalMs);

            TouchResult second = tap(x, y);
            if (!second.isSuccess()) {
                return second;
            }

            return TouchResult.successDoubleTap(x, y, intervalMs);
        } catch (Exception e) {
            logger.error("双击操作失败", e);
            return TouchResult.failureDoubleTap(x, y, intervalMs, e.getMessage());
        }
    }

    /**
     * 模拟双击 - 使用默认间隔 100ms
     */
    public TouchResult doubleTap(int x, int y) {
        return doubleTap(x, y, 100);
    }

    /**
     * 模拟滚动手势 - 垂直滚动
     * @param direction 滚动方向 (UP 或 DOWN)
     * @param distance 滚动距离(像素)
     */
    public TouchResult scroll(ScrollDirection direction, int distance) {
        if (screenSize == null) {
            return TouchResult.failure("scroll", 0, 0, "屏幕尺寸未初始化");
        }

        int centerX = screenSize.getWidth() / 2;
        int centerY = screenSize.getHeight() / 2;

        int startY, endY;
        if (direction == ScrollDirection.UP) {
            startY = centerY + distance / 2;
            endY = centerY - distance / 2;
        } else {
            startY = centerY - distance / 2;
            endY = centerY + distance / 2;
        }

        return swipe(centerX, startY, centerX, endY, 300);
    }

    /**
     * 模拟按键事件
     * @param keyCode 按键代码
     */
    public TouchResult keyEvent(int keyCode) {
        try {
            logger.info("执行按键事件: {}", keyCode);

            AdbExecutor.AdbResult result = adbExecutor.executeShell("input", "keyevent",
                    String.valueOf(keyCode));

            if (result.isSuccess()) {
                return new TouchResult(true, "keyevent", 0, 0, null);
            } else {
                return new TouchResult(false, "keyevent", 0, 0, result.getOutput());
            }
        } catch (Exception e) {
            logger.error("按键事件失败", e);
            return new TouchResult(false, "keyevent", 0, 0, e.getMessage());
        }
    }

    /**
     * 输入文本
     * @param text 要输入的文本
     */
    public TouchResult text(String text) {
        try {
            logger.info("执行文本输入: {}", text);

            // 需要对文本进行转义
            String escapedText = text.replace(" ", "%s").replace("'", "\\'");

            AdbExecutor.AdbResult result = adbExecutor.executeShell("input", "text", escapedText);

            if (result.isSuccess()) {
                return new TouchResult(true, "text", 0, 0, null);
            } else {
                return new TouchResult(false, "text", 0, 0, result.getOutput());
            }
        } catch (Exception e) {
            logger.error("文本输入失败", e);
            return new TouchResult(false, "text", 0, 0, e.getMessage());
        }
    }

    /**
     * 验证坐标是否在屏幕范围内
     */
    private void validateCoordinates(int x, int y) {
        if (x < 0 || y < 0) {
            throw new IllegalArgumentException("坐标不能为负数: (" + x + ", " + y + ")");
        }

        if (screenSize != null) {
            if (x > screenSize.getWidth() || y > screenSize.getHeight()) {
                logger.warn("坐标可能超出屏幕范围: ({}, {}), 屏幕尺寸: {}",
                        x, y, screenSize);
            }
        }
    }

    /**
     * 滚动方向枚举
     */
    public enum ScrollDirection {
        UP, DOWN
    }
}