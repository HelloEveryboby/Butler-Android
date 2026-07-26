import os
import sys
import json
import shutil
import base64
import sqlite3
import time
from typing import List, Optional, Dict, Any
from butler.core.hybrid_link import HybridLinkClient

# 获取项目根目录
PROJECT_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

class MemosSkill:
    """
    备忘录技能类 (Memos Skill) - 单例模式
    直接读取 SQLite 数据库并处理多媒体附件和 AI 分析。
    """
    _instance = None

    def __new__(cls, *args, **kwargs):
        if not cls._instance:
            cls._instance = super(MemosSkill, cls).__new__(cls)
            cls._instance._initialized = False
        return cls._instance

    def __init__(self):
        if self._initialized:
            return

        # Go 后端路径
        self.executable_path = os.path.join(PROJECT_ROOT, "programs/hybrid_memos/memos_service")
        # 数据库路径
        self.db_path = os.path.join(PROJECT_ROOT, "data/memos/memos.db")
        # 附件存储路径
        self.attachments_dir = os.path.join(PROJECT_ROOT, "data/memos/attachments")

        os.makedirs(os.path.dirname(self.db_path), exist_ok=True)
        os.makedirs(self.attachments_dir, exist_ok=True)

        # 确保数据库和表格及列存在
        self._ensure_db_schema()

        # 初始化混合链接客户端（保留兼容性，尽管我们直接使用 Python 处理 core 操作）
        if not os.path.exists(self.executable_path):
            print(f"警告：备忘录 Go 后端未找到，继续使用原生 Python SQLite 引擎。")
            self.client = None
        else:
            self.client = HybridLinkClient(
                executable_path=self.executable_path,
                fallback_enabled=False
            )
            os.environ["BUTLER_MEMO_DB"] = self.db_path
            try:
                self.client.start()
            except Exception as e:
                print(f"警告：启动 Go Memos 后端失败，退回到 Python 数据库连接：{e}")

        self._initialized = True

    def _ensure_db_schema(self):
        """确保 SQLite 数据库和最新的表格列属性均正常就绪"""
        conn = sqlite3.connect(self.db_path, timeout=10)
        cursor = conn.cursor()
        cursor.execute("""
        CREATE TABLE IF NOT EXISTS memos (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            content TEXT,
            tags TEXT,
            resources TEXT,
            created_at INTEGER,
            updated_at INTEGER
        );
        """)

        # 动态数据库迁移，检测并添加新增列
        migrations = [
            ("ALTER TABLE memos ADD COLUMN is_pinned INTEGER DEFAULT 0", "is_pinned"),
            ("ALTER TABLE memos ADD COLUMN is_archived INTEGER DEFAULT 0", "is_archived"),
            ("ALTER TABLE memos ADD COLUMN title TEXT", "title"),
            ("ALTER TABLE memos ADD COLUMN status TEXT DEFAULT '进行中'", "status")
        ]
        for query, col in migrations:
            try:
                cursor.execute(query)
            except sqlite3.OperationalError:
                pass # 已经存在该列，直接跳过

        conn.commit()
        conn.close()

    def get_conn(self):
        """获取带 Row Factory 的数据库连接，便于快速字典化处理"""
        conn = sqlite3.connect(self.db_path, timeout=10)
        conn.row_factory = sqlite3.Row
        return conn

    def _row_to_dict(self, row) -> Dict[str, Any]:
        """将数据库行数据优雅地转换为标准前端可读格式"""
        d = dict(row)

        # 解析 tags (存储为 JSON 字符串)
        if d.get("tags"):
            try:
                d["tags"] = json.loads(d["tags"])
            except Exception:
                d["tags"] = []
        else:
            d["tags"] = []

        # 解析 resources (存储为 JSON 字符串)
        if d.get("resources"):
            try:
                d["resources"] = json.loads(d["resources"])
            except Exception:
                d["resources"] = []
        else:
            d["resources"] = []

        # 缺失字段设定安全默认值
        if d.get("is_pinned") is None:
            d["is_pinned"] = 0
        if d.get("is_archived") is None:
            d["is_archived"] = 0
        if d.get("title") is None:
            d["title"] = ""
        if d.get("status") is None:
            d["status"] = "进行中"

        return d

    def add_memo(self, content: str, tags: List[str] = None, title: str = None, status: str = "进行中", files: List[str] = None, base64_files: List[Dict[str, str]] = None):
        """添加一条备忘录记录，支持本地文件、Base64 以及新的 Title 和 Status 属性"""
        resources = []

        # 处理本地文件
        if files:
            for file_path in files:
                if os.path.exists(file_path):
                    file_name = os.path.basename(file_path)
                    target_name = self._get_safe_filename(file_name)
                    target_path = os.path.join(self.attachments_dir, target_name)
                    shutil.copy2(file_path, target_path)
                    resources.append(f"data/memos/attachments/{target_name}")

        # 处理 Base64 文件 (通常来自 Web UI)
        if base64_files:
            for f in base64_files:
                name = f.get("name")
                data = f.get("data")
                if name and data:
                    # 移除 Base64 前缀
                    if "," in data:
                        data = data.split(",")[1]

                    target_name = self._get_safe_filename(name)
                    target_path = os.path.join(self.attachments_dir, target_name)
                    with open(target_path, "wb") as fb:
                        fb.write(base64.b64decode(data))
                    resources.append(f"data/memos/attachments/{target_name}")

        now = int(time.time())
        conn = self.get_conn()
        cursor = conn.cursor()
        cursor.execute("""
            INSERT INTO memos (content, tags, resources, created_at, updated_at, is_pinned, is_archived, title, status)
            VALUES (?, ?, ?, ?, ?, 0, 0, ?, ?)
        """, (content, json.dumps(tags or []), json.dumps(resources), now, now, title or "", status or "进行中"))
        new_id = cursor.lastrowid
        conn.commit()
        conn.close()
        return {"id": new_id}

    def _get_safe_filename(self, filename: str) -> str:
        """获取不重复的文件名"""
        if not os.path.exists(os.path.join(self.attachments_dir, filename)):
            return filename
        base, ext = os.path.splitext(filename)
        return f"{base}_{int(time.time() * 1000)}{ext}"

    def list_memos(self, limit: int = 100, offset: int = 0):
        """多维排序并列出备忘录"""
        conn = self.get_conn()
        cursor = conn.cursor()
        cursor.execute("""
            SELECT id, content, tags, resources, created_at, updated_at, is_pinned, is_archived, title, status
            FROM memos
            ORDER BY is_pinned DESC, created_at DESC
            LIMIT ? OFFSET ?
        """, (limit, offset))
        rows = cursor.fetchall()
        conn.close()
        return [self._row_to_dict(row) for row in rows]

    def search_memos(self, query: str):
        """对内容、标签和标题进行模糊检索并排序"""
        conn = self.get_conn()
        cursor = conn.cursor()
        q = f"%{query}%"
        cursor.execute("""
            SELECT id, content, tags, resources, created_at, updated_at, is_pinned, is_archived, title, status
            FROM memos
            WHERE content LIKE ? OR tags LIKE ? OR title LIKE ?
            ORDER BY is_pinned DESC, created_at DESC
        """, (q, q, q))
        rows = cursor.fetchall()
        conn.close()
        return [self._row_to_dict(row) for row in rows]

    def delete_memo(self, memo_id: int):
        """删除对应 id 的备忘录"""
        conn = self.get_conn()
        cursor = conn.cursor()
        cursor.execute("DELETE FROM memos WHERE id = ?", (memo_id,))
        conn.commit()
        conn.close()
        return "success"

    def update_memo(self, memo_id: int, **fields):
        """深度原地修改/更新备忘录"""
        conn = self.get_conn()
        cursor = conn.cursor()

        update_parts = []
        params = []

        # 动态处理各更新属性
        if "content" in fields and fields["content"] is not None:
            update_parts.append("content = ?")
            params.append(fields["content"])

        if "tags" in fields and fields["tags"] is not None:
            update_parts.append("tags = ?")
            params.append(json.dumps(fields["tags"]))

        if "is_pinned" in fields and fields["is_pinned"] is not None:
            update_parts.append("is_pinned = ?")
            params.append(int(fields["is_pinned"]))

        if "is_archived" in fields and fields["is_archived"] is not None:
            update_parts.append("is_archived = ?")
            params.append(int(fields["is_archived"]))

        if "title" in fields and fields["title"] is not None:
            update_parts.append("title = ?")
            params.append(fields["title"])

        if "status" in fields and fields["status"] is not None:
            update_parts.append("status = ?")
            params.append(fields["status"])

        # 多媒体附件异步追加上传
        base64_files = fields.get("base64_files")
        if base64_files:
            cursor.execute("SELECT resources FROM memos WHERE id = ?", (memo_id,))
            row = cursor.fetchone()
            resources = []
            if row and row["resources"]:
                try:
                    resources = json.loads(row["resources"])
                except Exception:
                    pass
            for f in base64_files:
                name = f.get("name")
                data = f.get("data")
                if name and data:
                    if "," in data:
                        data = data.split(",")[1]
                    target_name = self._get_safe_filename(name)
                    target_path = os.path.join(self.attachments_dir, target_name)
                    with open(target_path, "wb") as fb:
                        fb.write(base64.b64decode(data))
                    resources.append(f"data/memos/attachments/{target_name}")
            update_parts.append("resources = ?")
            params.append(json.dumps(resources))

        if not update_parts:
            conn.close()
            return {"success": True}

        update_parts.append("updated_at = ?")
        params.append(int(time.time()))

        sql = f"UPDATE memos SET {', '.join(update_parts)} WHERE id = ?"
        params.append(memo_id)

        cursor.execute(sql, params)
        conn.commit()
        conn.close()
        return {"success": True}

    def ai_tag_predict(self, content: str, jarvis_app=None) -> List[str]:
        """使用大语言模型智能预测备忘录标签分类"""
        if jarvis_app and hasattr(jarvis_app, "nlu_service"):
            try:
                prompt = f"Please analyze the following memo content and suggest 1 to 3 appropriate, short category tags starting with '#' (e.g., #Work, #Life, #Idea, #Study, #Shopping). Avoid duplicates. Return only the suggested tags separated by spaces, nothing else:\n\n{content}"
                res = jarvis_app.nlu_service.ask_llm(prompt, [], use_habit=False)
                words = res.split()
                tags = [w for w in words if w.startswith("#")]
                if tags:
                    return tags[:3]
            except Exception as e:
                print(f"AI Tag Predict failed: {e}")

        # 本地简易预测（无大模型连接时的优秀兜底方案）
        tags = []
        content_lower = content.lower()
        if any(w in content_lower for w in ["work", "工作", "项目", "下周", "会议"]):
            tags.append("#工作")
        if any(w in content_lower for w in ["idea", "灵感", "想", "创意", "计划"]):
            tags.append("#灵感")
        if any(w in content_lower for w in ["read", "阅读", "书", "笔记", "摘录"]):
            tags.append("#阅读")
        if any(w in content_lower for w in ["shop", "买", "购物", "清单", "超市"]):
            tags.append("#生活")
        if not tags:
            tags.append("#备忘")
        return tags

    def ai_magic_wand(self, content: str, mode: str, jarvis_app=None) -> str:
        """AI 魔棒功能，一键润色、摘要或转换为 Todo 列表"""
        if jarvis_app and hasattr(jarvis_app, "nlu_service"):
            try:
                if mode == "summary":
                    prompt = f"Please summarize the following memo text to a very concise summary (TL;DR bullet-point list). Keep it brief, in the same language as the text:\n\n{content}"
                elif mode == "polish":
                    prompt = f"Please polish, correct any typos, and beautifully format/markdown the following memo content to improve readability and style, but keep its core meaning unchanged:\n\n{content}"
                elif mode == "todo":
                    prompt = f"Please extract actionable tasks from the following text and convert them into a clean markdown checklist/todo list (using - [ ] format):\n\n{content}"
                else:
                    return "Error: Unknown AI mode"

                return jarvis_app.nlu_service.ask_llm(prompt, [], use_habit=False)
            except Exception as e:
                return f"AI 处理失败: {e}"

        # 本地规则解析兜底
        if mode == "summary":
            return f"**[智能摘要]** {content[:60]}..."
        elif mode == "polish":
            return f"✨ *已优化排版* ✨\n\n{content}"
        elif mode == "todo":
            lines = content.split('\n')
            todos = []
            for line in lines:
                line = line.strip()
                if line:
                    todos.append(f"- [ ] {line}")
            return "\n".join(todos)
        return "Error: Unknown AI mode"

    def stop(self):
        """停止后端进程"""
        if self.client:
            try:
                self.client.stop()
            except Exception:
                pass
            self.client = None
            self._initialized = False
            MemosSkill._instance = None

