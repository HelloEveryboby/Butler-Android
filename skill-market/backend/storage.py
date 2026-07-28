"""Skill 市场存储层实现。

本模块提供 :class:`SkillStorage` 类，负责 ``.bsk`` 包文件与元数据
的持久化存储、查询、版本管理与统计。

存储目录结构
------------
::

    {storage_dir}/
      {skill_id}/
        meta.json              # 该 skill 的全部元数据与版本历史
        {version}/
          skill.bsk            # 实际的 .bsk 包文件

``meta.json`` 结构示例::

    {
      "skill_id": "my_skill",
      "name": "My Skill",
      "category": "productivity",
      "versions": [
        {
          "version": "1.0.0",
          "sha256": "abc123...",
          "upload_date": "2024-01-01T12:00:00",
          "download_count": 0,
          "size_bytes": 10240,
          "metadata": { ... SkillMetadata 全部字段 ... }
        }
      ]
    }

线程安全
--------
所有涉及 ``meta.json`` 读写的方法均通过实例级互斥锁保护，
确保并发上传/下载/删除时元数据的一致性。
"""

from __future__ import annotations

import hashlib
import io
import json
import re
import threading
import zipfile
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

import config


# ======================================================================
# 版本比较工具
# ======================================================================

# 语义化版本号正则：匹配 "1.2.3"、"-1.2.3"、"-2" 等形式（适配 min_app_version 的简写）
_VERSION_PATTERN = re.compile(r"^v?(\d+)(?:\.(\d+))?(?:\.(\d+))?")


def _parse_version(version: str) -> Tuple[int, int, int]:
    """将语义化版本字符串解析为可比较的元组。

    支持不完整的版本号（如 ``1``、``1.2``），缺失部分补 0。
    无法解析的部分会被忽略。

    :param version: 版本字符串，如 ``"1.2.3"``。
    :return: ``(major, minor, patch)`` 三元组。
    """
    match = _VERSION_PATTERN.match(version.strip())
    if not match:
        return (0, 0, 0)
    major = int(match.group(1) or 0)
    minor = int(match.group(2) or 0)
    patch = int(match.group(3) or 0)
    return (major, minor, patch)


def _version_gte(version: str, minimum: str) -> bool:
    """判断 ``version`` 是否大于等于 ``minimum``。

    :param version: 待比较的版本字符串。
    :param minimum: 最低要求的版本字符串。
    :return: ``version >= minimum`` 时返回 True。
    """
    return _parse_version(version) >= _parse_version(minimum)


# ======================================================================
# 存储层主类
# ======================================================================


