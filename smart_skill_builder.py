#!/usr/bin/env python3
"""
智能 Skill 打包流水线
自动检测 skill 类型 → 纯Python直接打包 / C扩展自动交叉编译 → 统一输出 .bsk
"""
import os
import re
import json
import shutil
import zipfile
import subprocess
import ast
import sys
from pathlib import Path
from typing import Optional


# ============================================================
# C 扩展特征数据库：已知需要交叉编译的包
# ============================================================
C_EXTENSION_PACKAGES = {
    # 图像处理
    "Pillow": {"so_names": ["_imaging.so", "_imagingcms.so", "_webp.so"]},
    "PyMuPDF": {"so_names": ["_fitz.so"], "needs_mupdf": True},
    "opencv-python": {"so_names": ["cv2.so"], "cmake": True},
    # XML / 数据处理
    "lxml": {"so_names": ["etree.so", "lxml.etree.so"]},
    # 科学计算
    "numpy": {"so_names": ["_multiarray_umath.so", "_core.so"]},
    "scipy": {"so_names": ["_ccallback.so"], "needs_blas": True},
    "pandas": {"so_names": ["_libs.so"], "depends_on": ["numpy"]},
    # 加密
    "cryptography": {"so_names": ["_rust.abi3.so"], "rust": True},
    "pycryptodome": {"so_names": ["_raw_aes.so", "_raw_md5.so"]},
    # 数据库
    "sqlalchemy": {"so_names": [], "optional_c": True},
    # 网络
    "psutil": {"so_names": ["_psutil_linux.so"]},
    "yarl": {"so_names": ["_quoting.so"]},
    "multidict": {"so_names": ["_multidict.so"]},
    # 压缩
    "brotli": {"so_names": ["_brotli.so"]},
    "zstandard": {"so_names": ["_zstd.so"]},
    # 其他
    "regex": {"so_names": ["_regex.so"]},
    "markupsafe": {"so_names": ["_speedups.so"]},
    "ujson": {"so_names": ["_ujson.so"]},
}

# 纯 Python 包白名单：确认无需编译
PURE_PYTHON_PACKAGES = {
    "pdfminer.six", "pdfplumber", "python-docx", "tabulate", "openpyxl",
    "markitdown", "beautifulsoup4", "requests", "urllib3", "certifi",
    "chardet", "idna", "soupsieve", "pyyaml", "jinja2",
    "click", "rich", "typer", "pydantic", "fastapi", "starlette",
}


