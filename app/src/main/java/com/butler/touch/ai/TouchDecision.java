package com.butler.touch.ai;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * AI 触摸决策结果
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TouchDecision {
    private String operation;      // tap, swipe, longPress, doubleTap, scroll
    private Integer x;             // X 坐标
    private Integer y;             // Y 坐标
    private Integer endX;          // 滑动终点 X
    private Integer endY;          // 滑动终点 Y
    private Long duration;         // 持续时间 (ms)
    private String direction;      // 滚动方向 (up/down)
    private String description;    // 操作描述

    public TouchDecision() {}

    // Getters and Setters
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

    public String getDirection() {
        return direction;
    }

    public void setDirection(String direction) {
        this.direction = direction;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("TouchDecision{");
        sb.append("operation='").append(operation).append('\'');
        if (x != null) sb.append(", x=").append(x);
        if (y != null) sb.append(", y=").append(y);
        if (endX != null) sb.append(", endX=").append(endX);
        if (endY != null) sb.append(", endY=").append(endY);
        if (duration != null) sb.append(", duration=").append(duration);
        if (direction != null) sb.append(", direction='").append(direction).append('\'');
        if (description != null) sb.append(", description='").append(description).append('\'');
        sb.append('}');
        return sb.toString();
    }
}