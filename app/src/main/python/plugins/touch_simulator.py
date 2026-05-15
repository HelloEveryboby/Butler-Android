"""
Butler 触摸模拟插件 - 插件入口
将此文件放入 Butler 的 plugin/ 目录即可自动加载
"""

from typing import Dict, Any, Optional
from touch_simulator_plugin import TouchSimulatorPlugin, create_plugin

# 插件元信息
PLUGIN_NAME = "touch_simulator"
PLUGIN_VERSION = "1.0.0"
PLUGIN_AUTHOR = "Butler Team"
PLUGIN_DESCRIPTION = "Android 触摸模拟插件 - 支持点击、滑动、长按等操作，集成 AI 智能决策"


class Plugin(TouchSimulatorPlugin):
    """
    Butler 插件标准入口类

    Butler 会自动加载 plugin/ 目录下的模块，
    并查找名为 Plugin 的类作为插件入口
    """

    def __init__(self):
        super().__init__()
        self.name = PLUGIN_NAME
        self.version = PLUGIN_VERSION
        self.description = PLUGIN_DESCRIPTION

    def get_info(self) -> Dict[str, str]:
        """获取插件信息"""
        return {
            "name": self.name,
            "version": self.version,
            "author": PLUGIN_AUTHOR,
            "description": self.description,
            "status": "initialized" if self._initialized else "not_initialized"
        }

    def get_commands(self) -> Dict[str, str]:
        """获取支持的命令列表"""
        return {
            "devices": "获取已连接的 Android 设备列表",
            "connect <device_id>": "连接到指定设备",
            "tap <x> <y>": "在指定坐标点击",
            "swipe <x1> <y1> <x2> <y2> [duration]": "从 (x1,y1) 滑动到 (x2,y2)",
            "scroll <up|down> [distance]": "滚动屏幕",
            "smart <instruction>": "AI 智能操作，如: smart 点击登录按钮"
        }


def get_plugin() -> Plugin:
    """Butler 插件加载入口"""
    return Plugin()


# 当作为独立脚本运行时
if __name__ == "__main__":
    import sys

    plugin = Plugin()

    print(f"Butler 触摸模拟插件 v{PLUGIN_VERSION}")
    print(f"{'=' * 40}")

    if len(sys.argv) > 1:
        command = " ".join(sys.argv[1:])
        result = plugin.run(command)
        print(f"结果: {result}")
    else:
        print("\n支持的命令:")
        for cmd, desc in plugin.get_commands().items():
            print(f"  {cmd:<40} - {desc}")

        print("\n示例:")
        print("  python plugin.py devices")
        print("  python plugin.py connect emulator-5554")
        print("  python plugin.py tap 500 300")
        print("  python plugin.py smart 点击确定按钮")