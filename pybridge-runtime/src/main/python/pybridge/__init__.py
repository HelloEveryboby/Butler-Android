"""PyBridge 运行时入口模块。

PyBridge 是 Butler-Android 项目的核心组件之一，负责在 Android 平台上嵌入
CPython 解释器，加载并执行 ``.bsk`` 格式的 Skill 包。

本模块在导入时即自动调用 :func:`init_runtime` 完成运行时环境的初始化，包括：
- 将预编译第三方包路径加入 ``sys.path``；
- 配置动态链接库（``.so``）搜索路径；
- 创建 Skill 存放目录。

环境变量
---------
运行时依赖以下环境变量（通常由 JNI 层在初始化时设置）：

- ``PYBRIDGE_ASSETS``: Python 资源根目录，包含预编译包、标准库等。
- ``PYBRIDGE_FILES``: 用户文件目录，Skill 包（``.bsk``）存放于此目录下的
  ``skills`` 子目录中。

模块结构
---------
- :func:`init_runtime`: 初始化运行时环境
- :func:`load_skill`: 加载指定 Skill
- :func:`run_skill`: 加载并执行指定 Skill
- :func:`list_installed_skills`: 列出所有已安装 Skill
- :func:`uninstall_skill`: 卸载指定 Skill
- :func:`get_skill_info`: 获取指定 Skill 元信息
- :class:`SkillLoader`: 底层加载器实现（见 :mod:`pybridge.skill_loader`）
"""

from __future__ import annotations

import json
import os
import sys
import traceback
from typing import Any, Dict, List, Optional

from .skill_loader import SkillLoader

__all__ = [
    "init_runtime",
    "load_skill",
    "run_skill",
    "list_installed_skills",
    "uninstall_skill",
    "get_skill_info",
    "SkillLoader",
    "get_loader",
]

__version__ = "1.0.0"

# ======================================================================
# 路径常量
# ======================================================================

#: Python 资源根目录，来源于环境变量 ``PYBRIDGE_ASSETS``。
#: 该目录包含预编译包（``packages``）、标准库、共享库等。
_ASSETS_DIR: str = os.environ.get("PYBRIDGE_ASSETS", "")

#: 预编译第三方包目录，位于 ``_ASSETS_DIR/packages`` 下。
#: 每个子目录可能包含 ``site-packages`` 子目录，其中的包会被加入 ``sys.path``。
_PACKAGES_DIR: str = os.path.join(_ASSETS_DIR, "packages") if _ASSETS_DIR else ""

#: 用户文件根目录，来源于环境变量 ``PYBRIDGE_FILES``。
_FILES_DIR: str = os.environ.get("PYBRIDGE_FILES", "")

#: Skill 存放目录，位于 ``_FILES_DIR/skills`` 下。
#: 每个 ``{skill_id}.bsk`` 文件代表一个已安装的 Skill。
_SKILLS_DIR: str = os.path.join(_FILES_DIR, "skills") if _FILES_DIR else ""

#: 动态链接库（``.so``）所在目录，位于 ``_ASSETS_DIR/lib`` 下。
_LIB_DIR: str = os.path.join(_ASSETS_DIR, "lib") if _ASSETS_DIR else ""

#: 全局 SkillLoader 实例，在 :func:`init_runtime` 中创建。
_loader: Optional[SkillLoader] = None

#: 运行时是否已初始化的标志。
_initialized: bool = False


