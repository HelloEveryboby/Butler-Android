package com.butler.touch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * ADB 命令执行器
 * 负责执行 Android Debug Bridge 命令，实现与 Android 设备的通信
 */
public class AdbExecutor {
    private static final Logger logger = LoggerFactory.getLogger(AdbExecutor.class);

    private final String adbPath;
    private String deviceId;
    private long timeoutSeconds = 30;

    public AdbExecutor() {
        this("adb");
    }

    public AdbExecutor(String adbPath) {
        this.adbPath = adbPath;
    }

    /**
     * 设置目标设备 ID
     */
    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    /**
     * 设置命令超时时间
     */
    public void setTimeoutSeconds(long timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    /**
     * 执行 ADB 命令
     * @param args ADB 命令参数
     * @return 命令执行结果
     */
    public AdbResult execute(String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(adbPath);

        // 如果指定了设备，添加 -s 参数
        if (deviceId != null && !deviceId.isEmpty()) {
            command.add("-s");
            command.add(deviceId);
        }

        command.addAll(List.of(args));

        logger.debug("执行 ADB 命令: {}", String.join(" ", command));

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);

        Process process = pb.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }

        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new RuntimeException("ADB 命令执行超时");
        }

        int exitCode = process.exitValue();
        String outputStr = output.toString().trim();

        logger.debug("ADB 命令结果 (exitCode={}): {}", exitCode, outputStr);

        return new AdbResult(exitCode, outputStr);
    }

    /**
     * 执行 Shell 命令
     */
    public AdbResult executeShell(String... args) throws IOException, InterruptedException {
        List<String> shellArgs = new ArrayList<>();
        shellArgs.add("shell");
        shellArgs.addAll(List.of(args));
        return execute(shellArgs.toArray(new String[0]));
    }

    /**
     * 获取已连接的设备列表
     */
    public List<DeviceInfo> getDevices() throws IOException, InterruptedException {
        AdbResult result = execute("devices");
        if (!result.isSuccess()) {
            throw new RuntimeException("获取设备列表失败: " + result.getOutput());
        }

        List<DeviceInfo> devices = new ArrayList<>();
        String[] lines = result.getOutput().split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("List of devices")) {
                continue;
            }

            String[] parts = line.split("\\s+");
            if (parts.length >= 2) {
                String id = parts[0];
                String status = parts[1];
                devices.add(new DeviceInfo(id, status));
            }
        }

        return devices;
    }

    /**
     * 获取设备屏幕分辨率
     */
    public ScreenSize getScreenSize() throws IOException, InterruptedException {
        AdbResult result = executeShell("wm", "size");
        if (!result.isSuccess()) {
            throw new RuntimeException("获取屏幕尺寸失败: " + result.getOutput());
        }

        // 输出格式: Physical size: 1080x2400
        String output = result.getOutput();
        String[] parts = output.split(":");
        if (parts.length >= 2) {
            String size = parts[1].trim();
            String[] dims = size.split("x");
            if (dims.length == 2) {
                return new ScreenSize(Integer.parseInt(dims[0]), Integer.parseInt(dims[1]));
            }
        }

        throw new RuntimeException("无法解析屏幕尺寸: " + output);
    }

    /**
     * 截取屏幕并保存到设备
     */
    public String takeScreenshot(String devicePath) throws IOException, InterruptedException {
        AdbResult result = executeShell("screencap", "-p", devicePath);
        if (!result.isSuccess()) {
            throw new RuntimeException("截图失败: " + result.getOutput());
        }
        return devicePath;
    }

    /**
     * 从设备拉取文件到本地
     */
    public void pullFile(String remotePath, String localPath) throws IOException, InterruptedException {
        AdbResult result = execute("pull", remotePath, localPath);
        if (!result.isSuccess()) {
            throw new RuntimeException("拉取文件失败: " + result.getOutput());
        }
    }

    /**
     * ADB 命令执行结果
     */
    public static class AdbResult {
        private final int exitCode;
        private final String output;

        public AdbResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }

        public int getExitCode() {
            return exitCode;
        }

        public String getOutput() {
            return output;
        }

        public boolean isSuccess() {
            return exitCode == 0;
        }
    }

    /**
     * 设备信息
     */
    public static class DeviceInfo {
        private final String id;
        private final String status;

        public DeviceInfo(String id, String status) {
            this.id = id;
            this.status = status;
        }

        public String getId() {
            return id;
        }

        public String getStatus() {
            return status;
        }

        public boolean isOnline() {
            return "device".equals(status);
        }

        @Override
        public String toString() {
            return String.format("DeviceInfo{id='%s', status='%s'}", id, status);
        }
    }

    /**
     * 屏幕尺寸
     */
    public static class ScreenSize {
        private final int width;
        private final int height;

        public ScreenSize(int width, int height) {
            this.width = width;
            this.height = height;
        }

        public int getWidth() {
            return width;
        }

        public int getHeight() {
            return height;
        }

        @Override
        public String toString() {
            return width + "x" + height;
        }
    }
}