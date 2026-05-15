"""
Butler 触摸模拟插件
封装 Java 触摸模拟服务，提供自然语言接口
"""

import os
import json
import time
import subprocess
import requests
from typing import Optional, Dict, Any, List, Tuple
from dataclasses import dataclass
from enum import Enum

# 尝试导入 Butler 插件基类
try:
    from plugin.abstract_plugin import AbstractPlugin
    HAS_BUTLER = True
except ImportError:
    HAS_BUTLER = False
    # 创建一个虚拟基类
    class AbstractPlugin:
        def __init__(self):
            pass


class TouchOperation(Enum):
    """触摸操作类型"""
    TAP = "tap"
    SWIPE = "swipe"
    LONG_PRESS = "longPress"
    DOUBLE_TAP = "doubleTap"
    SCROLL = "scroll"


@dataclass
class TouchResult:
    """触摸操作结果"""
    success: bool
    operation: str
    x: Optional[int] = None
    y: Optional[int] = None
    end_x: Optional[int] = None
    end_y: Optional[int] = None
    duration: Optional[int] = None
    error_message: Optional[str] = None
    timestamp: Optional[float] = None

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> 'TouchResult':
        return cls(
            success=data.get('success', False),
            operation=data.get('operation', ''),
            x=data.get('x'),
            y=data.get('y'),
            end_x=data.get('endX'),
            end_y=data.get('endY'),
            duration=data.get('duration'),
            error_message=data.get('errorMessage'),
            timestamp=data.get('timestamp')
        )


@dataclass
class TouchDecision:
    """AI 触摸决策"""
    operation: str
    x: int
    y: int
    end_x: Optional[int] = None
    end_y: Optional[int] = None
    duration: Optional[int] = None
    direction: Optional[str] = None
    description: Optional[str] = None

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> 'TouchDecision':
        return cls(
            operation=data.get('operation', 'tap'),
            x=data.get('x', 0),
            y=data.get('y', 0),
            end_x=data.get('endX'),
            end_y=data.get('endY'),
            duration=data.get('duration'),
            direction=data.get('direction'),
            description=data.get('description')
        )


class TouchSimulatorClient:
    """触摸模拟服务客户端"""

    def __init__(self, host: str = "localhost", port: int = 8765):
        self.base_url = f"http://{host}:{port}"
        self.session = requests.Session()
        self._connected = False
        self._screen_size: Optional[Tuple[int, int]] = None

    def is_service_running(self) -> bool:
        """检查服务是否运行"""
        try:
            response = self.session.get(f"{self.base_url}/api/health", timeout=5)
            return response.status_code == 200
        except:
            return False

    def get_devices(self) -> List[Dict[str, str]]:
        """获取已连接的 Android 设备列表"""
        response = self.session.get(f"{self.base_url}/api/devices")
        response.raise_for_status()
        return response.json()

    def connect(self, device_id: str) -> bool:
        """连接到指定设备"""
        response = self.session.post(
            f"{self.base_url}/api/connect",
            json={"deviceId": device_id}
        )
        response.raise_for_status()
        self._connected = True
        return True

    def get_screen_size(self) -> Tuple[int, int]:
        """获取屏幕尺寸"""
        if self._screen_size:
            return self._screen_size
        # 通过截图获取
        response = self.session.get(f"{self.base_url}/api/screenshot")
        if response.status_code == 200:
            # 这里简化处理，实际应该解析图片尺寸
            self._screen_size = (1080, 2400)  # 默认值
        return self._screen_size or (1080, 2400)

    def tap(self, x: int, y: int) -> TouchResult:
        """点击指定位置"""
        response = self.session.post(
            f"{self.base_url}/api/tap",
            json={"x": x, "y": y}
        )
        response.raise_for_status()
        return TouchResult.from_dict(response.json())

    def swipe(self, start_x: int, start_y: int, end_x: int, end_y: int,
              duration: int = 300) -> TouchResult:
        """滑动"""
        response = self.session.post(
            f"{self.base_url}/api/swipe",
            json={
                "startX": start_x,
                "startY": start_y,
                "endX": end_x,
                "endY": end_y,
                "duration": duration
            }
        )
        response.raise_for_status()
        return TouchResult.from_dict(response.json())

    def long_press(self, x: int, y: int, duration: int = 500) -> TouchResult:
        """长按"""
        response = self.session.post(
            f"{self.base_url}/api/longpress",
            json={"x": x, "y": y, "duration": duration}
        )
        response.raise_for_status()
        return TouchResult.from_dict(response.json())

    def double_tap(self, x: int, y: int, interval: int = 100) -> TouchResult:
        """双击"""
        response = self.session.post(
            f"{self.base_url}/api/doubletap",
            json={"x": x, "y": y, "interval": interval}
        )
        response.raise_for_status()
        return TouchResult.from_dict(response.json())

    def scroll(self, direction: str = "down", distance: int = 300) -> TouchResult:
        """滚动"""
        response = self.session.post(
            f"{self.base_url}/api/scroll",
            json={"direction": direction, "distance": distance}
        )
        response.raise_for_status()
        return TouchResult.from_dict(response.json())

    def key_event(self, key_code: int) -> TouchResult:
        """按键事件"""
        response = self.session.post(
            f"{self.base_url}/api/keyevent",
            json={"keyCode": key_code}
        )
        response.raise_for_status()
        return TouchResult.from_dict(response.json())

    def input_text(self, text: str) -> TouchResult:
        """输入文本"""
        response = self.session.post(
            f"{self.base_url}/api/text",
            json={"text": text}
        )
        response.raise_for_status()
        return TouchResult.from_dict(response.json())

    def take_screenshot(self) -> Optional[str]:
        """截取屏幕，返回 Base64 编码"""
        response = self.session.get(f"{self.base_url}/api/screenshot")
        if response.status_code == 200:
            return response.json().get('base64')
        return None

    def ai_analyze(self, instruction: str, screenshot: Optional[str] = None) -> TouchDecision:
        """AI 分析指令"""
        payload = {"instruction": instruction}
        if screenshot:
            payload["screenshot"] = screenshot

        response = self.session.post(
            f"{self.base_url}/api/ai/analyze",
            json=payload
        )
        response.raise_for_status()
        return TouchDecision.from_dict(response.json())

    def ai_execute(self, instruction: str, screenshot: Optional[str] = None) -> Dict[str, Any]:
        """AI 分析并执行"""
        payload = {"instruction": instruction}
        if screenshot:
            payload["screenshot"] = screenshot

        response = self.session.post(
            f"{self.base_url}/api/ai/execute",
            json=payload
        )
        response.raise_for_status()
        return response.json()