# ======================================================================
# 运行时初始化
# ======================================================================
def init_runtime() -> Dict[str, Any]:
    """初始化 PyBridge 运行时环境。

    执行以下操作：
    1. 重新读取环境变量以更新路径常量（支持运行时重新初始化）；
    2. 遍历 ``_PACKAGES_DIR`` 下的子目录，将其中 ``site-packages`` 路径
       加入 ``sys.path``，使预编译第三方包可被导入；
    3. 将 ``.so`` 所在目录添加到动态链接路径（优先使用
       :func:`os.add_dll_directory`，回退到设置 ``LD_LIBRARY_PATH``）；
    4. 创建 ``_SKILLS_DIR`` 目录（若不存在）；
    5. 创建全局 :class:`SkillLoader` 实例。

    本函数是幂等的，多次调用不会重复添加路径。所有异常均被捕获并记录，
    不会向上抛出，以保证运行时不会因初始化失败而崩溃。

    :return: 初始化结果字典，包含以下字段：
        - ``success`` (bool): 是否初始化成功
        - ``assets_dir`` (str): 资源根目录路径
        - ``packages_dir`` (str): 预编译包目录路径
        - ``skills_dir`` (str): Skill 存放目录路径
        - ``lib_dir`` (str): 动态库目录路径
        - ``added_paths`` (list): 本次新增到 sys.path 的路径列表
        - ``error`` (str|None): 失败时的错误信息
    """
    global _ASSETS_DIR, _PACKAGES_DIR, _FILES_DIR, _SKILLS_DIR, _LIB_DIR
    global _loader, _initialized

    added_paths: List[str] = []

    try:
        # 1. 重新读取环境变量，更新路径常量
        _ASSETS_DIR = os.environ.get("PYBRIDGE_ASSETS", "")
        _PACKAGES_DIR = (
            os.path.join(_ASSETS_DIR, "packages") if _ASSETS_DIR else ""
        )
        _FILES_DIR = os.environ.get("PYBRIDGE_FILES", "")
        _SKILLS_DIR = (
            os.path.join(_FILES_DIR, "skills") if _FILES_DIR else ""
        )
        _LIB_DIR = os.path.join(_ASSETS_DIR, "lib") if _ASSETS_DIR else ""

        # 2. 遍历 _PACKAGES_DIR 下的子目录，将 site-packages 路径加入 sys.path
        if _PACKAGES_DIR and os.path.isdir(_PACKAGES_DIR):
            try:
                for subdir_name in sorted(os.listdir(_PACKAGES_DIR)):
                    subdir_path = os.path.join(_PACKAGES_DIR, subdir_name)
                    if not os.path.isdir(subdir_path):
                        continue
                    # 查找子目录下的 site-packages
                    site_packages = os.path.join(subdir_path, "site-packages")
                    if os.path.isdir(site_packages):
                        if site_packages not in sys.path:
                            sys.path.insert(0, site_packages)
                            added_paths.append(site_packages)
                    # 同时将子目录本身加入路径（兼容部分包结构）
                    if subdir_path not in sys.path:
                        sys.path.insert(0, subdir_path)
                        added_paths.append(subdir_path)
            except OSError as exc:
                sys.stderr.write(
                    f"[PyBridge] Warning: failed to scan packages dir: {exc}\n"
                )

        # 将 _PACKAGES_DIR 本身也加入路径，便于直接导入顶层包
        if _PACKAGES_DIR and _PACKAGES_DIR not in sys.path:
            sys.path.insert(0, _PACKAGES_DIR)
            added_paths.append(_PACKAGES_DIR)

        # 3. 添加 .so 所在目录到动态链接路径
        if _LIB_DIR and os.path.isdir(_LIB_DIR):
            _configure_library_path(_LIB_DIR)

        # 4. 创建 _SKILLS_DIR 目录
        if _SKILLS_DIR:
            os.makedirs(_SKILLS_DIR, exist_ok=True)

        # 5. 创建全局 SkillLoader 实例
        _loader = SkillLoader(_SKILLS_DIR)

        _initialized = True

        return {
            "success": True,
            "assets_dir": _ASSETS_DIR,
            "packages_dir": _PACKAGES_DIR,
            "skills_dir": _SKILLS_DIR,
            "lib_dir": _LIB_DIR,
            "added_paths": added_paths,
            "error": None,
        }
    except Exception as exc:
        tb_str = traceback.format_exc()
        sys.stderr.write(
            f"[PyBridge] init_runtime failed: {exc}\n{tb_str}\n"
        )
        _initialized = False
        return {
            "success": False,
            "assets_dir": _ASSETS_DIR,
            "packages_dir": _PACKAGES_DIR,
            "skills_dir": _SKILLS_DIR,
            "lib_dir": _LIB_DIR,
            "added_paths": added_paths,
            "error": f"{exc}\n{tb_str}",
        }