class SkillStorage:
    """Skill 包存储管理器。

    负责包文件的保存、元数据维护、版本管理、查询过滤与下载统计。

    :param storage_dir: 存储根目录。若不存在会在首次写入时自动创建。
    """

    #: 包文件在版本目录中的固定文件名。
    PACKAGE_FILENAME: str = "skill.bsk"

    #: 元数据文件名。
    META_FILENAME: str = "meta.json"

    def __init__(self, storage_dir: Path) -> None:
        """初始化存储管理器。

        :param storage_dir: 存储根目录路径。
        """
        self.storage_dir: Path = Path(storage_dir)
        # 保护 meta.json 并发读写的互斥锁
        self._lock: threading.RLock = threading.RLock()

    # ------------------------------------------------------------------
    # 路径辅助
    # ------------------------------------------------------------------

    def _skill_dir(self, skill_id: str) -> Path:
        """返回指定 skill 的目录路径。"""
        return self.storage_dir / skill_id

    def _version_dir(self, skill_id: str, version: str) -> Path:
        """返回指定 skill 某版本的目录路径。"""
        return self._skill_dir(skill_id) / version

    def _meta_path(self, skill_id: str) -> Path:
        """返回指定 skill 的 meta.json 路径。"""
        return self._skill_dir(skill_id) / self.META_FILENAME

    def _package_path(self, skill_id: str, version: str) -> Path:
        """返回指定 skill 某版本的 .bsk 文件路径。"""
        return self._version_dir(skill_id, version) / self.PACKAGE_FILENAME

    # ------------------------------------------------------------------
    # 静态工具方法
    # ------------------------------------------------------------------

    @staticmethod
    def _calculate_sha256(data: bytes) -> str:
        """计算给定字节流的 SHA256 校验和。

        :param data: 原始字节数据。
        :return: 十六进制小写形式的 SHA256 字符串。
        """
        return hashlib.sha256(data).hexdigest()

    @staticmethod
    def _now_iso() -> str:
        """返回当前 UTC 时间的 ISO 8601 字符串。"""
        return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")

    # ------------------------------------------------------------------
    # .bsk 包解析
    # ------------------------------------------------------------------

    def _parse_bsk_manifest(self, file_data: bytes) -> Dict[str, Any]:
        """从 .bsk 包字节流中解析 manifest.json。

        ``.bsk`` 本质是 ZIP 压缩包，其中 ``manifest.json`` 为必备文件。
        本方法在内存中解压并读取清单，不写入磁盘。

        :param file_data: ``.bsk`` 文件的完整字节内容。
        :return: 解析后的 manifest 字典。
        :raises ValueError: 数据不是合法 ZIP 或缺少 manifest.json
            或 manifest.json 格式非法时抛出。
        """
        # 校验是否为合法 ZIP
        try:
            zf = zipfile.ZipFile(io.BytesIO(file_data))
        except zipfile.BadZipFile as exc:
            raise ValueError(f"上传的文件不是合法的 ZIP/.bsk 格式: {exc}") from exc

        with zf:
            # 检查是否包含 manifest.json
            names = zf.namelist()
            # 兼容 manifest.json 位于根目录或带前缀的情况
            manifest_name: Optional[str] = None
            for name in names:
                # 归一化路径，取最后一段
                base = name.rstrip("/").split("/")[-1]
                if base == "manifest.json":
                    manifest_name = name
                    break

            if manifest_name is None:
                raise ValueError(
                    ".bsk 包中缺少 manifest.json 文件"
                )

            try:
                with zf.open(manifest_name) as manifest_file:
                    raw = manifest_file.read()
                manifest = json.loads(raw.decode("utf-8"))
            except (json.JSONDecodeError, UnicodeDecodeError) as exc:
                raise ValueError(
                    f"manifest.json 解析失败: {exc}"
                ) from exc

            if not isinstance(manifest, dict):
                raise ValueError("manifest.json 顶层必须是一个 JSON 对象")

        # 校验必要字段（支持 id/skill_id 和 entry/entry_file 别名）
        required_fields = ("skill_id", "entry_file", "entry_function")
        for field in required_fields:
            # 允许别名：skill_id ↔ id, entry_file ↔ entry
            alias = {"skill_id": "id", "entry_file": "entry"}.get(field)
            if field not in manifest and (not alias or alias not in manifest):
                raise ValueError(
                    f"manifest.json 缺少必要字段: {field}"
                )

        return manifest

    def _normalize_manifest(self, manifest: Dict[str, Any]) -> Dict[str, Any]:
        """将 manifest.json 字段归一化为 SkillMetadata 所需的字段名。

        主要映射：
        - ``skill_id`` -> ``id``
        - ``entry_file`` -> ``entry``

        :param manifest: 原始 manifest 字典。
        :return: 归一化后的元数据字典。
        """
        # 归一化 arch 字段：允许字符串或列表
        arch_raw = manifest.get("arch", ["universal"])
        if isinstance(arch_raw, str):
            # 逗号分隔或单架构
            if "," in arch_raw:
                arch_list = [a.strip() for a in arch_raw.split(",") if a.strip()]
            else:
                arch_list = [arch_raw.strip()]
        elif isinstance(arch_raw, list):
            arch_list = [str(a).strip() for a in arch_raw if str(a).strip()]
        else:
            arch_list = ["universal"]

        # 归一化 dependencies 字段
        deps_raw = manifest.get("dependencies", [])
        if isinstance(deps_raw, str):
            deps_list = [d.strip() for d in deps_raw.split(",") if d.strip()]
        elif isinstance(deps_raw, list):
            deps_list = [str(d) for d in deps_raw]
        else:
            deps_list = []

        return {
            "id": str(manifest.get("skill_id", manifest.get("id", ""))).strip(),
            "name": str(manifest.get("name", manifest.get("skill_id", manifest.get("id", "")))).strip(),
            "version": str(manifest.get("version", "0.0.0")).strip(),
            "description": str(manifest.get("description", "")),
            "entry": str(manifest.get("entry_file", manifest.get("entry", "main.py"))).strip(),
            "entry_function": str(manifest.get("entry_function", "run")).strip(),
            "author": str(manifest.get("author", "")),
            "category": str(manifest.get("category", "other")).strip() or "other",
            "language": str(manifest.get("language", "python")).strip()
            or "python",
            "min_app_version": str(
                manifest.get("min_app_version", "0.0.0")
            ).strip()
            or "0.0.0",
            "arch": arch_list,
            "skill_type": str(manifest.get("skill_type", "python")).strip()
            or "python",
            "dependencies": deps_list,
        }

    # ------------------------------------------------------------------
    # 元数据读写
    # ------------------------------------------------------------------

    def get_skill_meta(self, skill_id: str) -> Optional[Dict[str, Any]]:
        """读取指定 skill 的完整元数据。

        :param skill_id: Skill 唯一标识符。
        :return: 元数据字典；若 skill 不存在则返回 None。
        """
        meta_path = self._meta_path(skill_id)
        if not meta_path.is_file():
            return None
        try:
            with meta_path.open("r", encoding="utf-8") as f:
                return json.load(f)
        except (json.JSONDecodeError, OSError):
            return None

    def _save_skill_meta(self, skill_id: str, meta: Dict[str, Any]) -> None:
        """写入指定 skill 的完整元数据。

        :param skill_id: Skill 唯一标识符。
        :param meta: 元数据字典。
        """
        meta_path = self._meta_path(skill_id)
        meta_path.parent.mkdir(parents=True, exist_ok=True)
        # 先写入临时文件再原子替换，避免写入中断导致损坏
        tmp_path = meta_path.with_suffix(".json.tmp")
        with tmp_path.open("w", encoding="utf-8") as f:
            json.dump(meta, f, ensure_ascii=False, indent=2)
        tmp_path.replace(meta_path)

    # ------------------------------------------------------------------
    # 保存 skill
    # ------------------------------------------------------------------

    def save_skill(self, metadata: Dict[str, Any], file_data: bytes) -> Dict[str, Any]:
        """保存 skill 包文件及其元数据。

        执行流程：
        1. 解析 .bsk 中的 manifest.json；
        2. 将 manifest 字段与上传 metadata 合并（manifest 为核心字段来源，
           上传 metadata 补充市场专属字段）；
        3. 计算 SHA256 与文件大小；
        4. 将 .bsk 写入 ``{skill_id}/{version}/skill.bsk``；
        5. 更新 ``{skill_id}/meta.json`` 的版本历史。

        :param metadata: 上传时附加的 metadata 字典（可能为空）。
        :param file_data: ``.bsk`` 文件的完整字节内容。
        :return: 包含 ``skill_id``、``version``、``sha256``、
            ``size_bytes``、``upload_date`` 的字典。
        :raises ValueError: .bsk 格式非法或元数据冲突时抛出。
        """
        # 1. 解析 manifest
        manifest = self._parse_bsk_manifest(file_data)
        normalized = self._normalize_manifest(manifest)

        skill_id = normalized["id"]
        version = normalized["version"]

        if not skill_id:
            raise ValueError("manifest.json 中 skill_id 不能为空")
        if not version:
            raise ValueError("manifest.json 中 version 不能为空")

        # 2. 合并上传 metadata（上传 metadata 可补充/覆盖市场专属字段）
        merged = dict(normalized)
        if metadata and isinstance(metadata, dict):
            for key in (
                "category",
                "language",
                "min_app_version",
                "arch",
                "skill_type",
                "dependencies",
                "description",
                "author",
                "name",
            ):
                if key in metadata and metadata[key] not in (None, "", []):
                    # 特殊处理 arch / dependencies 的类型归一化
                    if key == "arch":
                        merged["arch"] = self._normalize_manifest(
                            {"arch": metadata[key]}
                        )["arch"]
                    elif key == "dependencies":
                        merged["dependencies"] = self._normalize_manifest(
                            {"dependencies": metadata[key]}
                        )["dependencies"]
                    else:
                        merged[key] = metadata[key]

        # 3. 计算校验和与大小
        sha256 = self._calculate_sha256(file_data)
        size_bytes = len(file_data)
        upload_date = self._now_iso()

        with self._lock:
            # 4. 写入 .bsk 文件
            pkg_path = self._package_path(skill_id, version)
            pkg_path.parent.mkdir(parents=True, exist_ok=True)
            with pkg_path.open("wb") as f:
                f.write(file_data)

            # 5. 更新 meta.json
            meta = self.get_skill_meta(skill_id) or {
                "skill_id": skill_id,
                "name": merged["name"],
                "category": merged["category"],
                "versions": [],
            }

            # 更新顶层冗余字段（便于列表查询时快速读取）
            meta["skill_id"] = skill_id
            meta["name"] = merged["name"]
            meta["category"] = merged["category"]

            versions: List[Dict[str, Any]] = meta.get("versions", [])

            # 构建新版本条目
            version_entry: Dict[str, Any] = {
                "version": version,
                "sha256": sha256,
                "upload_date": upload_date,
                "download_count": 0,
                "size_bytes": size_bytes,
                "metadata": merged,
            }

            # 若该版本已存在则替换，否则追加
            replaced = False
            for i, existing in enumerate(versions):
                if existing.get("version") == version:
                    # 保留已有下载计数
                    version_entry["download_count"] = existing.get(
                        "download_count", 0
                    )
                    versions[i] = version_entry
                    replaced = True
                    break
            if not replaced:
                versions.append(version_entry)

            # 按版本号降序排列，使最新版本在前
            versions.sort(key=lambda v: _parse_version(v["version"]), reverse=True)
            meta["versions"] = versions

            self._save_skill_meta(skill_id, meta)

        return {
            "skill_id": skill_id,
            "version": version,
            "sha256": sha256,
            "size_bytes": size_bytes,
            "upload_date": upload_date,
        }

    # ------------------------------------------------------------------
    # 版本查询
    # ------------------------------------------------------------------

    def get_skill_versions(self, skill_id: str) -> List[Dict[str, Any]]:
        """获取指定 skill 的所有版本信息。

        :param skill_id: Skill 唯一标识符。
        :return: 版本信息列表（按版本号降序），每项包含
            ``version``、``sha256``、``upload_date``、
            ``download_count``、``size_bytes``。skill 不存在时返回空列表。
        """
        meta = self.get_skill_meta(skill_id)
        if meta is None:
            return []
        versions = meta.get("versions", [])
        return [
            {
                "version": v.get("version", ""),
                "sha256": v.get("sha256", ""),
                "upload_date": v.get("upload_date", ""),
                "download_count": v.get("download_count", 0),
                "size_bytes": v.get("size_bytes", 0),
            }
            for v in versions
        ]

    def get_latest_version_entry(
        self, skill_id: str
    ) -> Optional[Dict[str, Any]]:
        """获取指定 skill 最新版本（含完整 metadata）的条目。

        :param skill_id: Skill 唯一标识符。
        :return: 最新版本条目字典；skill 不存在时返回 None。
        """
        meta = self.get_skill_meta(skill_id)
        if meta is None:
            return None
        versions = meta.get("versions", [])
        if not versions:
            return None
        # versions 已按降序排列，第一个即最新
        return versions[0]

    # ------------------------------------------------------------------
    # 文件路径
    # ------------------------------------------------------------------

    def get_skill_file_path(self, skill_id: str, version: str) -> Optional[Path]:
        """获取指定 skill 某版本的 .bsk 文件路径。

        :param skill_id: Skill 唯一标识符。
        :param version: 版本号。
        :return: 文件 :class:`~pathlib.Path`；若文件不存在则返回 None。
        """
        pkg_path = self._package_path(skill_id, version)
        if pkg_path.is_file():
            return pkg_path
        return None

    # ------------------------------------------------------------------
    # 下载计数
    # ------------------------------------------------------------------

    def increment_download_count(self, skill_id: str, version: str) -> None:
        """将指定 skill 某版本的下载计数加 1。

        若版本不存在则静默忽略。

        :param skill_id: Skill 唯一标识符。
        :param version: 版本号。
        """
        with self._lock:
            meta = self.get_skill_meta(skill_id)
            if meta is None:
                return
            versions = meta.get("versions", [])
            for v in versions:
                if v.get("version") == version:
                    v["download_count"] = v.get("download_count", 0) + 1
                    break
            self._save_skill_meta(skill_id, meta)

    # ------------------------------------------------------------------
    # 删除版本
    # ------------------------------------------------------------------

    def delete_skill_version(self, skill_id: str, version: str) -> bool:
        """删除指定 skill 的某个版本。

        删除该版本的 .bsk 文件，并从 meta.json 的版本历史中移除。
        若删除后该 skill 不再有任何版本，则连同整个 skill 目录一并删除。

        :param skill_id: Skill 唯一标识符。
        :param version: 版本号。
        :return: 成功删除返回 True；skill 或版本不存在返回 False。
        """
        import shutil

        with self._lock:
            meta = self.get_skill_meta(skill_id)
            if meta is None:
                return False

            versions = meta.get("versions", [])
            # 查找待删除版本
            found = False
            remaining: List[Dict[str, Any]] = []
            for v in versions:
                if v.get("version") == version:
                    found = True
                else:
                    remaining.append(v)

            if not found:
                return False

            # 删除 .bsk 文件及版本目录
            version_dir = self._version_dir(skill_id, version)
            if version_dir.exists():
                shutil.rmtree(version_dir, ignore_errors=True)

            if remaining:
                # 重新排序并保存
                remaining.sort(
                    key=lambda v: _parse_version(v["version"]), reverse=True
                )
                meta["versions"] = remaining
                # 更新顶层 name/category 为剩余最新版本的信息
                latest = remaining[0].get("metadata", {})
                meta["name"] = latest.get("name", meta.get("name", skill_id))
                meta["category"] = latest.get("category", meta.get("category", "other"))
                self._save_skill_meta(skill_id, meta)
            else:
                # 没有剩余版本，删除整个 skill 目录
                skill_dir = self._skill_dir(skill_id)
                if skill_dir.exists():
                    shutil.rmtree(skill_dir, ignore_errors=True)

            return True

    # ------------------------------------------------------------------
    # 列表查询
    # ------------------------------------------------------------------

    def list_skills(
        self,
        arch: Optional[str] = None,
        app_version: Optional[str] = None,
        category: Optional[str] = None,
        search: Optional[str] = None,
    ) -> List[Dict[str, Any]]:
        """列出所有符合过滤条件的 skill。

        每个返回项为一个字典，包含该 skill 最新版本的完整信息：
        ``metadata``（归一化的 SkillMetadata 字段）、``latest_version``、
        ``sha256``、``download_count``（所有版本合计）、``size_bytes``、
        ``download_url``。

        :param arch: 客户端架构，用于过滤兼容的 skill。
            为 ``universal`` 的 skill 始终兼容；为 None 时不过滤。
        :param app_version: 客户端 App 版本，用于过滤最低版本要求。
            为 None 时不过滤。
        :param category: 分类过滤，为 None 时不过滤。
        :param search: 全文搜索关键词（匹配 name 与 description，不区分大小写）。
            为 None 时不过滤。
        :return: 符合条件的 skill 信息列表。
        """
        results: List[Dict[str, Any]] = []

        if not self.storage_dir.is_dir():
            return results

        # 遍历存储目录下的每个 skill 目录
        for skill_dir in sorted(self.storage_dir.iterdir()):
            if not skill_dir.is_dir():
                continue
            skill_id = skill_dir.name
            meta = self.get_skill_meta(skill_id)
            if meta is None:
                continue

            versions = meta.get("versions", [])
            if not versions:
                continue

            # 取最新版本（已降序排列）
            latest_entry = versions[0]
            metadata = latest_entry.get("metadata", {})

            # --- 架构过滤 ---
            if arch:
                skill_archs = metadata.get("arch", ["universal"])
                if (
                    config.UNIVERSAL_ARCH not in skill_archs
                    and arch not in skill_archs
                ):
                    continue

            # --- App 版本过滤 ---
            if app_version:
                min_app_version = metadata.get("min_app_version", "0.0.0")
                if not _version_gte(app_version, min_app_version):
                    continue

            # --- 分类过滤 ---
            if category:
                if metadata.get("category", "other") != category:
                    continue

            # --- 全文搜索 ---
            if search:
                keyword = search.lower().strip()
                name = str(metadata.get("name", "")).lower()
                description = str(metadata.get("description", "")).lower()
                sid = str(metadata.get("id", "")).lower()
                if keyword and keyword not in name and keyword not in description and keyword not in sid:
                    continue

            # 计算所有版本的下载次数合计
            total_downloads = sum(
                v.get("download_count", 0) for v in versions
            )

            results.append(
                {
                    "metadata": metadata,
                    "latest_version": latest_entry.get("version", ""),
                    "sha256": latest_entry.get("sha256", ""),
                    "size_bytes": latest_entry.get("size_bytes", 0),
                    "upload_date": latest_entry.get("upload_date", ""),
                    "download_count": total_downloads,
                    "versions_count": len(versions),
                    "skill_id": skill_id,
                }
            )

        return results

    # ------------------------------------------------------------------
    # 分类列表
    # ------------------------------------------------------------------

    def get_categories(self) -> List[str]:
        """获取所有已上传 skill 使用过的分类列表（去重排序）。

        :return: 分类名称列表。
        """
        categories: set = set()
        if not self.storage_dir.is_dir():
            return []

        for skill_dir in self.storage_dir.iterdir():
            if not skill_dir.is_dir():
                continue
            meta = self.get_skill_meta(skill_dir.name)
            if meta is None:
                continue
            for v in meta.get("versions", []):
                cat = v.get("metadata", {}).get("category")
                if cat:
                    categories.add(cat)

        return sorted(categories)

    # ------------------------------------------------------------------
    # 详情查询
    # ------------------------------------------------------------------

    def get_skill_detail(self, skill_id: str) -> Optional[Dict[str, Any]]:
        """获取指定 skill 的详细信息（含全部版本历史）。

        :param skill_id: Skill 唯一标识符。
        :return: 详情字典，包含 ``metadata``（最新版本）、
            ``versions``（全部 VersionInfo 列表）、``download_count``（合计）；
            skill 不存在时返回 None。
        """
        meta = self.get_skill_meta(skill_id)
        if meta is None:
            return None

        versions = meta.get("versions", [])
        if not versions:
            return None

        latest_entry = versions[0]
        metadata = latest_entry.get("metadata", {})
        total_downloads = sum(v.get("download_count", 0) for v in versions)

        version_infos = [
            {
                "version": v.get("version", ""),
                "sha256": v.get("sha256", ""),
                "upload_date": v.get("upload_date", ""),
                "download_count": v.get("download_count", 0),
                "size_bytes": v.get("size_bytes", 0),
            }
            for v in versions
        ]

        return {
            "metadata": metadata,
            "latest_version": latest_entry.get("version", ""),
            "sha256": latest_entry.get("sha256", ""),
            "size_bytes": latest_entry.get("size_bytes", 0),
            "upload_date": latest_entry.get("upload_date", ""),
            "download_count": total_downloads,
            "versions": version_infos,
            "skill_id": skill_id,
        }