class TouchSimulatorPlugin(AbstractPlugin):
    """
    Butler 触摸模拟插件

    提供模拟手指触摸操作的能力，支持：
    - 点击、滑动、长按、双击
    - AI 智能决策点击位置
    - 自然语言控制
    """

    PLUGIN_NAME = "touch_simulator"
    PLUGIN_DESCRIPTION = "Android 触摸模拟插件 - 支持点击、滑动等操作"

    def __init__(self):
        super().__init__()
        self.client: Optional[TouchSimulatorClient] = None
        self.java_process: Optional[subprocess.Popen] = None
        self._initialized = False

    def initialize(self, config: Optional[Dict[str, Any]] = None) -> bool:
        """
        初始化插件

        Args:
            config: 配置字典，可包含:
                - host: Java 服务主机 (默认 localhost)
                - port: Java 服务端口 (默认 8765)
                - auto_start: 是否自动启动 Java 服务 (默认 True)
                - jar_path: Java JAR 文件路径
        """
        config = config or {}

        host = config.get('host', 'localhost')
        port = config.get('port', 8765)

        self.client = TouchSimulatorClient(host, port)

        # 检查服务是否已运行
        if self.client.is_service_running():
            self._initialized = True
            return True

        # 尝试启动 Java 服务
        if config.get('auto_start', True):
            jar_path = config.get('jar_path')
            if jar_path and os.path.exists(jar_path):
                self._start_java_service(jar_path, port)
                # 等待服务启动
                for _ in range(10):
                    if self.client.is_service_running():
                        self._initialized = True
                        return True
                    time.sleep(1)

        return self.client.is_service_running()

    def _start_java_service(self, jar_path: str, port: int):
        """启动 Java 服务"""
        api_key = os.environ.get('DEEPSEEK_API_KEY', '')
        cmd = ['java', '-jar', jar_path, '--port', str(port)]
        if api_key:
            cmd.extend(['--api-key', api_key])

        self.java_process = subprocess.Popen(
            cmd,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE
        )

    def shutdown(self):
        """关闭插件"""
        if self.java_process:
            self.java_process.terminate()
            self.java_process = None

    def get_devices(self) -> List[Dict[str, str]]:
        """获取可用设备列表"""
        if not self._initialized:
            raise RuntimeError("插件未初始化")
        return self.client.get_devices()

    def connect_device(self, device_id: str) -> bool:
        """连接设备"""
        if not self._initialized:
            raise RuntimeError("插件未初始化")
        return self.client.connect(device_id)

    def execute_touch(self, operation: str, **kwargs) -> TouchResult:
        """
        执行触摸操作

        Args:
            operation: 操作类型 (tap, swipe, long_press, double_tap, scroll)
            **kwargs: 操作参数
        """
        if not self._initialized:
            raise RuntimeError("插件未初始化")

        operation = operation.lower().replace('_', '').replace('-', '')

        if operation == 'tap':
            return self.client.tap(kwargs['x'], kwargs['y'])
        elif operation == 'swipe':
            return self.client.swipe(
                kwargs['start_x'], kwargs['start_y'],
                kwargs['end_x'], kwargs['end_y'],
                kwargs.get('duration', 300)
            )
        elif operation == 'longpress':
            return self.client.long_press(
                kwargs['x'], kwargs['y'],
                kwargs.get('duration', 500)
            )
        elif operation == 'doubletap':
            return self.client.double_tap(
                kwargs['x'], kwargs['y'],
                kwargs.get('interval', 100)
            )
        elif operation == 'scroll':
            return self.client.scroll(
                kwargs.get('direction', 'down'),
                kwargs.get('distance', 300)
            )
        else:
            raise ValueError(f"未知操作类型: {operation}")

    def smart_touch(self, instruction: str, use_screenshot: bool = True) -> Dict[str, Any]:
        """
        智能触摸 - 使用 AI 分析指令并执行

        Args:
            instruction: 自然语言指令，如 "点击登录按钮"
            use_screenshot: 是否使用屏幕截图辅助分析

        Returns:
            包含决策和执行结果的字典
        """
        if not self._initialized:
            raise RuntimeError("插件未初始化")

        screenshot = None
        if use_screenshot:
            try:
                screenshot = self.client.take_screenshot()
            except:
                pass

        return self.client.ai_execute(instruction, screenshot)

    def run(self, command: str, **kwargs) -> Any:
        """
        插件主入口 - Butler 调用接口

        支持的命令:
        - devices: 获取设备列表
        - connect <device_id>: 连接设备
        - tap <x> <y>: 点击
        - swipe <start_x> <start_y> <end_x> <end_y>: 滑动
        - scroll <up|down>: 滚动
        - smart <instruction>: AI 智能操作
        """
        if not self._initialized:
            # 尝试自动初始化
            if not self.initialize():
                return {"error": "插件初始化失败，请确保 Java 服务正在运行"}

        parts = command.strip().split(maxsplit=2)
        if not parts:
            return {"error": "命令为空"}

        cmd = parts[0].lower()

        try:
            if cmd == 'devices':
                return {"devices": self.get_devices()}

            elif cmd == 'connect':
                if len(parts) < 2:
                    return {"error": "请指定设备 ID"}
                device_id = parts[1]
                success = self.connect_device(device_id)
                return {"success": success, "device_id": device_id}

            elif cmd == 'tap':
                if len(parts) < 3:
                    return {"error": "用法: tap <x> <y>"}
                x, y = int(parts[1]), int(parts[2])
                result = self.client.tap(x, y)
                return result.__dict__

            elif cmd == 'swipe':
                if len(parts) < 5:
                    return {"error": "用法: swipe <start_x> <start_y> <end_x> <end_y>"}
                coords = [int(p) for p in parts[1:5]]
                duration = int(parts[5]) if len(parts) > 5 else 300
                result = self.client.swipe(*coords, duration)
                return result.__dict__

            elif cmd == 'scroll':
                direction = parts[1] if len(parts) > 1 else 'down'
                distance = int(parts[2]) if len(parts) > 2 else 300
                result = self.client.scroll(direction, distance)
                return result.__dict__

            elif cmd == 'smart':
                if len(parts) < 2:
                    return {"error": "请提供操作指令，如: smart 点击登录按钮"}
                instruction = parts[1]
                return self.smart_touch(instruction)

            else:
                return {"error": f"未知命令: {cmd}"}

        except Exception as e:
            return {"error": str(e)}


# 便捷函数
def create_plugin(config: Optional[Dict[str, Any]] = None) -> TouchSimulatorPlugin:
    """创建并初始化插件"""
    plugin = TouchSimulatorPlugin()
    plugin.initialize(config)
    return plugin


if __name__ == "__main__":
    # 测试代码
    plugin = create_plugin({'auto_start': False})

    if plugin.client.is_service_running():
        print("服务已运行")
        devices = plugin.get_devices()
        print(f"设备列表: {devices}")
    else:
        print("服务未运行，请先启动 Java 服务")