class SkillAnalyzer:
    """分析 skill 依赖，判断是否需要交叉编译"""

    def __init__(self, skill_dir: str):
        self.skill_dir = Path(skill_dir)
        self.skill_name = self.skill_dir.name
        self.requires_compilation = False
        self.c_dependencies = []
        self.pure_python_deps = []
        self.analysis_details = {}

    def analyze(self) -> dict:
        """执行完整分析"""
        print(f"\n{'='*60}")
        print(f"分析 Skill: {self.skill_name}")
        print(f"{'='*60}")

        # 1. 检查 requirements.txt / setup.py / pyproject.toml
        deps = self._extract_dependencies()
        print(f"  发现依赖: {deps}")

        # 2. 扫描 .c / .cpp / .h 源文件
        c_sources = self._scan_c_sources()
        print(f"  C/C++ 源文件: {c_sources if c_sources else '无'}")

        # 3. 扫描 import 语句
        imports = self._scan_imports()
        print(f"  import 语句: {len(imports)} 个")

        # 4. 分类依赖
        for dep in deps:
            dep_normalized = dep.lower().replace("-", "_")
            if dep in C_EXTENSION_PACKAGES or dep_normalized in {k.lower() for k in C_EXTENSION_PACKAGES}:
                self.c_dependencies.append(dep)
                self.requires_compilation = True
            elif dep in PURE_PYTHON_PACKAGES or dep_normalized in {k.lower() for k in PURE_PYTHON_PACKAGES}:
                self.pure_python_deps.append(dep)
            else:
                # 未知包，保守检查是否有 C 扩展
                if self._check_unknown_package(dep):
                    self.c_dependencies.append(dep)
                    self.requires_compilation = True
                else:
                    self.pure_python_deps.append(dep)

        # 5. 检查 skill 自身是否包含 C 源码
        if c_sources:
            self.requires_compilation = True
            self.analysis_details["has_c_source"] = True

        # 汇总
        result = {
            "skill_name": self.skill_name,
            "requires_compilation": self.requires_compilation,
            "c_dependencies": self.c_dependencies,
            "pure_python_deps": self.pure_python_deps,
            "c_source_files": c_sources,
            "total_deps": len(deps),
        }
        self.analysis_details = result

        # 打印结论
        if self.requires_compilation:
            print(f"\n  [结论] 需要 C 扩展编译")
            print(f"  C 依赖: {self.c_dependencies}")
        else:
            print(f"\n  [结论] 纯 Python，可直接打包")

        return result

    def _extract_dependencies(self) -> list:
        """从 requirements.txt / setup.py / pyproject.toml 提取依赖"""
        deps = []

        # requirements.txt
        req_file = self.skill_dir / "requirements.txt"
        if req_file.exists():
            with open(req_file) as f:
                for line in f:
                    line = line.strip()
                    if line and not line.startswith("#"):
                        # 提取包名（去掉版本号）
                        pkg = re.split(r'[>=<\[]', line)[0].strip()
                        if pkg:
                            deps.append(pkg)

        # setup.py
        setup_file = self.skill_dir / "setup.py"
        if setup_file.exists():
            with open(setup_file) as f:
                content = f.read()
                # 简单提取 install_requires
                match = re.search(r'install_requires\s*=\s*\[(.*?)\]', content, re.DOTALL)
                if match:
                    for m in re.finditer(r"['\"]([^'\"]+)['\"]", match.group(1)):
                        pkg = re.split(r'[>=<\[]', m.group(1))[0].strip()
                        if pkg and pkg not in deps:
                            deps.append(pkg)

        # pyproject.toml
        pyproject = self.skill_dir / "pyproject.toml"
        if pyproject.exists():
            with open(pyproject) as f:
                content = f.read()
                for m in re.finditer(r'"([^"]+)"', content):
                    pkg = re.split(r'[>=<\[]', m.group(1))[0].strip()
                    if pkg and pkg not in deps and not pkg.startswith(("http", "git", "file")):
                        deps.append(pkg)

        # SKILL.md frontmatter 中的 dependencies
        skill_md = self.skill_dir / "SKILL.md"
        if skill_md.exists():
            with open(skill_md) as f:
                content = f.read()
                match = re.match(r'^---\n(.*?)\n---\n', content, re.DOTALL)
                if match:
                    # 查找 dependencies 字段
                    for m in re.finditer(r'-\s+(.+)', match.group(1)):
                        pkg = m.group(1).strip()
                        if pkg and pkg not in deps:
                            deps.append(pkg)

        return deps

    def _scan_c_sources(self) -> list:
        """扫描 skill 目录中的 C/C++ 源文件"""
        c_extensions = {".c", ".cpp", ".cc", ".cxx", ".h", ".hpp"}
        c_files = []
        for f in self.skill_dir.rglob("*"):
            if f.suffix in c_extensions and "__pycache__" not in str(f):
                c_files.append(str(f.relative_to(self.skill_dir)))
        return c_files

    def _scan_imports(self) -> list:
        """扫描 Python 文件中的 import 语句"""
        imports = []
        for py_file in self.skill_dir.rglob("*.py"):
            if "__pycache__" in str(py_file):
                continue
            try:
                with open(py_file) as f:
                    tree = ast.parse(f.read())
                    for node in ast.walk(tree):
                        if isinstance(node, ast.Import):
                            for alias in node.names:
                                imports.append(alias.name)
                        elif isinstance(node, ast.ImportFrom):
                            if node.module:
                                imports.append(node.module)
            except SyntaxError:
                continue
        return list(set(imports))

    def _check_unknown_package(self, pkg_name: str) -> bool:
        """检查未知包是否包含 C 扩展（通过 PyPI API）"""
        # 保守策略：未知包默认按纯 Python 处理
        # 可扩展为查询 PyPI JSON API
        return False


