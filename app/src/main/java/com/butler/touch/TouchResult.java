package com.butler.touch;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 触摸操作结果
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TouchResult {
    private boolean success;
    private String operation;
    private Integer x;
    private Integer y;
    private Integer endX;
    private Integer endY;
    private Long duration;
    private String errorMessage;
    private long timestamp;

    public TouchResult() {
        this.timestamp = System.currentTimeMillis();
    }

    public TouchResult(boolean success, String operation, Integer x, Integer y, String errorMessage) {
        this.success = success;
        this.operation = operation;
        this.x = x;
        this.y = y;
        this.errorMessage = errorMessage;
        this.timestamp = System.currentTimeMillis();
    }

    // 静态工厂方法
    public static TouchResult success(String operation, int x, int y) {
        TouchResult result = new TouchResult();
        result.success = true;
        result.operation = operation;
        result.x = x;
        result.y = y;
        return result;
    }

    public static TouchResult failure(String operation, int x, int y, String errorMessage) {
        TouchResult result = new TouchResult();
        result.success = false;
        result.operation = operation;
        result.x = x;
        result.y = y;
        result.errorMessage = errorMessage;
        return result;
    }

    public static TouchResult successSwipe(int startX, int startY, int endX, int endY, long duration) {
        TouchResult result = new TouchResult();
        result.success = true;
        result.operation = "swipe";
        result.x = startX;
        result.y = startY;
        result.endX = endX;
        result.endY = endY;
        result.duration = duration;
        return result;
    }

    public static TouchResult failureSwipe(int startX, int startY, int endX, int endY, long duration, String errorMessage) {
        TouchResult result = new TouchResult();
        result.success = false;
        result.operation = "swipe";
        result.x = startX;
        result.y = startY;
        result.endX = endX;
        result.endY = endY;
        result.duration = duration;
        result.errorMessage = errorMessage;
        return result;
    }

    public static TouchResult successLongPress(int x, int y, long duration) {
        TouchResult result = new TouchResult();
        result.success = true;
        result.operation = "longPress";
        result.x = x;
        result.y = y;
        result.duration = duration;
        return result;
    }

    public static TouchResult failureLongPress(int x, int y, long duration, String errorMessage) {
        TouchResult result = new TouchResult();
        result.success = false;
        result.operation = "longPress";
        result.x = x;
        result.y = y;
        result.duration = duration;
        result.errorMessage = errorMessage;
        return result;
    }

    public static TouchResult successDoubleTap(int x, int y, long interval) {
        TouchResult result = new TouchResult();
        result.success = true;
        result.operation = "doubleTap";
        result.x = x;
        result.y = y;
        result.duration = interval;
        return result;
    }

    public static TouchResult failureDoubleTap(int x, int y, long interval, String errorMessage) {
        TouchResult result = new TouchResult();
        result.success = false;
        result.operation = "doubleTap";
        result.x = x;
        result.y = y;
        result.duration = interval;
        result.errorMessage = errorMessage;
        return result;
    }

    // Getters and Setters
    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getOperation() {
        return operation;
    }

    public void setOperation(String operation) {
        this.operation = operation;
    }

    public Integer getX() {
        return x;
    }

    public void setX(Integer x) {
        this.x = x;
    }

    public Integer getY() {
        return y;
    }

    public void setY(Integer y) {
        this.y = y;
    }

    public Integer getEndX() {
        return endX;
    }

    public void setEndX(Integer endX) {
        this.endX = endX;
    }

    public Integer getEndY() {
        return endY;
    }

    public void setEndY(Integer endY) {
        this.endY = endY;
    }

    public Long getDuration() {
        return duration;
    }

    public void setDuration(Long duration) {
        this.duration = duration;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("TouchResult{");
        sb.append("success=").append(success);
        sb.append(", operation='").append(operation).append('\'');
        if (x != null) sb.append(", x=").append(x);
        if (y != null) sb.append(", y=").append(y);
        if (endX != null) sb.append(", endX=").append(endX);
        if (endY != null) sb.append(", endY=").append(endY);
        if (duration != null) sb.append(", duration=").append(duration);
        if (errorMessage != null) sb.append(", errorMessage='").append(errorMessage).append('\'');
        sb.append('}');
        return sb.toString();
    }
}