#!/usr/bin/env python3
"""
Skill 构建器单元测试
测试 smart_skill_builder.py 的分析、打包逻辑
"""
import os
import sys
import json
import shutil
import tempfile
import zipfile
import unittest
from pathlib import Path

# 将项目根目录加入 sys.path
PROJECT_ROOT = Path(__file__).parent.parent
sys.path.insert(0, str(PROJECT_ROOT))

from smart_skill_builder import (
    SkillAnalyzer,
    SmartSkillBuilder,
    C_EXTENSION_PACKAGES,
    PURE_PYTHON_PACKAGES,
)


class TestSkillAnalyzer(unittest.TestCase):
    """SkillAnalyzer 单元测试"""

    def setUp(self):
        self.tmpdir = tempfile.mkdtemp()
        self.skill_dir = Path(self.tmpdir) / "test_skill"
        self.skill_dir.mkdir()

    def tearDown(self):
        shutil.rmtree(self.tmpdir)

    def _create_skill_md(self, metadata: dict, body: str = ""):
        """创建 SKILL.md 文件"""
        import yaml
        content = f"---\n{yaml.dump(metadata, allow_unicode=True)}---\n{body}"
        (self.skill_dir / "SKILL.md").write_text(content)

    def _create_main_py(self, code: str):
        """创建 main.py"""
        (self.skill_dir / "main.py").write_text(code)

    def _create_requirements(self, deps: list):
        """创建 requirements.txt"""
        (self.skill_dir / "requirements.txt").write_text("\n".join(deps))

    def test_pure_python_skill(self):
        """测试纯 Python skill 分析"""
        self._create_skill_md({"name": "test_pure", "version": "1.0.0"})
        self._create_main_py("def main(args): return args")
        self._create_requirements(["pdfplumber", "openpyxl", "requests"])

        analyzer = SkillAnalyzer(str(self.skill_dir))
        result = analyzer.analyze()

        self.assertFalse(result["requires_compilation"])
        self.assertEqual(len(result["c_dependencies"]), 0)
        self.assertIn("pdfplumber", result["pure_python_deps"])

    def test_c_extension_skill(self):
        """测试含 C 扩展依赖的 skill 分析"""
        self._create_skill_md({"name": "test_c_ext", "version": "1.0.0"})
        self._create_main_py("from PIL import Image\ndef main(args): return args")
        self._create_requirements(["Pillow", "lxml", "pdfplumber"])

        analyzer = SkillAnalyzer(str(self.skill_dir))
        result = analyzer.analyze()

        self.assertTrue(result["requires_compilation"])
        self.assertIn("Pillow", result["c_dependencies"])
        self.assertIn("lxml", result["c_dependencies"])
        self.assertIn("pdfplumber", result["pure_python_deps"])

    def test_skill_with_c_source(self):
        """测试包含 .c 源文件的 skill"""
        self._create_skill_md({"name": "test_c_src", "version": "1.0.0"})
        self._create_main_py("def main(args): return args")
        (self.skill_dir / "native.c").write_text("// C source")

        analyzer = SkillAnalyzer(str(self.skill_dir))
        result = analyzer.analyze()

        self.assertTrue(result["requires_compilation"])
        self.assertIn("native.c", result["c_source_files"])

    def test_no_dependencies(self):
        """测试无依赖的 skill"""
        self._create_skill_md({"name": "test_no_deps", "version": "1.0.0"})
        self._create_main_py("def main(args): return {'result': 'ok'}")

        analyzer = SkillAnalyzer(str(self.skill_dir))
        result = analyzer.analyze()

        self.assertFalse(result["requires_compilation"])
        self.assertEqual(result["total_deps"], 0)

    def test_import_scanning(self):
        """测试 import 语句扫描"""
        self._create_skill_md({"name": "test_imports", "version": "1.0.0"})
        self._create_main_py("""
import os
import sys
from PIL import Image
import pdfplumber
def main(args): return args
""")

        analyzer = SkillAnalyzer(str(self.skill_dir))
        imports = analyzer._scan_imports()

        self.assertIn("os", imports)
        self.assertIn("PIL", imports)
        self.assertIn("pdfplumber", imports)

    def test_mixed_dependencies(self):
        """测试混合依赖"""
        self._create_skill_md({"name": "test_mixed", "version": "1.0.0"})
        self._create_main_py("def main(args): return args")
        self._create_requirements([
            "pdfplumber",   # pure python
            "Pillow",       # C extension
            "numpy",        # C extension
            "requests",     # pure python
        ])

        analyzer = SkillAnalyzer(str(self.skill_dir))
        result = analyzer.analyze()

        self.assertTrue(result["requires_compilation"])
        self.assertEqual(len(result["c_dependencies"]), 2)
        self.assertEqual(len(result["pure_python_deps"]), 2)