def _configure_library_path(lib_dir: str) -> None:
    """配置动态链接库搜索路径。

    优先使用 :func:`os.add_dll_directory`（Python 3.8+，Windows 上有效，
    但在部分嵌入式 Python 构建中也可用），若不可用则回退到设置
    ``LD_LIBRARY_PATH`` 环境变量（Linux/Android 平台）。

    :param lib_dir: 动态库所在目录路径。
    """
    # 方式一：使用 os.add_dll_directory（若可用）
    add_dll_directory = getattr(os, "add_dll_directory", None)
    if callable(add_dll_directory):
        try:
            add_dll_directory(lib_dir)
        except (OSError, ValueError):
            # 某些平台可能不支持，忽略错误
            pass

    # 方式二：设置 LD_LIBRARY_PATH（Linux/Android 回退方案）
    ld_library_path = os.environ.get("LD_LIBRARY_PATH", "")
    if lib_dir not in ld_library_path.split(os.pathsep):
        new_ld_path = (
            lib_dir + os.pathsep + ld_library_path
            if ld_library_path
            else lib_dir
        )
        os.environ["LD_LIBRARY_PATH"] = new_ld_path

    # 同时将 lib_dir 加入 sys.path，便于 ctypes 加载
    if lib_dir not in sys.path:
        sys.path.append(lib_dir)


def get_loader() -> Optional[SkillLoader]:
    """获取全局 SkillLoader 实例。

    :return: 已初始化的 :class:`SkillLoader` 实例；若运行时尚未初始化则返回 None。
    """
    return _loader


# ======================================================================
# Skill 操作 API
# ======================================================================
def load_skill(skill_id: str) -> Dict[str, Any]:
    """从 ``{_SKILLS_DIR}/{skill_id}.bsk`` 加载 Skill。

    内部委托给全局 :class:`SkillLoader` 实例执行：
    - 解压 ``.bsk`` 到临时目录；
    - 读取 ``manifest.json``；
    - 用 importlib 动态加载入口模块；
    - 返回包含 manifest、module、entry_function 的结果。

    若运行时未初始化，会返回错误而不会抛出异常。

    :param skill_id: Skill 唯一标识符。
    :return: 加载结果字典，包含以下字段：
        - ``success`` (bool): 是否加载成功
        - ``manifest`` (dict|None): 成功时为清单字典
        - ``module`` (Any|None): 成功时为模块对象
        - ``entry_function`` (Any|None): 成功时为入口函数
        - ``error`` (str|None): 失败时的错误信息
        - ``traceback`` (str|None): 失败时的完整 traceback
    """
    if _loader is None:
        return {
            "success": False,
            "manifest": None,
            "module": None,
            "entry_function": None,
            "error": "PyBridge runtime is not initialized",
            "traceback": None,
        }
    return _loader.load(skill_id)


def run_skill(skill_id: str, args: Any = None) -> Dict[str, Any]:
    """加载并执行指定 Skill。

    内部调用 :func:`load_skill` 加载 Skill，然后调用其入口函数。
    所有异常均被捕获，返回结构化的结果字典，不会向上抛出异常。

    :param skill_id: Skill 唯一标识符。
    :param args: 传递给入口函数的参数，通常为 dict 或 list。默认为 None。
    :return: 执行结果字典，包含以下字段：
        - ``success`` (bool): 是否执行成功
        - ``data`` (Any|None): 成功时为入口函数返回值
        - ``error`` (str|None): 失败时的错误信息
        - ``traceback`` (str|None): 失败时的完整 traceback

    示例::

        result = run_skill("weather", {"city": "Beijing"})
        if result["success"]:
            print(result["data"])
        else:
            print(result["error"])
    """
    if _loader is None:
        return {
            "success": False,
            "data": None,
            "error": "PyBridge runtime is not initialized",
            "traceback": None,
        }
    return _loader.run(skill_id, args)


def list_installed_skills() -> List[Dict[str, Any]]:
    """扫描 Skill 存放目录，返回已安装 Skill 列表。

    遍历 ``_SKILLS_DIR`` 下所有 ``.bsk`` 文件，并尝试读取其清单以获取元信息。
    无法读取清单的包会包含错误信息但不会导致整体失败。

    :return: 已安装 Skill 的信息列表。每项为字典，至少包含 ``skill_id``，
        成功读取清单时还会包含 manifest 中的所有字段（如 ``name``、
        ``version``、``description`` 等）。若运行时未初始化，返回空列表。
    """
    if _loader is None:
        return []
    return _loader.list_all()