class CrossCompiler:
    """C 扩展交叉编译器"""

    def __init__(self, config: dict):
        self.ndk_path = config.get("ndk_path", os.environ.get("NDK", ""))
        self.python_version = config.get("python_version", "3.12.3")
        self.abi = config.get("abi", "arm64-v8a")
        self.android_api = config.get("android_api", 28)
        self.build_dir = config.get("build_dir", "/tmp/pybridge-build")
        self.output_dir = config.get("output_dir", "prebuilt-packages")

        # 确定 NDK 工具链
        abi_map = {
            "arm64-v8a": "aarch64-linux-android",
            "armeabi-v7a": "armv7a-linux-androideabi",
            "x86_64": "x86_64-linux-android",
        }
        self.target_triple = abi_map.get(self.abi, "aarch64-linux-android")
        self.toolchain = f"{self.ndk_path}/toolchains/llvm/prebuilt/linux-x86_64"

    def compile_package(self, pkg_name: str) -> dict:
        """
        交叉编译单个 C 扩展包

        Returns:
            {
                "package": pkg_name,
                "success": bool,
                "so_files": [list of .so paths],
                "error": optional error message
            }
        """
        print(f"\n  [编译] {pkg_name} ({self.abi})")

        result = {
            "package": pkg_name,
            "success": False,
            "so_files": [],
        }

        if not self.ndk_path:
            result["error"] = "NDK path not configured"
            print(f"  [跳过] NDK 未配置: {pkg_name}")
            return result

        # 设置交叉编译环境变量
        clang = f"{self.toolchain}/bin/{self.target_triple}{self.android_api}-clang"
        env = os.environ.copy()
        env.update({
            "CC": clang,
            "CXX": clang + "++",
            "AR": f"{self.toolchain}/bin/llvm-ar",
            "RANLIB": f"{self.toolchain}/bin/llvm-ranlib",
            "STRIP": f"{self.toolchain}/bin/llvm-strip",
            "CFLAGS": f"-fPIC -I{self.build_dir}/install-{self.abi}/include/python{self.python_version[:3]}",
            "LDFLAGS": f"-L{self.build_dir}/install-{self.abi}/lib",
            "PYTHONPATH": f"{self.build_dir}/install-{self.abi}/lib/python{self.python_version[:3]}",
            "_PYTHON_SYSCONFIGDATA_NAME": f"_sysconfigdata__linux_android_{self.abi.replace('-', '_')}",
        })

        # 下载源码
        pkg_build = Path(self.build_dir) / f"build-{pkg_name}-{self.abi}"
        pkg_build.mkdir(parents=True, exist_ok=True)

        try:
            # pip download 源码
            subprocess.run(
                ["pip", "download", pkg_name, "--no-binary", ":all:",
                 "-d", str(pkg_build / "src")],
                check=True, capture_output=True, env=env, timeout=300
            )

            # 解压
            sdist = next(pkg_build.glob("src/*.tar.gz"), None)
            if not sdist:
                sdist = next(pkg_build.glob("src/*.zip"), None)
            if not sdist:
                result["error"] = "No source distribution found"
                return result

            if sdist.suffix == ".gz":
                subprocess.run(["tar", "xzf", str(sdist), "-C", str(pkg_build)], check=True)
            else:
                subprocess.run(["unzip", "-q", str(sdist), "-d", str(pkg_build)], check=True)

            # 找到源码目录
            src_dirs = [d for d in pkg_build.iterdir() if d.is_dir() and d.name != "src"]
            if not src_dirs:
                result["error"] = "Cannot find extracted source directory"
                return result
            src_dir = src_dirs[0]

            # 执行编译
            pkg_info = C_EXTENSION_PACKAGES.get(pkg_name, {})
            compile_cmd = self._build_compile_cmd(src_dir, pkg_name, pkg_info)

            completed = subprocess.run(
                compile_cmd, cwd=str(src_dir), env=env,
                capture_output=True, text=True, timeout=600
            )

            if completed.returncode != 0:
                result["error"] = completed.stderr[-500:] if completed.stderr else "Build failed"
                print(f"  [失败] {pkg_name}: {result['error'][:100]}")
                return result

            # 收集 .so 文件
            so_output = Path(self.output_dir) / self.abi
            so_output.mkdir(parents=True, exist_ok=True)

            for so_file in src_dir.rglob("*.so"):
                dest = so_output / f"{pkg_name}_{so_file.name}"
                shutil.copy2(so_file, dest)
                result["so_files"].append(str(dest))

            # 收集 Python 源码
            subprocess.run(
                [sys.executable, "setup.py", "install",
                 f"--prefix={self.output_dir}/{self.abi}/{pkg_name}",
                 "--no-compile"],
                cwd=str(src_dir), env=env, capture_output=True, timeout=300
            )

            result["success"] = True
            print(f"  [成功] {pkg_name}: {len(result['so_files'])} 个 .so")

        except subprocess.TimeoutExpired:
            result["error"] = f"Compilation timeout for {pkg_name}"
            print(f"  [超时] {pkg_name}")
        except Exception as e:
            result["error"] = str(e)
            print(f"  [异常] {pkg_name}: {e}")

        return result

    def _build_compile_cmd(self, src_dir: Path, pkg_name: str, pkg_info: dict) -> list:
        """根据包类型构建编译命令"""
        py_inc = f"{self.build_dir}/install-{self.abi}/include/python{self.python_version[:3]}"
        py_lib = f"{self.build_dir}/install-{self.abi}/lib"

        cmd = [
            sys.executable, "setup.py", "build_ext",
            f"--include-dirs={py_inc}",
            f"--library-dirs={py_lib}",
            "--force"
        ]

        # 特殊处理
        if pkg_name == "PyMuPDF":
            cmd = [sys.executable, "setup.py", "build_ext", "--inplace"]
        elif pkg_name == "numpy":
            cmd.extend(["--define-macros=NPY_BLAS_ORDER=", "NPY_LAPACK_ORDER="])

        return cmd

    def compile_all(self, packages: list) -> dict:
        """批量编译"""
        results = {}
        for pkg in packages:
            results[pkg] = self.compile_package(pkg)
        return results


