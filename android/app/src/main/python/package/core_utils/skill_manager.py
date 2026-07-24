"""
Butler Skill Manager — 技能包管理、安装、卸载、市场对接

技能包格式 (.bsk = zip):
  my-skill.bsk
  ├── manifest.json    # { id, name, version, desc, entry, ... }
  ├── main.py          # 入口模块 (必须包含 run() 函数)
  ├── icon.png         # 可选图标
  └── assets/          # 可选资源

技能目录结构:
  {SKILLS_ROOT}/
  ├── builtin/         # 预置技能 (随 APK 打包)
  ├── external/        # 用户安装的技能 (从市场下载)
  └── custom/          # 用户手动放入的技能包/目录
"""

import os
import sys
import json
import zipfile
import shutil
import hashlib
import logging
import tempfile
import threading
import traceback
from pathlib import Path
from typing import Dict, Any, List, Optional
from urllib.parse import urlparse
from datetime import datetime

logger = logging.getLogger("SkillManager")

# ── 技能包元数据校验 ──────────────────────────────────────────────

REQUIRED_MANIFEST_FIELDS = ["id", "name", "version", "entry"]
OPTIONAL_MANIFEST_FIELDS = [
    "author", "description", "icon", "entry_function",
    "language", "dependencies", "permissions", "category",
    "color", "homepage", "min_app_version", "tags"
]


def validate_manifest(manifest: dict) -> Optional[str]:
    """校验 manifest.json 合法性, 返回错误信息或 None"""
    if not isinstance(manifest, dict):
        return "manifest.json must be a JSON object"
    for field in REQUIRED_MANIFEST_FIELDS:
        if field not in manifest:
            return f"manifest.json missing required field: {field}"
    if not manifest["id"].strip():
        return "skill id must not be empty"
    if not manifest["entry"].strip():
        return "skill entry must not be empty"
    # 校验 id 格式: 字母数字连字符下划线
    import re
    if not re.match(r'^[a-zA-Z0-9_\-\.]+$', manifest["id"]):
        return f"invalid skill id: {manifest['id']} (only alphanumeric, -, _, . allowed)"
    return None


def compute_sha256(file_path: str) -> str:
    """计算文件的 SHA256 哈希"""
    sha = hashlib.sha256()
    with open(file_path, 'rb') as f:
        for chunk in iter(lambda: f.read(8192), b''):
            sha.update(chunk)
    return sha.hexdigest()


# ── SkillManager ──────────────────────────────────────────────────