def handle_request(action: str, **kwargs):
    """
    Skill 入口点
    """
    memos = MemosSkill()
    jarvis_app = kwargs.get("jarvis_app")

    if action == "add":
        content = kwargs.get("content", "")
        tags = kwargs.get("tags", [])
        title = kwargs.get("title", "")
        status = kwargs.get("status", "进行中")
        files = kwargs.get("files", [])
        base64_files = kwargs.get("base64_files", [])

        if not content and not tags and not files and not base64_files:
             return "错误：备忘录内容不能为空。"

        result = memos.add_memo(content, tags, title, status, files, base64_files)

        if jarvis_app and result and "id" in result:
             jarvis_app.ui_print(content, tag="memo_card", response_id=result.get("id"))

        return result

    elif action == "list":
        return memos.list_memos(kwargs.get("limit", 100), kwargs.get("offset", 0))

    elif action == "search":
        return memos.search_memos(kwargs.get("query", ""))

    elif action == "delete":
        memo_id = kwargs.get("id")
        return memos.delete_memo(int(memo_id)) if memo_id else "错误：未指定 ID。"

    elif action == "update":
        memo_id = kwargs.get("id")
        if not memo_id:
            return "错误：未指定 ID。"

        # 获取所有可能的更新字段并过滤出来传递
        fields = {}
        for key in ["content", "tags", "is_pinned", "is_archived", "title", "status", "base64_files"]:
            if key in kwargs:
                fields[key] = kwargs[key]

        return memos.update_memo(int(memo_id), **fields)

    elif action == "ai_tag_predict":
        content = kwargs.get("content", "")
        return memos.ai_tag_predict(content, jarvis_app)

    elif action == "ai_magic_wand":
        content = kwargs.get("content", "")
        mode = kwargs.get("mode", "summary")
        return memos.ai_magic_wand(content, mode, jarvis_app)

    return "未知操作。"
