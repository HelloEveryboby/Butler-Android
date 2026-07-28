"""Skill 市场后端配置模块。

集中管理所有可配置项，所有配置均可通过环境变量覆盖。

环境变量
--------
- ``SKILL_MARKET_STORAGE_DIR``: Skill 包存储根目录
- ``SKILL_MARKET_API_KEY``: 管理操作（删除等）所需的 API Key
- ``SKILL_MARKET_MAX_UPLOAD_SIZE``: 单次上传最大字节数
- ``SKILL_MARKET_HOST``: 服务监听地址
- ``SKILL_MARKET_PORT``: 服务监听端口
"""

from __future__ import annotations

import os
from pathlib import Path

# ======================================================================
# 存储配置
# ======================================================================

#: Skill 包存储根目录。
#: 目录结构为 ``{STORAGE_DIR}/{skill_id}/{version}/skill.bsk``，
#: 以及 ``{STORAGE_DIR}/{skill_id}/meta.json``。
STORAGE_DIR: Path = Path(
    os.environ.get("SKILL_MARKET_STORAGE_DIR", "/var/lib/butler/skills")
)

# ======================================================================
# 认证配置
# ======================================================================

#: 管理操作（如删除 skill 版本）所需的 API Key。
#: 生产环境务必通过环境变量设置一个足够复杂的随机字符串。
API_KEY: str = os.environ.get("SKILL_MARKET_API_KEY", "")

#: 客户端传递 API Key 时使用的请求头名称。
API_KEY_HEADER: str = "X-API-Key"

# ======================================================================
# 上传限制
# ======================================================================

#: 单次上传文件最大大小（字节），默认 50MB。
MAX_UPLOAD_SIZE: int = int(
    os.environ.get("SKILL_MARKET_MAX_UPLOAD_SIZE", str(50 * 1024 * 1024))
)

# ======================================================================
# 服务配置
# ======================================================================

#: 服务监听地址，``0.0.0.0`` 表示监听所有网卡。
HOST: str = os.environ.get("SKILL_MARKET_HOST", "0.0.0.0")

#: 服务监听端口。
PORT: int = int(os.environ.get("SKILL_MARKET_PORT", "8000"))

# ======================================================================
# 默认值
# ======================================================================

#: 客户端未指定 arch 时的默认架构。
DEFAULT_ARCH: str = "arm64-v8a"

#: 默认分页大小。
DEFAULT_PAGE_SIZE: int = 20

#: 分页大小上限，防止客户端请求过大分页。
MAX_PAGE_SIZE: int = 100

#: 支持的架构白名单（用于上传校验）。
SUPPORTED_ARCHS: tuple[str, ...] = (
    "arm64-v8a",
    "armeabi-v7a",
    "x86",
    "x86_64",
    "universal",
)

#: 通用架构标识，表示兼容所有架构。
UNIVERSAL_ARCH: str = "universal"


def ensure_storage_dir() -> Path:
    """确保存储根目录存在，若不存在则创建。

    :return: 存储根目录的 :class:`~pathlib.Path` 对象。
    """
    STORAGE_DIR.mkdir(parents=True, exist_ok=True)
    return STORAGE_DIR