class SmartSkillBuilder:
    """智能 Skill 打包流水线主控"""

    def __init__(self, config: dict = None):
        self.config = config or {}
        self.output_dir = Path(self.config.get("output_dir", "android_skills"))
        self.output_dir.mkdir(parents=True, exist_ok=True)

        self.analyzer = None
        self.compiler = CrossCompiler(self.config) if self.config.get("ndk_path") else None

    def build_all(self, skills_root: str) -> dict:
        """
        批量处理所有 skill

        Args:
            skills_root: skills 根目录，如 butler/skills/

        Returns:
            构建报告
        """
        skills_root = Path(skills_root)
        if not skills_root.exists():
            return {"error": f"Skills directory not found: {skills_root}"}

        report = {
            "total": 0,
            "pure_python": [],
            "needs_compilation": [],
            "compiled": [],
            "failed": [],
            "bsk_files": [],
        }

        # 遍历所有 skill 目录
        skill_dirs = [d for d in skills_root.iterdir() if d.is_dir() and (d / "SKILL.md").exists()]

        report["total"] = len(skill_dirs)
        print(f"\n发现 {len(skill_dirs)} 个 skill")

        for skill_dir in skill_dirs:
            bsk_path = self.build_one(str(skill_dir))

            if bsk_path:
                report["bsk_files"].append(bsk_path)
                analysis = self.analyzer.analysis_details
                if analysis.get("requires_compilation"):
                    report["needs_compilation"].append(skill_dir.name)
                    if self.compiler:
                        report["compiled"].append(skill_dir.name)
                else:
                    report["pure_python"].append(skill_dir.name)
            else:
                report["failed"].append(skill_dir.name)

        # 打印汇总
        self._print_report(report)
        return report

    def build_one(self, skill_dir: str) -> Optional[str]:
        """
        处理单个 skill

        Returns:
            生成的 .bsk 路径，失败返回 None
        """
        # 1. 分析
        self.analyzer = SkillAnalyzer(skill_dir)
        analysis = self.analyzer.analyze()

        # 2. 如果需要编译，先编译 C 扩展
        deps_map = {}
        if analysis["requires_compilation"] and self.compiler:
            print(f"\n  开始交叉编译 C 依赖...")
            compile_results = self.compiler.compile_all(analysis["c_dependencies"])

            for pkg, res in compile_results.items():
                deps_map[pkg] = {
                    "compiled": res["success"],
                    "so_files": res["so_files"],
                    "error": res.get("error"),
                }

                if not res["success"]:
                    print(f"  [警告] {pkg} 编译失败，skill 可能无法正常运行")
        elif analysis["requires_compilation"] and not self.compiler:
            print(f"\n  [警告] 需要编译但 NDK 未配置，仅打包纯 Python 部分")
            print(f"  C 依赖 {analysis['c_dependencies']} 将标记为外部依赖")

        # 3. 打包 .bsk
        return self._package_bsk(skill_dir, analysis, deps_map)

    @staticmethod
    def _detect_entry_point(skill_dir: Path) -> dict:
        """自动检测 skill 的入口函数和模式。

        扫描 __init__.py / main.py，识别两种入口风格：
        - Butler 风格：``handle_request(action, **kwargs)``
        - 通用风格：``main(args)``

        :return: ``{"module": str, "func": str, "style": "butler"|"generic"}``
        """
        # 优先扫描 __init__.py，再回退到 main.py
        for fname in ("__init__.py", "main.py"):
            fpath = skill_dir / fname
            if not fpath.exists():
                continue
            try:
                with open(fpath, "r", encoding="utf-8") as f:
                    source = f.read()
                tree = ast.parse(source)
            except (SyntaxError, UnicodeDecodeError):
                continue
            for node in ast.walk(tree):
                if isinstance(node, ast.FunctionDef):
                    if node.name == "handle_request":
                        return {"module": fname[:-3], "func": "handle_request", "style": "butler"}
                    if node.name == "main":
                        return {"module": fname[:-3], "func": "main", "style": "generic"}
        # 默认：假设 main.py 有 main()
        return {"module": "main", "func": "main", "style": "generic"}

    def _package_bsk(self, skill_dir: str, analysis: dict, deps_map: dict) -> str:
        """打包 skill 为 .bsk"""
        skill_dir = Path(skill_dir)
        skill_name = skill_dir.name

        # 读取 SKILL.md（若不存在则尝试 manifest.json）
        meta = {}
        skill_md = skill_dir / "SKILL.md"
        if skill_md.exists():
            with open(skill_md, 'r', encoding='utf-8') as f:
                content = f.read()
                match = re.match(r'^---\n(.*?)\n---\n', content, re.DOTALL)
                if match:
                    import yaml
                    meta = yaml.safe_load(match.group(1)) or {}
        else:
            manifest_path = skill_dir / "manifest.json"
            if manifest_path.exists():
                with open(manifest_path, 'r', encoding='utf-8') as f:
                    meta = json.load(f)

        # 自动检测入口函数
        entry_info = self._detect_entry_point(skill_dir)

        # 生成 manifest.json
        manifest = {
            "id": meta.get("name", skill_name),
            "name": meta.get("name", skill_name),
            "version": meta.get("version", "1.0.0"),
            "description": meta.get("description", ""),
            "entry": "__init__.py",
            "entry_function": "run",
            "author": meta.get("author", ""),
            "category": meta.get("category", "general"),
            "language": "python",
            "min_app_version": "1.0.0",
            "arch": [self.config.get("abi", "arm64-v8a")],
            "skill_type": "c_extension" if analysis["requires_compilation"] else "pure_python",
            "dependencies": {
                "pure_python": analysis["pure_python_deps"],
                "c_extensions": analysis["c_dependencies"],
            },
            "deps_map": deps_map,
            "original_entry": entry_info,
        }

        # 临时构建目录
        build_tmp = self.output_dir / f".{skill_name}_build"
        if build_tmp.exists():
            shutil.rmtree(build_tmp)
        build_tmp.mkdir(parents=True)

        # 复制 skill 代码
        skill_code = build_tmp / "skill"
        shutil.copytree(skill_dir, skill_code, ignore=shutil.ignore_patterns(
            "__pycache__", "*.pyc", ".git", "tests", "*.c", "*.cpp", "*.h"
        ))

        # 写入 manifest.json
        with open(build_tmp / "manifest.json", 'w', encoding='utf-8') as f:
            json.dump(manifest, f, indent=2, ensure_ascii=False)

        # 生成入口包装器
        entry_wrapper = build_tmp / "__init__.py"
        with open(entry_wrapper, 'w', encoding='utf-8') as f:
            f.write(self._generate_entry_wrapper(skill_name, manifest))

        # 写入 deps_map.json（供运行时使用）
        if deps_map:
            with open(build_tmp / "deps_map.json", 'w', encoding='utf-8') as f:
                json.dump(deps_map, f, indent=2, ensure_ascii=False)

        # 打包
        bsk_path = self.output_dir / f"{skill_name}.bsk"
        with zipfile.ZipFile(bsk_path, 'w', zipfile.ZIP_DEFLATED) as zf:
            for file_path in build_tmp.rglob("*"):
                if file_path.is_file():
                    arcname = file_path.relative_to(build_tmp)
                    zf.write(file_path, arcname)

        shutil.rmtree(build_tmp)

        skill_type = "C扩展" if analysis["requires_compilation"] else "纯Python"
        print(f"\n  [打包完成] {skill_name}.bsk ({skill_type})")
        return str(bsk_path)

    def _generate_entry_wrapper(self, skill_name: str, manifest: dict) -> str:
        """生成 Android 入口包装器

        根据原始 skill 的入口风格自动适配：
        - ``butler`` 风格：调用 ``handle_request(action, **kwargs)``
        - ``generic`` 风格：调用 ``main(args)``
        """
        c_deps = manifest.get("dependencies", {}).get("c_extensions", [])
        has_c_deps = "True" if c_deps else "False"

        entry_info = manifest.get("original_entry", {})
        style = entry_info.get("style", "generic")
        orig_module = entry_info.get("module", "main")
        orig_func = entry_info.get("func", "main")

        # 导入语句：__init__ 对应 skill 包根，main 对应 skill.main
        if orig_module == "__init__":
            import_stmt = "from skill import handle_request as _orig_entry"
        else:
            import_stmt = f"from skill.{orig_module} import {orig_func} as _orig_entry"

        # 调用逻辑：根据风格构造不同的调用代码
        if style == "butler":
            call_block = '''    # Butler 风格入口：handle_request(action, **kwargs)
    action = args.get("action", "run")
    kwargs = {k: v for k, v in args.items() if k != "action"}
    try:
        result = _orig_entry(action, **kwargs)
        # 字符串结果包装为标准 dict
        if isinstance(result, str):
            return {"success": True, "data": result}
        return {"success": True, "data": result}
    except Exception as e:
        import traceback
        return {"success": False, "error": str(e), "traceback": traceback.format_exc()}'''
        else:
            call_block = '''    try:
        result = _orig_entry(args)
        return {"success": True, "data": result}
    except Exception as e:
        import traceback
        return {"success": False, "error": str(e), "traceback": traceback.format_exc()}'''

        return f'''"""Android Skill 入口（自动生成 by smart_skill_builder）"""
import sys
import os
import json

_SKILL_DIR = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, _SKILL_DIR)

# 加载依赖映射
_HAS_C_DEPS = {has_c_deps}
_DEPS_MAP = {{}}

_deps_map_path = os.path.join(_SKILL_DIR, "deps_map.json")
if os.path.exists(_deps_map_path):
    with open(_deps_map_path, "r") as f:
        _DEPS_MAP = json.load(f)

# 导入原始入口（风格: {style}）
{import_stmt}

def run(args: dict) -> dict:
    """
    Android 统一入口

    Args:
        args: 调用参数

    Returns:
        执行结果字典
    """
    # 检查 C 扩展依赖是否可用
    if _HAS_C_DEPS:
        for pkg, info in _DEPS_MAP.items():
            if info.get("compiled"):
                # .so 已预装在 APK 中，验证可加载
                try:
                    __import__(pkg.split(".")[0])
                except ImportError as e:
                    return {{"success": False, "error": f"C 扩展 {{pkg}} 不可用: {{e}}"}}
            else:
                return {{"success": False, "error": f"C 扩展 {{pkg}} 未编译，无法运行"}}

{call_block}
'''

    def _print_report(self, report: dict):
        """打印构建报告"""
        print(f"\n{'='*60}")
        print(f"构建报告")
        print(f"{'='*60}")
        print(f"  总计: {report['total']}")
        print(f"  纯 Python: {len(report['pure_python'])} → {report['pure_python']}")
        print(f"  需编译: {len(report['needs_compilation'])} → {report['needs_compilation']}")

        if report.get("compiled"):
            print(f"  编译成功: {len(report['compiled'])} → {report['compiled']}")

        if report["failed"]:
            print(f"  失败: {len(report['failed'])} → {report['failed']}")
        else:
            print(f"  失败: 0")

        print(f"\n  .bsk 文件:")
        for bsk in report["bsk_files"]:
            size = os.path.getsize(bsk) / 1024
            print(f"    {bsk} ({size:.0f} KB)")

        print(f"\n{'='*60}")