class TestSmartSkillBuilder(unittest.TestCase):
    """SmartSkillBuilder 单元测试"""

    def setUp(self):
        self.tmpdir = tempfile.mkdtemp()
        self.skills_root = Path(self.tmpdir) / "skills"
        self.output_dir = Path(self.tmpdir) / "output"
        self.skills_root.mkdir()

    def tearDown(self):
        shutil.rmtree(self.tmpdir)

    def _create_skill(self, name: str, deps: list = None, has_c: bool = False):
        """创建测试 skill"""
        import yaml
        skill_dir = self.skills_root / name
        skill_dir.mkdir()

        meta = {"name": name, "version": "1.0.0", "description": f"Test skill {name}"}
        content = f"---\n{yaml.dump(meta, allow_unicode=True)}---\n# {name}"
        (skill_dir / "SKILL.md").write_text(content)

        (skill_dir / "main.py").write_text(
            "def main(args):\n    return {'skill': '" + name + "', 'args': args}\n"
        )

        if deps:
            (skill_dir / "requirements.txt").write_text("\n".join(deps))

        if has_c:
            (skill_dir / "native.c").write_text("// native code")

        return skill_dir

    def test_build_pure_python_skill(self):
        """测试纯 Python skill 打包"""
        self._create_skill("pdf_tool", deps=["pdfplumber", "openpyxl"])

        config = {"output_dir": str(self.output_dir)}
        builder = SmartSkillBuilder(config)
        bsk_path = builder.build_one(str(self.skills_root / "pdf_tool"))

        self.assertIsNotNone(bsk_path)
        self.assertTrue(os.path.exists(bsk_path))

        # 验证 .bsk 内容
        with zipfile.ZipFile(bsk_path, 'r') as zf:
            names = zf.namelist()
            self.assertIn("manifest.json", names)
            self.assertIn("__init__.py", names)
            self.assertIn("skill/main.py", names)
            self.assertIn("skill/SKILL.md", names)

            # 验证 manifest
            manifest = json.loads(zf.read("manifest.json"))
            self.assertEqual(manifest["skill_type"], "pure_python")
            self.assertEqual(manifest["name"], "pdf_tool")
            self.assertIn("pdfplumber", manifest["dependencies"]["pure_python"])

    def test_build_c_extension_skill(self):
        """测试 C 扩展 skill 打包（无 NDK，标记外部依赖）"""
        self._create_skill("image_tool", deps=["Pillow", "pdfplumber"])

        config = {"output_dir": str(self.output_dir)}  # 无 ndk_path
        builder = SmartSkillBuilder(config)
        bsk_path = builder.build_one(str(self.skills_root / "image_tool"))

        self.assertIsNotNone(bsk_path)

        with zipfile.ZipFile(bsk_path, 'r') as zf:
            manifest = json.loads(zf.read("manifest.json"))
            self.assertEqual(manifest["skill_type"], "c_extension")
            self.assertIn("Pillow", manifest["dependencies"]["c_extensions"])

    def test_build_all(self):
        """测试批量构建"""
        self._create_skill("skill_a", deps=["pdfplumber"])
        self._create_skill("skill_b", deps=["Pillow"])
        self._create_skill("skill_c")

        config = {"output_dir": str(self.output_dir)}
        builder = SmartSkillBuilder(config)
        report = builder.build_all(str(self.skills_root))

        self.assertEqual(report["total"], 3)
        self.assertEqual(len(report["bsk_files"]), 3)
        self.assertIn("skill_a", report["pure_python"])
        self.assertIn("skill_b", report["needs_compilation"])
        self.assertIn("skill_c", report["pure_python"])

    def test_entry_wrapper_generation(self):
        """测试入口包装器生成"""
        self._create_skill("test_wrapper")

        config = {"output_dir": str(self.output_dir)}
        builder = SmartSkillBuilder(config)
        bsk_path = builder.build_one(str(self.skills_root / "test_wrapper"))

        with zipfile.ZipFile(bsk_path, 'r') as zf:
            entry_code = zf.read("__init__.py").decode()
            self.assertIn("def run(args", entry_code)
            self.assertIn("from skill.main import main", entry_code)
            self.assertIn("_HAS_C_DEPS", entry_code)

    def test_manifest_structure(self):
        """测试 manifest.json 结构完整性"""
        self._create_skill("manifest_test", deps=["pdfplumber", "Pillow"])

        config = {"output_dir": str(self.output_dir)}
        builder = SmartSkillBuilder(config)
        bsk_path = builder.build_one(str(self.skills_root / "manifest_test"))

        with zipfile.ZipFile(bsk_path, 'r') as zf:
            manifest = json.loads(zf.read("manifest.json"))

            required_fields = [
                "id", "name", "version", "description", "entry",
                "entry_function", "author", "category", "language",
                "min_app_version", "arch", "skill_type", "dependencies"
            ]
            for field in required_fields:
                self.assertIn(field, manifest, f"Missing field: {field}")

            self.assertIn("pure_python", manifest["dependencies"])
            self.assertIn("c_extensions", manifest["dependencies"])
            self.assertIsInstance(manifest["arch"], list)


class TestCExtensionDatabase(unittest.TestCase):
    """C 扩展数据库测试"""

    def test_known_c_extensions(self):
        """验证已知 C 扩展包"""
        self.assertIn("Pillow", C_EXTENSION_PACKAGES)
        self.assertIn("PyMuPDF", C_EXTENSION_PACKAGES)
        self.assertIn("lxml", C_EXTENSION_PACKAGES)
        self.assertIn("numpy", C_EXTENSION_PACKAGES)

    def test_pure_python_whitelist(self):
        """验证纯 Python 白名单"""
        self.assertIn("pdfplumber", PURE_PYTHON_PACKAGES)
        self.assertIn("openpyxl", PURE_PYTHON_PACKAGES)
        self.assertIn("requests", PURE_PYTHON_PACKAGES)

    def test_no_overlap(self):
        """确保 C 扩展和纯 Python 列表无重叠"""
        c_set = set(C_EXTENSION_PACKAGES.keys())
        py_set = set(PURE_PYTHON_PACKAGES)
        overlap = c_set & py_set
        self.assertEqual(len(overlap), 0, f"Overlap found: {overlap}")


if __name__ == "__main__":
    unittest.main(verbosity=2)