def uninstall_skill(skill_id: str) -> Dict[str, Any]:
    """卸载指定 Skill。

    删除 ``.bsk`` 文件、解压目录以及缓存中的记录。

    :param skill_id: Skill 唯一标识符。
    :return: 卸载结果字典：
        - ``success`` (bool): 是否成功
        - ``error`` (str|None): 失败时的错误信息
    """
    if _loader is None:
        return {
            "success": False,
            "error": "PyBridge runtime is not initialized",
        }
    return _loader.uninstall(skill_id)


def get_skill_info(skill_id: str) -> Dict[str, Any]:
    """获取指定 Skill 的元信息。

    优先从缓存读取清单，若未缓存则临时解压读取。

    :param skill_id: Skill 唯一标识符。
    :return: Skill 信息字典：
        - ``success`` (bool): 是否成功读取
        - ``manifest`` (dict|None): 成功时为清单字典
        - ``error`` (str|None): 失败时的错误信息
    """
    if _loader is None:
        return {
            "success": False,
            "manifest": None,
            "error": "PyBridge runtime is not initialized",
        }
    return _loader.get_info(skill_id)


def install_skill(bsk_path: str) -> Dict[str, Any]:
    """将 .bsk 文件安装到 Skill 存放目录。

    将指定路径的 ``.bsk`` 文件复制到 ``_SKILLS_DIR`` 目录下。
    若已存在同名文件则覆盖。复制后从包中读取 ``skill_id`` 以确定目标文件名。

    :param bsk_path: 源 ``.bsk`` 文件路径。
    :return: 安装结果字典：
        - ``success`` (bool): 是否安装成功
        - ``skill_id`` (str|None): 成功时为包内的 skill_id
        - ``installed_path`` (str|None): 成功时为目标文件路径
        - ``error`` (str|None): 失败时的错误信息
    """
    import shutil
    import tempfile
    import zipfile

    if _loader is None or not _SKILLS_DIR:
        return {
            "success": False,
            "skill_id": None,
            "installed_path": None,
            "error": "PyBridge runtime is not initialized",
        }

    try:
        if not os.path.isfile(bsk_path):
            return {
                "success": False,
                "skill_id": None,
                "installed_path": None,
                "error": f"BSK file not found: {bsk_path}",
            }

        # 临时解压读取 manifest 以获取 skill_id
        with tempfile.TemporaryDirectory() as tmp_dir:
            with zipfile.ZipFile(bsk_path, "r") as zf:
                zf.extractall(tmp_dir)
            manifest_path = os.path.join(tmp_dir, "manifest.json")
            if not os.path.isfile(manifest_path):
                return {
                    "success": False,
                    "skill_id": None,
                    "installed_path": None,
                    "error": "manifest.json not found in bsk package",
                }
            with open(manifest_path, "r", encoding="utf-8") as f:
                manifest = json.load(f)
            skill_id = manifest.get("skill_id")
            if not skill_id:
                return {
                    "success": False,
                    "skill_id": None,
                    "installed_path": None,
                    "error": "skill_id not found in manifest.json",
                }

        # 复制到 Skill 存放目录
        dest_path = os.path.join(_SKILLS_DIR, f"{skill_id}.bsk")
        shutil.copy2(bsk_path, dest_path)

        # 清除该 skill 的缓存（若存在），以便下次加载使用新版本
        _loader.clear_cache(skill_id)

        return {
            "success": True,
            "skill_id": skill_id,
            "installed_path": dest_path,
            "error": None,
        }
    except Exception as exc:
        tb_str = traceback.format_exc()
        return {
            "success": False,
            "skill_id": None,
            "installed_path": None,
            "error": f"{exc}\n{tb_str}",
        }


# ======================================================================
# 模块导入时自动初始化运行时
# ======================================================================
# 在模块被导入时自动执行运行时初始化。
# 若环境变量尚未设置（例如在桌面开发环境中导入本模块进行测试），
# init_runtime 内部会安全地处理缺失的路径，不会抛出异常。
init_runtime()
