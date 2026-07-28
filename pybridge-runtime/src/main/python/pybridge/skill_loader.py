"""Skill 加载器模块。

封装 .bsk 格式 Skill 包的解压、清单解析、模块动态加载、缓存与执行逻辑。
该模块被 :mod:`pybridge.__init__` 导入，是 PyBridge 运行时的核心组件之一。

.bsk 包结构示例
---------------
一个合法的 ``.bsk`` 文件本质上是一个 ZIP 压缩包，解压后目录结构如下::

    my_skill.bsk (zip)
    |-- manifest.json          # Skill 元信息清单
    |-- main.py                # 入口模块
    |-- utils.py               # 辅助模块
    `-- requirements.txt       # 可选，依赖声明

其中 ``manifest.json`` 至少包含以下字段::

    {
        "skill_id": "my_skill",
        "name": "My Skill",
        "version": "1.0.0",
        "description": "一个示例 Skill",
        "entry_file": "main.py",
        "entry_function": "run",
        "author": "Butler Team"
    }
"""

from __future__ import annotations

import importlib
import importlib.util
import json
import os
import shutil
import sys
import threading
import traceback
import zipfile
from typing import Any, Dict, List, Optional


# 缓存中保存的单条 Skill 记录结构
class _SkillCacheEntry:
    """内部使用的缓存条目，保存已加载 Skill 的运行时上下文。

    :ivar manifest: manifest.json 解析后的字典
    :ivar module: 通过 importlib 加载得到的模块对象
    :ivar entry_function: 入口模块中需调用的可调用对象
    :ivar extract_dir: 解压后的临时目录路径
    """

    __slots__ = ("manifest", "module", "entry_function", "extract_dir")

    def __init__(
        self,
        manifest: Dict[str, Any],
        module: Any,
        entry_function: Any,
        extract_dir: str,
    ) -> None:
        self.manifest = manifest
        self.module = module
        self.entry_function = entry_function
        self.extract_dir = extract_dir