class SkillManager:
    """
    技能全生命周期管理:
      - 发现: 扫描 builtin/ external/ custom/ 三个目录
      - 安装: 从 URL 下载 .bsk 包, 或从本地文件解压安装
      - 卸载: 从 external/ 目录移除
      - 市场: 内置市场清单 + 可扩展远程市场源
    """

    # 默认技能存放根目录 (Android 外部存储)
    DEFAULT_SKILLS_ROOT = "/sdcard/Butler/skills"

    # 内置市场 (可扩展为远程 URL)
    BUILTIN_MARKETPLACE: List[Dict[str, Any]] = [
        {
            "id": "audio_denoiser",
            "name": "音频降噪",
            "version": "1.0.0",
            "author": "Butler Team",
            "description": "基于 FFT 的本地音频降噪处理, 支持 WAV/MP3",
            "category": "media",
            "icon": "fa-wave-square",
            "color": "#34C759",
            "tags": ["audio", "denoise", "fft"],
            "download_url": "https://market.butler.app/skills/audio_denoiser_v1.0.0.bsk",
            "size": "2.3 MB",
            "downloads": 1520,
            "rating": 4.5,
        },
        {
            "id": "image_compressor",
            "name": "图片压缩",
            "version": "2.1.0",
            "author": "Butler Team",
            "description": "批量图片压缩, 支持 WebP/JPEG/PNG 格式转换",
            "category": "media",
            "icon": "fa-image",
            "color": "#FF9500",
            "tags": ["image", "compress", "webp"],
            "download_url": "https://market.butler.app/skills/image_compressor_v2.1.0.bsk",
            "size": "1.8 MB",
            "downloads": 3200,
            "rating": 4.8,
        },
        {
            "id": "text_summarizer",
            "name": "文本摘要",
            "version": "1.2.0",
            "author": "Community",
            "description": "基于 NLP 的长文本智能摘要, 支持中英文",
            "category": "ai",
            "icon": "fa-file-lines",
            "color": "#007AFF",
            "tags": ["nlp", "summary", "text"],
            "download_url": "https://market.butler.app/skills/text_summarizer_v1.2.0.bsk",
            "size": "4.1 MB",
            "downloads": 890,
            "rating": 4.2,
        },
        {
            "id": "qr_scanner",
            "name": "二维码扫描",
            "version": "1.0.1",
            "author": "Community",
            "description": "快速识别和生成二维码/条形码",
            "category": "vision",
            "icon": "fa-qrcode",
            "color": "#5856D6",
            "tags": ["qr", "barcode", "scan"],
            "download_url": "https://market.butler.app/skills/qr_scanner_v1.0.1.bsk",
            "size": "0.9 MB",
            "downloads": 4500,
            "rating": 4.6,
        },
        {
            "id": "pdf_merger",
            "name": "PDF 合并",
            "version": "1.3.0",
            "author": "Butler Team",
            "description": "合并、拆分、旋转 PDF 文档, 支持加密解密",
            "category": "document",
            "icon": "fa-file-pdf",
            "color": "#FF3B30",
            "tags": ["pdf", "merge", "document"],
            "download_url": "https://market.butler.app/skills/pdf_merger_v1.3.0.bsk",
            "size": "3.5 MB",
            "downloads": 2100,
            "rating": 4.4,
        },
        {
            "id": "network_scanner",
            "name": "网络扫描",
            "version": "2.0.0",
            "author": "Community",
            "description": "局域网设备发现、端口扫描与网络诊断",
            "category": "network",
            "icon": "fa-network-wired",
            "color": "#AF52DE",
            "tags": ["network", "scan", "lan"],
            "download_url": "https://market.butler.app/skills/network_scanner_v2.0.0.bsk",
            "size": "1.5 MB",
            "downloads": 780,
            "rating": 4.0,
        },
    ]

    def __init__(self, skills_root: str = None):
        self.skills_root = skills_root or self.DEFAULT_SKILLS_ROOT
        self._lock = threading.Lock()

        # 确保目录结构存在
        for subdir in ["builtin", "external", "custom"]:
            os.makedirs(os.path.join(self.skills_root, subdir), exist_ok=True)

        logger.info(f"SkillManager initialized, root: {self.skills_root}")

    # ── 技能发现 ──────────────────────────────────────────────────

    def list_all_skills(self) -> List[Dict[str, Any]]:
        """列出所有已安装的技能 (builtin + external + custom)"""
        skills = []
        seen = set()

        for source in ["builtin", "external", "custom"]:
            dir_path = os.path.join(self.skills_root, source)
            if not os.path.isdir(dir_path):
                continue
            for entry in os.listdir(dir_path):
                entry_path = os.path.join(dir_path, entry)
                manifest = self._read_manifest(entry_path)
                if manifest and manifest.get("id") not in seen:
                    manifest["source"] = source
                    manifest["install_path"] = entry_path
                    manifest["status"] = "loaded"
                    skills.append(manifest)
                    seen.add(manifest["id"])

        # 补充内置技能（如果 builtin 目录为空，回退到 package 扫描）
        if not any(s["source"] == "builtin" for s in skills):
            builtin = self._scan_builtin_package_skills()
            for b in builtin:
                if b["id"] not in seen:
                    b["source"] = "builtin"
                    skills.append(b)
                    seen.add(b["id"])

        return skills

    def get_skill(self, skill_id: str) -> Optional[Dict[str, Any]]:
        """获取单个技能详情"""
        for skill in self.list_all_skills():
            if skill["id"] == skill_id:
                return skill
        return None

    def _scan_builtin_package_skills(self) -> List[Dict[str, Any]]:
        """扫描 package/ 目录下的内置 Python 模块作为技能"""
        skills = []
        try:
            # 获取 package 目录
            current_dir = os.path.dirname(os.path.abspath(__file__))
            package_dir = os.path.dirname(current_dir)  # package/

            # 扫描子目录中带 run() 函数的模块
            skill_dirs = [
                "core_utils", "security", "network", "file_system",
                "vision", "device", "media", "document", "algorithm"
            ]
            for subdir in skill_dirs:
                sub_path = os.path.join(package_dir, subdir)
                if not os.path.isdir(sub_path):
                    continue
                for fname in os.listdir(sub_path):
                    if fname.startswith("_") or not fname.endswith(".py"):
                        continue
                    fpath = os.path.join(sub_path, fname)
                    try:
                        with open(fpath, 'r', encoding='utf-8') as f:
                            content = f.read()
                        if "def run(" in content:
                            skill_id = fname[:-3]
                            skills.append({
                                "id": skill_id,
                                "name": self._skill_display_name(skill_id),
                                "version": "1.0.0",
                                "description": self._extract_docstring(content),
                                "entry": f"package.{subdir}.{skill_id}",
                                "entry_function": "run",
                                "language": "python",
                                "category": subdir,
                                "icon": self._skill_icon(skill_id),
                                "color": self._skill_color(skill_id),
                                "source": "builtin",
                                "status": "loaded",
                            })
                    except Exception:
                        pass
        except Exception as e:
            logger.warning(f"Builtin package scan failed: {e}")
        return skills

    # ── 技能安装 ──────────────────────────────────────────────────

    def install_from_url(self, url: str) -> Dict[str, Any]:
        """从 URL 下载 .bsk 包并安装"""
        import urllib.request

        parsed = urlparse(url)
        if not parsed.scheme or not parsed.netloc:
            return {"ok": False, "error": "Invalid URL"}

        # 下载到临时文件
        tmp_dir = tempfile.mkdtemp(prefix="butler_skill_")
        tmp_file = os.path.join(tmp_dir, "download.bsk")
        try:
            logger.info(f"Downloading skill from: {url}")
            req = urllib.request.Request(url, headers={
                "User-Agent": "Butler-SkillManager/1.0"
            })
            with urllib.request.urlopen(req, timeout=60) as resp:
                with open(tmp_file, 'wb') as f:
                    shutil.copyfileobj(resp, f, length=8192)
            logger.info(f"Downloaded to: {tmp_file}")

            result = self.install_from_file(tmp_file)
            return result
        except Exception as e:
            logger.error(f"Download failed: {e}")
            return {"ok": False, "error": f"Download failed: {str(e)}"}
        finally:
            shutil.rmtree(tmp_dir, ignore_errors=True)

    def install_from_file(self, file_path: str) -> Dict[str, Any]:
        """从本地 .bsk 文件安装技能"""
        if not os.path.isfile(file_path):
            return {"ok": False, "error": f"File not found: {file_path}"}

        tmp_dir = tempfile.mkdtemp(prefix="butler_skill_install_")
        try:
            # 解压
            with zipfile.ZipFile(file_path, 'r') as zf:
                zf.extractall(tmp_dir)

            # 读取 manifest
            manifest_path = os.path.join(tmp_dir, "manifest.json")
            if not os.path.isfile(manifest_path):
                return {"ok": False, "error": "manifest.json not found in package"}

            with open(manifest_path, 'r', encoding='utf-8') as f:
                manifest = json.load(f)

            # 校验
            err = validate_manifest(manifest)
            if err:
                return {"ok": False, "error": err}

            skill_id = manifest["id"]

            # 检查是否已安装
            existing = self.get_skill(skill_id)
            if existing:
                return {
                    "ok": False,
                    "error": f"Skill '{skill_id}' already installed (v{existing.get('version', '?')})",
                    "existing_version": existing.get("version"),
                }

            with self._lock:
                # 复制到 external 目录
                dest_dir = os.path.join(self.skills_root, "external", skill_id)
                if os.path.exists(dest_dir):
                    shutil.rmtree(dest_dir)
                shutil.copytree(tmp_dir, dest_dir)

                # 写入安装记录
                manifest["installed_at"] = datetime.now().isoformat()
                manifest["install_source"] = "file"
                with open(os.path.join(dest_dir, "manifest.json"), 'w', encoding='utf-8') as f:
                    json.dump(manifest, f, ensure_ascii=False, indent=2)

            logger.info(f"Skill installed: {skill_id} v{manifest.get('version')}")
            return {
                "ok": True,
                "skill": {
                    "id": skill_id,
                    "name": manifest.get("name", skill_id),
                    "version": manifest.get("version"),
                    "description": manifest.get("description", ""),
                    "category": manifest.get("category"),
                    "icon": manifest.get("icon", "fa-puzzle-piece"),
                    "color": manifest.get("color", "#007AFF"),
                    "source": "external",
                    "status": "loaded",
                }
            }
        except zipfile.BadZipFile:
            return {"ok": False, "error": "Invalid skill package (not a valid zip file)"}
        except Exception as e:
            logger.error(f"Install failed: {e}\n{traceback.format_exc()}")
            return {"ok": False, "error": str(e)}
        finally:
            shutil.rmtree(tmp_dir, ignore_errors=True)

    # ── 技能卸载 ──────────────────────────────────────────────────

    def uninstall_skill(self, skill_id: str) -> Dict[str, Any]:
        """卸载一个外部安装的技能 (不允许卸载内置技能)"""
        with self._lock:
            skill = self.get_skill(skill_id)
            if not skill:
                return {"ok": False, "error": f"Skill not found: {skill_id}"}
            if skill.get("source") == "builtin":
                return {"ok": False, "error": "Cannot uninstall builtin skills"}

            path = skill.get("install_path")
            if path and os.path.isdir(path):
                shutil.rmtree(path)
                logger.info(f"Skill uninstalled: {skill_id}")
                return {"ok": True}
            return {"ok": False, "error": "Skill path not found"}

    # ── 技能市场 ──────────────────────────────────────────────────

    def get_marketplace_skills(self) -> List[Dict[str, Any]]:
        """获取市场技能列表"""
        installed_ids = {s["id"] for s in self.list_all_skills()}

        result = []
        for skill in self.BUILTIN_MARKETPLACE:
            entry = dict(skill)
            entry["installed"] = skill["id"] in installed_ids
            if entry["installed"]:
                installed = self.get_skill(skill["id"])
                entry["installed_version"] = installed.get("version") if installed else None
            result.append(entry)
        return result

    # ── 自定义目录扫描 ────────────────────────────────────────────

    def scan_custom_dir(self, dir_path: str = None) -> Dict[str, Any]:
        """扫描自定义目录中的技能包并安装"""
        if dir_path is None:
            dir_path = os.path.join(self.skills_root, "custom")

        if not os.path.isdir(dir_path):
            return {"ok": False, "error": f"Directory not found: {dir_path}"}

        installed = []
        failed = []

        for entry in os.listdir(dir_path):
            entry_path = os.path.join(dir_path, entry)

            # 处理 .bsk 压缩包
            if entry.endswith(".bsk"):
                result = self.install_from_file(entry_path)
                if result.get("ok"):
                    installed.append(result.get("skill", {}).get("id", entry))
                else:
                    failed.append({"file": entry, "error": result.get("error")})

            # 处理已解压的目录
            elif os.path.isdir(entry_path):
                manifest = self._read_manifest(entry_path)
                if manifest:
                    with self._lock:
                        dest_dir = os.path.join(self.skills_root, "external", manifest["id"])
                        if not os.path.exists(dest_dir):
                            shutil.copytree(entry_path, dest_dir)
                            installed.append(manifest["id"])
                            logger.info(f"Custom skill imported: {manifest['id']}")

        return {
            "ok": True,
            "installed": installed,
            "failed": failed,
            "scanned_dir": dir_path,
        }

    # ── 辅助方法 ──────────────────────────────────────────────────

    def _read_manifest(self, entry_path: str) -> Optional[Dict[str, Any]]:
        """读取技能目录中的 manifest.json"""
        if not os.path.isdir(entry_path):
            return None
        manifest_path = os.path.join(entry_path, "manifest.json")
        if not os.path.isfile(manifest_path):
            return None
        try:
            with open(manifest_path, 'r', encoding='utf-8') as f:
                manifest = json.load(f)
            if validate_manifest(manifest) is None:
                return manifest
        except Exception:
            pass
        return None

    def _skill_display_name(self, skill_id: str) -> str:
        """从 skill_id 生成显示名称"""
        names = {
            "system_executor_tool": "系统审计",
            "hybrid_orchestrator": "混合编排",
            "dev_tools": "开发工具",
            "data_analyzer": "数据分析",
            "crypto_core": "加密核心",
            "encrypt": "文件加密",
            "quarantine": "安全隔离",
            "crawler": "网络爬虫",
            "weather": "天气查询",
            "network_app": "网络工具",
            "OrganizeIT": "文件整理",
            "archiver": "归档管理",
            "file_manager": "文件管理",
            "terminal": "终端",
            "os_utils": "系统工具",
            "music": "音乐播放",
            "PictureRecognition": "图像识别",
            "QR_Code_Recognition": "QR 识别",
            "Word_reading": "文字识别",
            "expense_report_engine": "报销引擎",
            "file_converter": "文件转换",
            "markdown_converter": "Markdown 转换",
            "translators": "翻译器",
            "algorithm": "算法工具",
            "math_tool": "数学工具",
        }
        return names.get(skill_id, skill_id.replace("_", " ").title())

    def _skill_icon(self, skill_id: str) -> str:
        """根据 skill_id 返回合适的 Font Awesome 图标"""
        icons = {
            "system_executor_tool": "fa-shield-halved",
            "hybrid_orchestrator": "fa-diagram-project",
            "dev_tools": "fa-code",
            "data_analyzer": "fa-chart-line",
            "crypto_core": "fa-lock",
            "encrypt": "fa-file-shield",
            "quarantine": "fa-biohazard",
            "crawler": "fa-spider",
            "weather": "fa-cloud-sun",
            "network_app": "fa-wifi",
            "OrganizeIT": "fa-folder-tree",
            "archiver": "fa-file-zipper",
            "file_manager": "fa-folder-open",
            "terminal": "fa-terminal",
            "os_utils": "fa-microchip",
            "music": "fa-music",
            "PictureRecognition": "fa-camera",
            "QR_Code_Recognition": "fa-qrcode",
            "Word_reading": "fa-glasses",
            "expense_report_engine": "fa-receipt",
            "file_converter": "fa-rotate",
            "markdown_converter": "fa-markdown",
            "translators": "fa-language",
            "algorithm": "fa-calculator",
            "math_tool": "fa-square-root-variable",
        }
        return icons.get(skill_id, "fa-puzzle-piece")

    def _skill_color(self, skill_id: str) -> str:
        """根据 skill_id 返回颜色"""
        colors = {
            "system_executor_tool": "#007AFF",
            "hybrid_orchestrator": "#AF52DE",
            "dev_tools": "#5856D6",
            "data_analyzer": "#34C759",
            "crypto_core": "#FF3B30",
            "encrypt": "#FF9500",
            "quarantine": "#FF2D55",
            "crawler": "#FF9500",
            "weather": "#5AC8FA",
            "network_app": "#64D2FF",
            "OrganizeIT": "#34C759",
            "archiver": "#FFCC00",
            "file_manager": "#007AFF",
            "terminal": "#1D1D1F",
            "os_utils": "#8E8E93",
            "music": "#FF2D55",
            "PictureRecognition": "#AF52DE",
            "QR_Code_Recognition": "#5856D6",
            "Word_reading": "#5AC8FA",
            "expense_report_engine": "#34C759",
            "file_converter": "#FF9500",
            "markdown_converter": "#007AFF",
            "translators": "#34C759",
            "algorithm": "#FF3B30",
            "math_tool": "#AF52DE",
        }
        return colors.get(skill_id, "#007AFF")

    def _extract_docstring(self, content: str) -> str:
        """从 Python 源码提取模块文档字符串"""
        lines = content.strip().split('\n')
        if lines and lines[0].startswith('"""'):
            desc_lines = []
            for line in lines[1:]:
                if '"""' in line:
                    break
                desc_lines.append(line.strip())
            return " ".join(desc_lines)[:100]
        return ""


# ── 全局单例 ──────────────────────────────────────────────────────

_skill_manager_instance: Optional[SkillManager] = None


def get_skill_manager(skills_root: str = None) -> SkillManager:
    """获取 SkillManager 单例"""
    global _skill_manager_instance
    if _skill_manager_instance is None:
        _skill_manager_instance = SkillManager(skills_root)
    return _skill_manager_instance