# ============================================================
# 主入口
# ============================================================
if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser(description="智能 Skill 打包流水线")
    parser.add_argument("skills_dir", help="skills 根目录路径")
    parser.add_argument("-o", "--output", default="android_skills", help="输出目录")
    parser.add_argument("--ndk", default=os.environ.get("NDK", ""), help="Android NDK 路径")
    parser.add_argument("--abi", default="arm64-v8a", help="目标 ABI")
    parser.add_argument("--python-version", default="3.12.3", help="Python 版本")
    parser.add_argument("--single", help="只处理单个 skill（指定目录名）")

    args = parser.parse_args()

    config = {
        "output_dir": args.output,
        "ndk_path": args.ndk,
        "abi": args.abi,
        "python_version": args.python_version,
        "build_dir": "/tmp/pybridge-build",
    }

    builder = SmartSkillBuilder(config)

    if args.single:
        # 单个 skill
        skill_path = os.path.join(args.skills_dir, args.single)
        builder.build_one(skill_path)
    else:
        # 批量处理
        report = builder.build_all(args.skills_dir)

        # 保存报告
        report_path = os.path.join(args.output, "build_report.json")
        with open(report_path, "w") as f:
            json.dump(report, f, indent=2, ensure_ascii=False)
        print(f"\n报告已保存: {report_path}")