class SkillLoader:
    """Skill 包加载器。

    负责 ``.bsk`` 包的解压、清单读取、入口模块动态加载、结果缓存以及执行调度。
    该类是线程安全的，所有涉及共享缓存状态的公共方法均通过内部锁保护。

    :param skills_dir: Skill 存放目录。该目录下每个 ``{skill_id}.bsk`` 文件
        代表一个已安装的 Skill。解压后的内容会放在 ``{skills_dir}/.extracted/{skill_id}``
        子目录中。
    """

    # 解压后内容存放的子目录名
    _EXTRACTED_SUBDIR = ".extracted"

    def __init__(self, skills_dir: str) -> None:
        """初始化 SkillLoader。

        :param skills_dir: Skill 存放目录路径。
        """
        self.skills_dir = skills_dir
        # 已加载 Skill 的缓存，key 为 skill_id，value 为 _SkillCacheEntry
        self._cache: Dict[str, _SkillCacheEntry] = {}
        # 保护缓存与文件操作的互斥锁
        self._lock = threading.Lock()

    # ------------------------------------------------------------------
    # 路径辅助方法
    # ------------------------------------------------------------------
    def _bsk_path(self, skill_id: str) -> str:
        """返回指定 skill_id 对应的 .bsk 文件路径。

        :param skill_id: Skill 唯一标识符。
        :return: ``{skills_dir}/{skill_id}.bsk`` 路径字符串。
        """
        return os.path.join(self.skills_dir, f"{skill_id}.bsk")

    def _extract_dir(self, skill_id: str) -> str:
        """返回指定 skill_id 对应的解压目录路径。

        :param skill_id: Skill 唯一标识符。
        :return: ``{skills_dir}/.extracted/{skill_id}`` 路径字符串。
        """
        return os.path.join(self.skills_dir, self._EXTRACTED_SUBDIR, skill_id)

    # ------------------------------------------------------------------
    # 解压与清单读取
    # ------------------------------------------------------------------
    def _extract_bsk(self, bsk_path: str, dest_dir: str) -> str:
        """将 .bsk 文件解压到目标目录。

        若目标目录已存在则会先删除，以保证内容为最新的包内容。
        解压采用 ZIP 格式（``.bsk`` 即 ZIP 压缩包）。

        :param bsk_path: ``.bsk`` 文件路径。
        :param dest_dir: 解压目标目录。
        :return: 解压后的目录路径。
        :raises FileNotFoundError: ``.bsk`` 文件不存在时抛出。
        :raises zipfile.BadZipFile: ``.bsk`` 文件损坏或不是合法 ZIP 时抛出。
        """
        if not os.path.isfile(bsk_path):
            raise FileNotFoundError(f"BSK file not found: {bsk_path}")

        # 若解压目录已存在，先清理以避免残留旧文件
        if os.path.exists(dest_dir):
            shutil.rmtree(dest_dir, ignore_errors=True)
        os.makedirs(dest_dir, exist_ok=True)

        # 以 ZIP 方式解压
        with zipfile.ZipFile(bsk_path, "r") as zf:
            # 校验压缩包内是否有恶意路径（如绝对路径或 ../ 越权访问）
            for member in zf.namelist():
                member_path = os.path.normpath(os.path.join(dest_dir, member))
                if not member_path.startswith(os.path.abspath(dest_dir)):
                    raise ValueError(
                        f"Unsafe path detected in bsk: {member}"
                    )
            zf.extractall(dest_dir)

        return dest_dir

    def _load_manifest(self, extract_dir: str) -> Dict[str, Any]:
        """从解压目录读取并解析 manifest.json。

        :param extract_dir: 已解压的 Skill 目录。
        :return: 解析后的清单字典。
        :raises FileNotFoundError: manifest.json 不存在时抛出。
        :raises ValueError: manifest.json 格式非法时抛出。
        """
        manifest_path = os.path.join(extract_dir, "manifest.json")
        if not os.path.isfile(manifest_path):
            raise FileNotFoundError(
                f"manifest.json not found in: {extract_dir}"
            )

        try:
            with open(manifest_path, "r", encoding="utf-8") as f:
                manifest = json.load(f)
        except json.JSONDecodeError as exc:
            raise ValueError(
                f"Invalid manifest.json format: {exc}"
            ) from exc

        # 校验必要字段
        required_fields = ("skill_id", "entry_file", "entry_function")
        for field in required_fields:
            if field not in manifest:
                raise ValueError(
                    f"manifest.json missing required field: {field}"
                )

        return manifest

    # ------------------------------------------------------------------
    # 模块动态加载
    # ------------------------------------------------------------------
    def _load_module(
        self,
        skill_id: str,
        extract_dir: str,
        entry_file: str,
    ) -> Any:
        """使用 importlib 动态加载入口模块。

        采用 :func:`importlib.util.spec_from_file_location` 从指定文件路径
        加载模块，并为每个 skill 使用独立的模块名（以 ``pybridge_skill_`` 为前缀），
        避免不同 skill 间模块名冲突。

        :param skill_id: Skill 唯一标识符，用于生成唯一模块名。
        :param extract_dir: 解压后的目录路径。
        :param entry_file: 入口模块文件名（相对 extract_dir）。
        :return: 加载得到的模块对象。
        :raises ImportError: 模块文件不存在或加载失败时抛出。
        """
        entry_path = os.path.join(extract_dir, entry_file)
        if not os.path.isfile(entry_path):
            raise ImportError(
                f"Entry file not found: {entry_path}"
            )

        # 生成唯一的模块名，避免与已加载模块冲突
        module_name = f"pybridge_skill_{skill_id}"

        # 若已存在同名模块，先从 sys.modules 移除以支持重新加载
        if module_name in sys.modules:
            del sys.modules[module_name]

        try:
            spec = importlib.util.spec_from_file_location(module_name, entry_path)
            if spec is None or spec.loader is None:
                raise ImportError(
                    f"Cannot create module spec for: {entry_path}"
                )
            module = importlib.util.module_from_spec(spec)
            # 注册到 sys.modules，使模块内相对导入与 import 正常工作
            sys.modules[module_name] = module
            spec.loader.exec_module(module)
        except Exception as exc:
            # 加载失败时清理 sys.modules 中的残留
            sys.modules.pop(module_name, None)
            raise ImportError(
                f"Failed to load module from {entry_path}: {exc}"
            ) from exc

        return module

    def _resolve_entry_function(self, module: Any, entry_function: str) -> Any:
        """从已加载的模块中解析入口函数。

        :param module: 已加载的模块对象。
        :param entry_function: 入口函数名。
        :return: 可调用的入口函数对象。
        :raises AttributeError: 模块中不存在该函数时抛出。
        :raises TypeError: 该属性不可调用时抛出。
        """
        func = getattr(module, entry_function, None)
        if func is None:
            raise AttributeError(
                f"Entry function '{entry_function}' not found in module"
            )
        if not callable(func):
            raise TypeError(
                f"Entry function '{entry_function}' is not callable"
            )
        return func

    # ------------------------------------------------------------------
    # 公共 API
    # ------------------------------------------------------------------
    def load(self, skill_id: str) -> Dict[str, Any]:
        """完整加载指定 Skill。

        执行流程：
        1. 检查缓存，若已加载则直接返回缓存结果；
        2. 定位 ``.bsk`` 文件并解压到临时目录；
        3. 读取 ``manifest.json``；
        4. 动态加载入口模块；
        5. 解析入口函数；
        6. 将结果写入缓存并返回。

        :param skill_id: Skill 唯一标识符。
        :return: 加载结果字典，包含以下字段：
            - ``success`` (bool): 是否加载成功
            - ``manifest`` (dict|None): 成功时为清单字典，失败时为 None
            - ``module`` (Any|None): 成功时为模块对象，失败时为 None
            - ``entry_function`` (Any|None): 成功时为入口函数，失败时为 None
            - ``error`` (str|None): 失败时的错误信息
            - ``traceback`` (str|None): 失败时的完整 traceback
        """
        # 先在锁外检查缓存以减少锁竞争，若未命中再加锁处理
        with self._lock:
            cached = self._cache.get(skill_id)
            if cached is not None:
                return {
                    "success": True,
                    "manifest": cached.manifest,
                    "module": cached.module,
                    "entry_function": cached.entry_function,
                    "error": None,
                    "traceback": None,
                }

        bsk_path = self._bsk_path(skill_id)
        extract_dir = self._extract_dir(skill_id)

        try:
            # 解压 .bsk
            self._extract_bsk(bsk_path, extract_dir)
            # 读取清单
            manifest = self._load_manifest(extract_dir)
            # 校验 manifest 中的 skill_id 与传入的一致
            if manifest.get("skill_id") != skill_id:
                raise ValueError(
                    f"Skill ID mismatch: expected '{skill_id}', "
                    f"but manifest says '{manifest.get('skill_id')}'"
                )
            # 加载入口模块
            module = self._load_module(
                skill_id, extract_dir, manifest["entry_file"]
            )
            # 解析入口函数
            entry_function = self._resolve_entry_function(
                module, manifest["entry_function"]
            )

            # 写入缓存
            entry = _SkillCacheEntry(
                manifest=manifest,
                module=module,
                entry_function=entry_function,
                extract_dir=extract_dir,
            )
            with self._lock:
                self._cache[skill_id] = entry

            return {
                "success": True,
                "manifest": manifest,
                "module": module,
                "entry_function": entry_function,
                "error": None,
                "traceback": None,
            }
        except Exception as exc:
            tb_str = traceback.format_exc()
            return {
                "success": False,
                "manifest": None,
                "module": None,
                "entry_function": None,
                "error": str(exc),
                "traceback": tb_str,
            }

    def run(self, skill_id: str, args: Any = None) -> Dict[str, Any]:
        """加载并执行指定 Skill 的入口函数。

        若 Skill 尚未加载，会自动调用 :meth:`load` 完成加载。
        执行过程中捕获所有异常，保证不会向上抛出导致运行时崩溃。

        :param skill_id: Skill 唯一标识符。
        :param args: 传递给入口函数的参数，可以是任意可序列化的 Python 对象，
            通常为 dict 或 list。默认为 None。
        :return: 执行结果字典，包含以下字段：
            - ``success`` (bool): 是否执行成功
            - ``data`` (Any|None): 成功时为入口函数返回值，失败时为 None
            - ``error`` (str|None): 失败时的错误信息
            - ``traceback`` (str|None): 失败时的完整 traceback
        """
        # 加载 Skill（若已缓存则直接复用）
        load_result = self.load(skill_id)
        if not load_result["success"]:
            return {
                "success": False,
                "data": None,
                "error": load_result["error"],
                "traceback": load_result["traceback"],
            }

        entry_function = load_result["entry_function"]

        try:
            # 调用入口函数，始终传入 args（即使为 None，由 skill 自行处理）
            result = entry_function(args)
            return {
                "success": True,
                "data": result,
                "error": None,
                "traceback": None,
            }
        except Exception as exc:
            tb_str = traceback.format_exc()
            return {
                "success": False,
                "data": None,
                "error": str(exc),
                "traceback": tb_str,
            }

    def is_installed(self, skill_id: str) -> bool:
        """检查指定 Skill 是否已安装。

        通过判断 ``.bsk`` 文件是否存在来确定安装状态。

        :param skill_id: Skill 唯一标识符。
        :return: 已安装返回 True，否则返回 False。
        """
        bsk_path = self._bsk_path(skill_id)
        return os.path.isfile(bsk_path)

    def list_all(self) -> List[Dict[str, Any]]:
        """列出所有已安装的 Skill。

        扫描 Skill 存放目录下所有 ``.bsk`` 文件，并尝试读取其清单以获取元信息。
        无法读取清单的包会被跳过但不会导致整体失败。

        :return: 已安装 Skill 的信息列表，每项为包含 ``skill_id`` 与清单字段的字典。
            若清单读取失败，则仅包含 ``skill_id`` 与 ``error`` 字段。
        """
        result: List[Dict[str, Any]] = []
        if not os.path.isdir(self.skills_dir):
            return result

        try:
            entries = sorted(os.listdir(self.skills_dir))
        except OSError:
            return result

        for entry in entries:
            if not entry.endswith(".bsk"):
                continue
            skill_id = entry[:-4]  # 去掉 .bsk 后缀
            bsk_path = os.path.join(self.skills_dir, entry)

            info: Dict[str, Any] = {"skill_id": skill_id}
            # 尝试从缓存或解压目录读取清单
            try:
                # 优先使用缓存中的清单
                with self._lock:
                    cached = self._cache.get(skill_id)
                if cached is not None:
                    info.update(cached.manifest)
                else:
                    # 临时解压读取清单
                    import tempfile
                    with tempfile.TemporaryDirectory() as tmp_dir:
                        with zipfile.ZipFile(bsk_path, "r") as zf:
                            zf.extractall(tmp_dir)
                        manifest = self._load_manifest(tmp_dir)
                        info.update(manifest)
            except Exception as exc:
                info["error"] = f"Failed to read manifest: {exc}"

            result.append(info)

        return result

    def clear_cache(self, skill_id: Optional[str] = None) -> None:
        """清除缓存。

        :param skill_id: 若指定，则仅清除该 Skill 的缓存；若为 None，则清除全部缓存。
            清除缓存时还会删除对应的解压目录以释放空间。
        """
        with self._lock:
            if skill_id is not None:
                entry = self._cache.pop(skill_id, None)
                if entry is not None:
                    # 删除解压目录
                    shutil.rmtree(entry.extract_dir, ignore_errors=True)
                else:
                    # 即使不在缓存中，也尝试清理可能残留的解压目录
                    extract_dir = self._extract_dir(skill_id)
                    shutil.rmtree(extract_dir, ignore_errors=True)
            else:
                # 清除全部缓存
                for sid, entry in list(self._cache.items()):
                    shutil.rmtree(entry.extract_dir, ignore_errors=True)
                self._cache.clear()

    def uninstall(self, skill_id: str) -> Dict[str, Any]:
        """卸载指定 Skill。

        删除 ``.bsk`` 文件、解压目录以及缓存中的记录。

        :param skill_id: Skill 唯一标识符。
        :return: 卸载结果字典：
            - ``success`` (bool): 是否成功
            - ``error`` (str|None): 失败时的错误信息
        """
        try:
            # 先清除缓存（含解压目录）
            self.clear_cache(skill_id)

            # 删除 .bsk 文件
            bsk_path = self._bsk_path(skill_id)
            if os.path.isfile(bsk_path):
                os.remove(bsk_path)

            # 确保解压目录也已删除
            extract_dir = self._extract_dir(skill_id)
            if os.path.exists(extract_dir):
                shutil.rmtree(extract_dir, ignore_errors=True)

            return {"success": True, "error": None}
        except Exception as exc:
            return {"success": False, "error": str(exc)}

    def get_info(self, skill_id: str) -> Dict[str, Any]:
        """获取指定 Skill 的元信息。

        优先从缓存读取清单，若未缓存则临时解压读取。

        :param skill_id: Skill 唯一标识符。
        :return: Skill 信息字典：
            - ``success`` (bool): 是否成功读取
            - ``manifest`` (dict|None): 成功时为清单字典
            - ``error`` (str|None): 失败时的错误信息
        """
        # 优先从缓存读取
        with self._lock:
            cached = self._cache.get(skill_id)
        if cached is not None:
            return {
                "success": True,
                "manifest": cached.manifest,
                "error": None,
            }

        # 未缓存则从 .bsk 临时读取
        bsk_path = self._bsk_path(skill_id)
        if not os.path.isfile(bsk_path):
            return {
                "success": False,
                "manifest": None,
                "error": f"Skill '{skill_id}' is not installed",
            }

        try:
            import tempfile
            with tempfile.TemporaryDirectory() as tmp_dir:
                with zipfile.ZipFile(bsk_path, "r") as zf:
                    zf.extractall(tmp_dir)
                manifest = self._load_manifest(tmp_dir)
            return {
                "success": True,
                "manifest": manifest,
                "error": None,
            }
        except Exception as exc:
            return {
                "success": False,
                "manifest": None,
                "error": str(exc),
            }
