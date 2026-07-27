"""
Bridge Server - Protocol Adaptation Layer
Bridges frontend REST/WebSocket requests to backend call_plugin() JNI interface.

Starts an HTTP+WebSocket server on localhost:8080 in a background thread.
Routes:
  REST:   GET/PUT /api/settings, GET /api/skills, GET /api/memos, POST /api/memos,
          GET /api/tasks, POST /api/tasks, DELETE /api/tasks/<id>,
          GET /api/vault, POST /api/vault, GET /api/focus, POST /api/focus,
          GET /api/cron, POST /api/cron, DELETE /api/cron/<id>,
          GET /api/profile, GET /api/system
  WebSocket /ws: message types: chat, skill:run, terminal, task:run, workflow:create,
                 workflow:execute, time_machine:snapshot, time_machine:range,
                 vault:get, vault:put, vault:delete, focus:start, focus:stop,
                 voice:start, voice:stop, code:run, cron:add, cron:remove,
                 cluster:nodes, cluster:health, profile:get, profile:update,
                 system:stats, system:mode
"""

import os
import sys
import json
import logging
import threading
import time
import traceback
import secrets
from http.server import HTTPServer, BaseHTTPRequestHandler
from socketserver import ThreadingMixIn
from urllib.parse import urlparse, parse_qs

logger = logging.getLogger("BridgeServer")

# ── Security constants ────────────────────────────────────────────
MAX_BODY_SIZE = 1 * 1024 * 1024  # 1MB request body limit
ALLOWED_ORIGINS = {
    'capacitor://localhost',
    'http://localhost',
    'http://127.0.0.1',
    'http://localhost:5173',    # Vite dev server
    'http://127.0.0.1:5173',
}
# One-time auth token shared across REST and WebSocket
_BRIDGE_AUTH_TOKEN = secrets.token_hex(32)

# ── WebSocket support ──────────────────────────────────────────────
try:
    import websockets
    HAS_WEBSOCKETS = True
except ImportError:
    HAS_WEBSOCKETS = False
    logger.warning("websockets not installed; WebSocket endpoint disabled")

# ── Import call_plugin from parent module ────────────────────────
_call_plugin = None
_skill_manager_ref = None


def set_call_plugin(fn):
    """Inject the call_plugin function from butler_android."""
    global _call_plugin
    _call_plugin = fn


def set_skill_manager(sm):
    """Inject the SkillManager instance for direct core access."""
    global _skill_manager_ref
    _skill_manager_ref = sm


def _plugin(skill_id, action, **params):
    """Unified call to call_plugin with JSON roundtrip."""
    if _call_plugin is None:
        return {"status": "error", "error_type": "BridgeError", "message": "call_plugin not injected"}
    raw = _call_plugin(skill_id, action, json.dumps(params))
    try:
        return json.loads(raw)
    except (json.JSONDecodeError, TypeError):
        return {"status": "error", "error_type": "ParseError", "raw": raw}


def _core_method(module_name, method_name, *args, **kwargs):
    """Call a method on a core module directly via skill_manager reference."""
    if _skill_manager_ref is None:
        return {"status": "error", "message": "SkillManager not available"}
    try:
        core = getattr(_skill_manager_ref, module_name, None)
        if core is None:
            # Try importing from butler.core
            import importlib
            mod = importlib.import_module(f"butler.core.{module_name}")
            # Look for singleton or class
            for attr_name in dir(mod):
                attr = getattr(mod, attr_name)
                if hasattr(attr, method_name) and callable(getattr(attr, method_name)):
                    return getattr(attr, method_name)(*args, **kwargs)
            return {"status": "error", "message": f"Method {method_name} not found in {module_name}"}
        method = getattr(core, method_name, None)
        if method is None:
            return {"status": "error", "message": f"Method {method_name} not found"}
        result = method(*args, **kwargs)
        if hasattr(result, '__dict__'):
            return result.__dict__
        return result
    except Exception as e:
        return {"status": "error", "error_type": type(e).__name__, "message": str(e)}


# ── REST Response Helpers ─────────────────────────────────────────

def _get_cors_origin(handler):
    """Return the appropriate CORS origin or None."""
    origin = handler.headers.get('Origin', '')
    # Allow capacitor://localhost (native WebView) and localhost
    if origin in ALLOWED_ORIGINS:
        return origin
    # Also allow requests with no Origin header (native WebView may not send it)
    if not origin:
        return None
    return None


def _json_response(handler, data, status=200):
    handler.send_response(status)
    handler.send_header('Content-Type', 'application/json; charset=utf-8')
    origin = _get_cors_origin(handler)
    if origin:
        handler.send_header('Access-Control-Allow-Origin', origin)
        handler.send_header('Vary', 'Origin')
    handler.send_header('Access-Control-Allow-Methods', 'GET, POST, PUT, DELETE, OPTIONS')
    handler.send_header('Access-Control-Allow-Headers', 'Content-Type, Authorization')
    handler.end_headers()
    handler.wfile.write(json.dumps(data, ensure_ascii=False, default=str).encode('utf-8'))


def _read_body(handler):
    length = min(int(handler.headers.get('Content-Length', 0)), MAX_BODY_SIZE)
    if length == 0:
        return {}
    raw = handler.rfile.read(length)
    return json.loads(raw.decode('utf-8'))


# ── REST Route Handlers ──────────────────────────────────────────

def handle_get_settings(handler):
    result = _plugin("config_manager", "get")
    if result.get("status") == "success":
        _json_response(handler, result.get("data", {}))
    else:
        # Fallback: try core config_manager directly
        _json_response(handler, _core_method("config_manager", "get_all", {}))


def handle_put_settings(handler):
    body = _read_body(handler)
    result = _plugin("config_manager", "set", **body)
    if result.get("status") == "success":
        _json_response(handler, {"ok": True})
    else:
        _json_response(handler, _core_method("config_manager", "set", body), 500)


def handle_get_skills(handler):
    result = _plugin("manage_skills", "list")
    if result.get("status") == "success":
        skills_list = result.get("data", [])
        if isinstance(skills_list, list):
            _json_response(handler, [
                {
                    "id": s.get("id", s.get("skill_id", "")),
                    "name": s.get("name", s.get("display_name", s.get("skill_id", ""))),
                    "icon": _skill_icon(s.get("skill_id", "")),
                    "color": _skill_color(s.get("skill_id", "")),
                    "desc": s.get("description", ""),
                    "version": s.get("version", ""),
                    "status": s.get("status", "loaded"),
                }
                for s in skills_list
            ])
        else:
            _json_response(handler, skills_list)
    else:
        _json_response(handler, [])


def handle_get_memos(handler):
    qs = parse_qs(urlparse(handler.path).query)
    limit = int(qs.get("limit", ["20"])[0])
    offset = int(qs.get("offset", ["0"])[0])
    result = _plugin("memos", "list", limit=limit, offset=offset)
    if result.get("status") == "success":
        _json_response(handler, result.get("data", []))
    else:
        _json_response(handler, [])


def handle_post_memo(handler):
    body = _read_body(handler)
    result = _plugin("memos", "add", **body)
    if result.get("status") == "success":
        _json_response(handler, {"ok": True, "id": result.get("data", {}).get("id", "")})
    else:
        _json_response(handler, {"ok": False, "error": result.get("message", "")}, 500)


def handle_get_tasks(handler):
    result = _plugin("task_management", "list")
    if result.get("status") == "success":
        _json_response(handler, result.get("data", []))
    else:
        _json_response(handler, _core_method("task_manager", "list_tasks"))


def handle_post_task(handler):
    body = _read_body(handler)
    result = _plugin("task_management", "add", **body)
    if result.get("status") == "success":
        _json_response(handler, {"ok": True})
    else:
        _json_response(handler, {"ok": False}, 500)


def handle_delete_task(handler, task_id):
    result = _plugin("task_management", "delete", id=task_id)
    _json_response(handler, {"ok": result.get("status") == "success"})


def handle_get_vault(handler):
    result = _plugin("secret_vault", "list")
    if result.get("status") == "success":
        _json_response(handler, result.get("data", []))
    else:
        _json_response(handler, _core_method("secret_vault", "list_secrets"))


def handle_post_vault(handler):
    body = _read_body(handler)
    result = _plugin("secret_vault", "put", **body)
    _json_response(handler, {"ok": result.get("status") == "success"})


def handle_get_focus(handler):
    result = _core_method("focus_mode", "get_status")
    _json_response(handler, result)


def handle_post_focus(handler):
    body = _read_body(handler)
    action = body.get("action", "start")
    result = _core_method("focus_mode", action, **body)
    _json_response(handler, result)


def handle_get_cron(handler):
    result = _core_method("cron_scheduler", "list_jobs")
    _json_response(handler, result if result else [])


def handle_post_cron(handler):
    body = _read_body(handler)
    result = _core_method("cron_scheduler", "add_job", **body)
    _json_response(handler, {"ok": True, "id": result.get("id", "")} if isinstance(result, dict) else {"ok": True})


def handle_delete_cron(handler, job_id):
    result = _core_method("cron_scheduler", "remove_job", job_id)
    _json_response(handler, {"ok": result is not False if result else True})


def handle_get_profile(handler):
    result = _core_method("habit_manager", "get_profile")
    _json_response(handler, result if result else {})


def handle_get_system(handler):
    """Return system status overview."""
    import platform
    info = {
        "platform": platform.system(),
        "python_version": platform.python_version(),
        "bridge": "running",
    }
    # Battery
    bat = _core_method("battery_manager", "get_status")
    if bat:
        info["battery"] = bat
    # Cluster
    cluster = _core_method("cluster_manager", "get_nodes")
    if cluster:
        info["cluster_nodes"] = cluster
    _json_response(handler, info)


def handle_get_auth_token(handler):
    """Return the one-time bridge auth token for WebSocket connection."""
    _json_response(handler, {"token": _BRIDGE_AUTH_TOKEN})


# ── Skill Manager Import ──────────────────────────────────────────

_skill_manager = None

def _get_skill_manager():
    """Lazy-import and return the SkillManager singleton."""
    global _skill_manager
    if _skill_manager is None:
        try:
            from package.core_utils.skill_manager import get_skill_manager
            _skill_manager = get_skill_manager()
        except Exception as e:
            logger.warning(f"SkillManager not available: {e}")
            _skill_manager = None
    return _skill_manager


# ── Marketplace API Handlers ──────────────────────────────────────

def handle_get_marketplace(handler):
    """GET /api/skills/marketplace — 获取技能市场列表"""
    sm = _get_skill_manager()
    if sm is None:
        _json_response(handler, [])
        return
    _json_response(handler, sm.get_marketplace_skills())


def handle_install_skill(handler):
    """POST /api/skills/install — 从 URL 安装技能"""
    body = _read_body(handler)
    url = body.get("url", "")
    if not url:
        _json_response(handler, {"ok": False, "error": "url is required"}, 400)
        return
    sm = _get_skill_manager()
    if sm is None:
        _json_response(handler, {"ok": False, "error": "SkillManager not available"}, 500)
        return
    result = sm.install_from_url(url)
    _json_response(handler, result, 200 if result.get("ok") else 400)


def handle_install_skill_file(handler):
    """POST /api/skills/install/file — 从本地文件安装技能"""
    body = _read_body(handler)
    file_path = body.get("path", "")
    if not file_path:
        _json_response(handler, {"ok": False, "error": "path is required"}, 400)
        return
    sm = _get_skill_manager()
    if sm is None:
        _json_response(handler, {"ok": False, "error": "SkillManager not available"}, 500)
        return
    result = sm.install_from_file(file_path)
    _json_response(handler, result, 200 if result.get("ok") else 400)


def handle_delete_skill(handler, skill_id):
    """DELETE /api/skills/<id> — 卸载技能"""
    sm = _get_skill_manager()
    if sm is None:
        _json_response(handler, {"ok": False, "error": "SkillManager not available"}, 500)
        return
    result = sm.uninstall_skill(skill_id)
    _json_response(handler, result, 200 if result.get("ok") else 400)


def handle_scan_skills(handler):
    """POST /api/skills/scan — 扫描自定义目录"""
    body = _read_body(handler)
    dir_path = body.get("path", None)
    sm = _get_skill_manager()
    if sm is None:
        _json_response(handler, {"ok": False, "error": "SkillManager not available"}, 500)
        return
    result = sm.scan_custom_dir(dir_path)
    _json_response(handler, result)


# ── Skills CLI (inline terminal command) ───────────────────────────

def handle_skills_cli(args: str) -> str:
    """处理 skills 终端命令, 返回格式化输出字符串。
    
    支持: skills add <url|source/name|id> | skills remove <id>
          skills list | skills search <query> | skills market | skills help
    """
    sm = _get_skill_manager()
    if sm is None:
        return "❌ SkillManager 不可用"

    if not args or args == "help":
        return _skills_cli_help()

    parts = args.split()
    sub = parts[0].lower()
    rest = " ".join(parts[1:])

    if sub in ("add", "install"):
        return _skills_cli_add(sm, rest)
    elif sub in ("remove", "rm", "uninstall"):
        return _skills_cli_remove(sm, rest)
    elif sub in ("list", "ls"):
        return _skills_cli_list(sm)
    elif sub in ("search", "find"):
        return _skills_cli_search(sm, rest)
    elif sub in ("market", "store"):
        return _skills_cli_market(sm)
    elif sub in ("help", "--help", "-h"):
        return _skills_cli_help()
    else:
        return f"未知子命令: {sub}\n输入 skills help 查看可用命令"


def _skills_cli_help() -> str:
    return "\n".join([
        "╔══════════════════════════════════════════════╗",
        "║       Butler Skills CLI — 技能管理命令      ║",
        "╠══════════════════════════════════════════════╣",
        "║  skills add <url>                           ║",
        "║    从 URL 直接下载安装技能包                ║",
        "║  skills add <source>/<name>                 ║",
        "║    从市场安装技能 (如: skills add butler/   ║",
        "║    audio_denoiser)                          ║",
        "║  skills add <id>                            ║",
        "║    按 ID 从市场搜索安装                     ║",
        "║  skills remove <id>                         ║",
        "║    卸载已安装的技能                         ║",
        "║  skills list                                ║",
        "║    列出所有已安装的技能                     ║",
        "║  skills search <query>                      ║",
        "║    搜索技能市场                             ║",
        "║  skills market                              ║",
        "║    浏览技能市场全部技能                     ║",
        "║  skills help                                ║",
        "║    显示此帮助信息                           ║",
        "╚══════════════════════════════════════════════╝",
    ])


def _skills_cli_add(sm, target: str) -> str:
    if not target:
        return "用法: skills add <url | source/name | id>\n示例: skills add butler/audio_denoiser\n示例: skills add https://example.com/skill.bsk"

    # Direct URL
    if target.startswith("http://") or target.startswith("https://"):
        result = sm.install_from_url(target)
        if result.get("ok"):
            skill = result.get("skill", {})
            return f"✅ 安装成功!\n   技能: {skill.get('name', '')} ({skill.get('id', '')})"
        return f"❌ 安装失败: {result.get('error', '未知错误')}"

    # Search marketplace
    marketplace = sm.get_marketplace_skills()
    match = None

    if "/" in target:
        source, name = target.split("/", 1)
        source_lower = source.lower()
        name_lower = name.lower()
        for s in marketplace:
            author_lower = s.get("author", "").lower().replace(" ", "").replace("team", "")
            sid_lower = s.get("id", "").lower()
            sname_lower = s.get("name", "").lower()
            if (author_lower.find(source_lower) >= 0 or sid_lower.find(source_lower) >= 0) \
               and (sid_lower.find(name_lower) >= 0 or sname_lower.find(name_lower) >= 0):
                match = s
                break
    else:
        target_lower = target.lower()
        for s in marketplace:
            if s.get("id", "").lower() == target_lower:
                match = s
                break
        if not match:
            for s in marketplace:
                sid = s.get("id", "").lower()
                sname = s.get("name", "").lower()
                if target_lower in sid or target_lower in sname:
                    match = s
                    break

    if not match:
        return f'❌ 未找到匹配的技能: "{target}"\n💡 输入 skills market 浏览可用技能'

    if match.get("installed"):
        return f'⚠️ 技能 "{match["name"]}" ({match["id"]}) 已安装 (v{match.get("installed_version", "")})'

    result = sm.install_from_url(match["download_url"])
    if result.get("ok"):
        return f'✅ "{match["name"]}" 安装成功!'
    return f'❌ 安装失败: {result.get("error", "未知错误")}'


def _skills_cli_remove(sm, skill_id: str) -> str:
    if not skill_id:
        return "用法: skills remove <id>\n示例: skills remove audio_denoiser"
    result = sm.uninstall_skill(skill_id)
    if result.get("ok"):
        return f'✅ 技能 "{skill_id}" 已卸载'
    return f'❌ 卸载失败: {result.get("error", "未知错误")}'


def _skills_cli_list(sm) -> str:
    skills = sm.list_all_skills()
    if not skills:
        return "(暂无已安装的技能)\n💡 输入 skills market 浏览可安装的技能"
    lines = [f"── 已安装技能 ({len(skills)}) ──"]
    for s in skills:
        status_icon = "●" if s.get("status") == "running" else "○"
        lines.append(
            f"  {status_icon} {s.get('id', ''):<24} {s.get('name', ''):<14} "
            f"v{s.get('version', '1.0.0'):<8} {s.get('status', 'idle')}"
        )
    return "\n".join(lines)


def _skills_cli_search(sm, query: str) -> str:
    if not query:
        return "用法: skills search <关键词>"
    marketplace = sm.get_marketplace_skills()
    q = query.lower()
    results = [
        s for s in marketplace
        if q in s.get("id", "").lower()
        or q in s.get("name", "").lower()
        or q in s.get("description", "").lower()
        or any(q in t.lower() for t in s.get("tags", []))
        or q in s.get("author", "").lower()
    ]
    if not results:
        return f'未找到匹配 "{query}" 的技能'
    lines = [f"── 搜索结果 ({len(results)}) ──"]
    for s in results:
        status = "✓ 已安装" if s.get("installed") else "+ 可安装"
        lines.append(
            f"  {s.get('id', ''):<24} {s.get('name', ''):<14} "
            f"v{s.get('version', ''):<8} {s.get('author', ''):<16} {status}"
        )
    lines.append("💡 输入 skills add <id> 安装技能")
    return "\n".join(lines)


def _skills_cli_market(sm) -> str:
    marketplace = sm.get_marketplace_skills()
    if not marketplace:
        return "(市场暂无可用的技能)"
    lines = [f"── 技能市场 ({len(marketplace)}) ──"]
    for s in marketplace:
        stars = "★" * int(s.get("rating", 0)) + "☆" * (5 - int(s.get("rating", 0)))
        status = "✓ 已安装" if s.get("installed") else "+ 可安装"
        lines.append(
            f"  {s.get('id', ''):<24} {stars} {s.get('rating', 0)}"
        )
        lines.append(
            f"    {s.get('name', ''):<14} v{s.get('version', ''):<8} "
            f"{s.get('author', ''):<16} {s.get('size', ''):<8} {status}"
        )
    lines.append("💡 输入 skills add <id> 安装技能")
    return "\n".join(lines)


# ── Skill icon/color mapping ─────────────────────────────────────

_SKILL_ICONS = {
    "sys_cleaner": "fa-broom",
    "memos": "fa-sticky-note",
    "translator": "fa-language",
    "pdf": "fa-file-pdf",
    "docx": "fa-file-word",
    "xlsx_recalc": "fa-file-excel",
    "markitdown": "fa-file-code",
    "archive_manager": "fa-file-zipper",
    "media_manager": "fa-photo-film",
    "music_player": "fa-music",
    "task_management": "fa-list-check",
    "frontend_design": "fa-palette",
    "doc_coauthoring": "fa-pen-nib",
    "skill_creator": "fa-wand-magic-sparkles",
    "xiaomi": "fa-mobile-screen",
    "hello_cli": "fa-terminal",
    "sys_cleaner": "fa-shield-halved",
    "hot_swap_test": "fa-flask",
    "skill_audio_core": "fa-headphones",
    "skill_clip_magic": "fa-scissors",
    "skill_local_sync": "fa-arrows-rotate",
    "skill_media_sniffer": "fa-radar",
    "skill_screenshot_debugger": "fa-camera",
    "skill_sec_radar": "fa-tower-broadcast",
    "butler_expert": "fa-robot",
    "daily_fashion_stylist": "fa-shirt",
    "karpathy_guidelines": "fa-book",
    "adobe-suite-exper": "fa-image",
    "photoshop-expert": "fa-bezier-curve",
    "office-suite-expert": "fa-file-lines",
    "wps-office-expert": "fa-file-powerpoint",
}

_SKILL_COLORS = [
    "#007AFF", "#FF9500", "#34C759", "#AF52DE", "#FF3B30",
    "#FFCC00", "#5AC8FA", "#FF2D55", "#64D2FF", "#30D158",
]


def _skill_icon(skill_id):
    return _SKILL_ICONS.get(skill_id, "fa-puzzle-piece")


def _skill_color(skill_id):
    idx = hash(skill_id) % len(_SKILL_COLORS)
    return _SKILL_COLORS[idx]


# ── HTTP Handler ──────────────────────────────────────────────────

class BridgeHTTPHandler(BaseHTTPRequestHandler):

    def do_OPTIONS(self):
        self.send_response(204)
        origin = _get_cors_origin(self)
        if origin:
            self.send_header('Access-Control-Allow-Origin', origin)
            self.send_header('Vary', 'Origin')
        self.send_header('Access-Control-Allow-Methods', 'GET, POST, PUT, DELETE, OPTIONS')
        self.send_header('Access-Control-Allow-Headers', 'Content-Type, Authorization')
        self.end_headers()

    def do_GET(self):
        path = urlparse(self.path).path.rstrip('/')
        routes = {
            '/api/settings': handle_get_settings,
            '/api/skills': handle_get_skills,
            '/api/skills/marketplace': handle_get_marketplace,
            '/api/memos': handle_get_memos,
            '/api/tasks': handle_get_tasks,
            '/api/vault': handle_get_vault,
            '/api/focus': handle_get_focus,
            '/api/cron': handle_get_cron,
            '/api/profile': handle_get_profile,
            '/api/system': handle_get_system,
            '/api/auth/token': handle_get_auth_token,
        }
        handler = routes.get(path)
        if handler:
            handler(self)
        else:
            _json_response(self, {"error": "Not Found"}, 404)

    def do_PUT(self):
        path = urlparse(self.path).path.rstrip('/')
        if path == '/api/settings':
            handle_put_settings(self)
        else:
            _json_response(self, {"error": "Not Found"}, 404)

    def do_POST(self):
        path = urlparse(self.path).path.rstrip('/')
        routes = {
            '/api/memos': handle_post_memo,
            '/api/tasks': handle_post_task,
            '/api/vault': handle_post_vault,
            '/api/focus': handle_post_focus,
            '/api/cron': handle_post_cron,
            '/api/skills/install': handle_install_skill,
            '/api/skills/install/file': handle_install_skill_file,
            '/api/skills/scan': handle_scan_skills,
        }
        handler = routes.get(path)
        if handler:
            handler(self)
        else:
            _json_response(self, {"error": "Not Found"}, 404)

    def do_DELETE(self):
        path = urlparse(self.path).path.rstrip('/')
        # /api/tasks/<id> or /api/cron/<id> or /api/skills/<id>
        parts = [p for p in path.split('/') if p]
        if len(parts) == 4 and parts[2] == 'tasks':
            handle_delete_task(self, parts[3])
        elif len(parts) == 4 and parts[2] == 'cron':
            handle_delete_cron(self, parts[3])
        elif len(parts) == 4 and parts[2] == 'skills':
            handle_delete_skill(self, parts[3])
        else:
            _json_response(self, {"error": "Not Found"}, 404)

    def log_message(self, fmt, *args):
        logger.info(f"[HTTP] {args[0] if args else fmt}")


class ThreadedHTTPServer(ThreadingMixIn, HTTPServer):
    daemon_threads = True
    allow_reuse_address = True


# ── WebSocket Handler ─────────────────────────────────────────────

async def _ws_handler(websocket, path):
    """Handle WebSocket messages from the frontend."""
    # Validate auth token from query string
    qs = parse_qs(urlparse(path).query)
    token = qs.get('token', [None])[0]
    if token != _BRIDGE_AUTH_TOKEN:
        logger.warning(f"WebSocket connection rejected: invalid token")
        await websocket.close(4001, "Unauthorized")
        return

    async for raw in websocket:
        try:
            msg = json.loads(raw)
            msg_type = msg.get("type", "")

            if msg_type == "chat":
                # Route to NLU → intent_dispatcher
                result = _plugin("nlu", "process", message=msg.get("message", ""))
                if result.get("status") == "error":
                    # Fallback: direct skill_manager execute with "chat" skill
                    result = _plugin("local_nlu", "process", text=msg.get("message", ""))
                await websocket.send(json.dumps({
                    "type": "chat:response",
                    "data": result.get("data", result),
                    "status": result.get("status", "success"),
                }))

            elif msg_type == "skill:run":
                skill_id = msg.get("skillId", "")
                params = msg.get("params", {})
                result = _plugin(skill_id, "run", **params)
                await websocket.send(json.dumps({
                    "type": "skill:result",
                    "skillId": skill_id,
                    "data": result.get("data", result),
                    "status": result.get("status", "success"),
                }))

            elif msg_type == "terminal":
                cmd = msg.get("command", "")
                lower_cmd = cmd.lower().strip()
                # Intercept skills CLI commands
                if lower_cmd == "skills" or lower_cmd.startswith("skills "):
                    args = cmd[len("skills"):].strip()
                    output = handle_skills_cli(args)
                    await websocket.send(json.dumps({
                        "type": "terminal:output",
                        "output": output,
                        "status": "success",
                    }))
                else:
                    result = _plugin("hello_cli", "run", command=cmd)
                    output = result.get("data", result) if isinstance(result, dict) else result
                    await websocket.send(json.dumps({
                        "type": "terminal:output",
                        "output": str(output) if not isinstance(output, str) else output,
                        "status": result.get("status", "success") if isinstance(result, dict) else "success",
                    }))

            elif msg_type == "workflow:create":
                result = _core_method("workflow_engine", "create_workflow",
                                      msg.get("name", ""), msg.get("steps", []))
                await websocket.send(json.dumps({
                    "type": "workflow:created",
                    "data": result,
                }))

            elif msg_type == "workflow:execute":
                wf_id = msg.get("workflowId", "")
                result = _core_method("workflow_engine", "execute_workflow", wf_id)
                await websocket.send(json.dumps({
                    "type": "workflow:status",
                    "workflowId": wf_id,
                    "data": result,
                }))

            elif msg_type == "time_machine:snapshot":
                ts = msg.get("timestamp")
                result = _core_method("time_machine", "get_snapshot_at", ts)
                await websocket.send(json.dumps({
                    "type": "tm:snapshot",
                    "data": result,
                }))

            elif msg_type == "time_machine:range":
                start = msg.get("start")
                end = msg.get("end")
                result = _core_method("time_machine", "get_range", start, end)
                await websocket.send(json.dumps({
                    "type": "tm:range",
                    "data": result,
                }))

            elif msg_type == "vault:get":
                key = msg.get("key", "")
                result = _plugin("secret_vault", "get", key=key)
                await websocket.send(json.dumps({
                    "type": "vault:data",
                    "data": result,
                }))

            elif msg_type == "vault:put":
                result = _plugin("secret_vault", "put", **msg.get("params", {}))
                await websocket.send(json.dumps({
                    "type": "vault:saved",
                    "status": result.get("status", "success"),
                }))

            elif msg_type == "vault:delete":
                key = msg.get("key", "")
                result = _plugin("secret_vault", "delete", key=key)
                await websocket.send(json.dumps({
                    "type": "vault:deleted",
                    "status": result.get("status", "success"),
                }))

            elif msg_type == "focus:start":
                duration = msg.get("duration", 25)
                result = _core_method("focus_mode", "start", duration=duration)
                await websocket.send(json.dumps({
                    "type": "focus:status",
                    "data": result,
                }))

            elif msg_type == "focus:stop":
                result = _core_method("focus_mode", "stop")
                await websocket.send(json.dumps({
                    "type": "focus:status",
                    "data": result,
                }))

            elif msg_type == "voice:start":
                result = _core_method("voice_service", "start_listening")
                await websocket.send(json.dumps({
                    "type": "voice:status",
                    "data": result,
                }))

            elif msg_type == "voice:stop":
                result = _core_method("voice_service", "stop_listening")
                await websocket.send(json.dumps({
                    "type": "voice:status",
                    "data": result,
                }))

            elif msg_type == "code:run":
                code = msg.get("code", "")
                lang = msg.get("language", "python")
                result = _plugin("hello_cli", "run", command=f"interpreter:{lang}:{code}")
                await websocket.send(json.dumps({
                    "type": "code:output",
                    "data": result,
                }))

            elif msg_type == "cron:add":
                result = _core_method("cron_scheduler", "add_job", **msg.get("params", {}))
                await websocket.send(json.dumps({
                    "type": "cron:added",
                    "data": result,
                }))

            elif msg_type == "cron:remove":
                job_id = msg.get("jobId", "")
                result = _core_method("cron_scheduler", "remove_job", job_id)
                await websocket.send(json.dumps({
                    "type": "cron:removed",
                    "data": result,
                }))

            elif msg_type == "cluster:nodes":
                result = _core_method("cluster_manager", "get_nodes")
                await websocket.send(json.dumps({
                    "type": "cluster:nodes",
                    "data": result,
                }))

            elif msg_type == "cluster:health":
                result = _core_method("cluster_manager", "health_check")
                await websocket.send(json.dumps({
                    "type": "cluster:health",
                    "data": result,
                }))

            elif msg_type == "profile:get":
                result = _core_method("habit_manager", "get_profile")
                await websocket.send(json.dumps({
                    "type": "profile:data",
                    "data": result,
                }))

            elif msg_type == "profile:update":
                result = _core_method("habit_manager", "update_habits", **msg.get("params", {}))
                await websocket.send(json.dumps({
                    "type": "profile:updated",
                    "data": result,
                }))

            elif msg_type == "system:stats":
                import platform
                stats = {
                    "platform": platform.system(),
                    "python": platform.python_version(),
                    "battery": _core_method("battery_manager", "get_status"),
                    "uptime": time.time(),
                }
                await websocket.send(json.dumps({
                    "type": "system:stats",
                    "data": stats,
                }))

            elif msg_type == "system:mode":
                mode = msg.get("mode", "normal")
                result = _core_method("algorithms", "set_mode", mode)
                await websocket.send(json.dumps({
                    "type": "system:mode_changed",
                    "data": result,
                }))

            # ── Aggregated Panel: Converter ──────────────────────────
            elif msg_type == "convert":
                file_path = msg.get("file", "")
                target = msg.get("target", "md")
                ext = os.path.splitext(file_path)[1].lower() if file_path else ""
                if ext in ('.pdf', '.docx', '.xlsx', '.pptx', '.html', '.rtf', '.epub', '.csv'):
                    result = _plugin("markitdown", "convert", file_path=file_path)
                elif ext == '.docx' and target in ('read', 'edit'):
                    result = _plugin("docx", target, file_path=file_path)
                elif ext == '.zip':
                    result = _plugin("archive_manager", "list_zip_contents", zip_path=file_path)
                else:
                    result = _plugin("markitdown", "convert", file_path=file_path)
                await websocket.send(json.dumps({
                    "type": "convert:result",
                    "data": result.get("data", result),
                    "status": result.get("status", "success"),
                }))

            elif msg_type == "archive:list":
                result = _plugin("archive_manager", "list_zip_contents", zip_path=msg.get("path", ""))
                await websocket.send(json.dumps({
                    "type": "archive:contents",
                    "data": result.get("data", []),
                }))

            # ── Aggregated Panel: Media Center ─────────────────────
            elif msg_type == "media:get_library":
                result = _plugin("media_manager", "get_library")
                await websocket.send(json.dumps({
                    "type": "media:library",
                    "data": result.get("data", []),
                }))

            elif msg_type == "music:get_playlist":
                result = _plugin("music_player", "get_playlist")
                await websocket.send(json.dumps({
                    "type": "media:playlist",
                    "data": result.get("data", []),
                }))

            elif msg_type == "music:play":
                result = _plugin("music_player", "play", index=msg.get("index", 0))
                await websocket.send(json.dumps({
                    "type": "media:status",
                    "data": {"playing": True, "track": msg.get("index", 0)},
                }))

            elif msg_type == "music:pause":
                _plugin("music_player", "pause")
                await websocket.send(json.dumps({
                    "type": "media:status",
                    "data": {"playing": False},
                }))

            elif msg_type == "music:next":
                _plugin("music_player", "next")
                await websocket.send(json.dumps({"type": "media:status", "data": {"playing": True}}))

            elif msg_type == "music:prev":
                _plugin("music_player", "prev")
                await websocket.send(json.dumps({"type": "media:status", "data": {"playing": True}}))

            # ── Aggregated Panel: Memory Center ─────────────────────
            elif msg_type == "memory:search":
                result = _core_method("memory_engine", "search", msg.get("query", ""))
                await websocket.send(json.dumps({
                    "type": "memory:search",
                    "data": result if isinstance(result, list) else [],
                }))

            elif msg_type == "memory:get_due":
                memos_result = _plugin("memos", "list", limit=100)
                memos_list = memos_result.get("data", [])
                result = _core_method("review_engine", "get_due_items", memos_list)
                await websocket.send(json.dumps({
                    "type": "memory:due",
                    "data": result if isinstance(result, list) else [],
                }))

            elif msg_type == "memory:mark_reviewed":
                await websocket.send(json.dumps({
                    "type": "memory:reviewed",
                    "data": {"ok": True},
                }))

            elif msg_type == "dream:get_status":
                can_dream = _core_method("dream_engine", "should_dream")
                await websocket.send(json.dumps({
                    "type": "dream:status",
                    "data": {"can_dream": can_dream, "last_dream": "未记录"},
                }))

            # ── Aggregated Panel: Notification Center ───────────────
            elif msg_type == "notifications:get":
                result = _core_method("notifier_system", "get_all")
                await websocket.send(json.dumps({
                    "type": "notifications:list",
                    "data": result if isinstance(result, list) else [],
                }))

            elif msg_type == "notification:dismiss":
                _core_method("notifier_system", "dismiss", msg.get("id"))
                await websocket.send(json.dumps({"type": "notification:dismissed", "ok": True}))

            elif msg_type == "proactive:get":
                result = _core_method("proactive_agent", "tick")
                await websocket.send(json.dumps({
                    "type": "proactive:suggestion",
                    "data": result,
                }))

            elif msg_type == "self_healing:get":
                result = _core_method("self_healing", "get_report")
                await websocket.send(json.dumps({
                    "type": "self_healing:report",
                    "data": result or {"issues": []},
                }))

            # ── Aggregated Panel: Security Toolkit ───────────────────
            elif msg_type == "security:scan":
                result = _plugin("skill_sec_radar", "scan", target=msg.get("target", ""), ports=msg.get("ports", "1-1024"))
                await websocket.send(json.dumps({
                    "type": "security:scan_result",
                    "data": result.get("data", []),
                }))

            elif msg_type == "security:check_url":
                result = _plugin("hybrid_net", "checkURL", url=msg.get("url", ""))
                await websocket.send(json.dumps({
                    "type": "security:net_result",
                    "data": str(result.get("data", result)),
                }))

            elif msg_type == "security:ping":
                result = _plugin("hybrid_net", "ping", host=msg.get("host", ""))
                await websocket.send(json.dumps({
                    "type": "security:net_result",
                    "data": str(result.get("data", result)),
                }))

            elif msg_type == "security:sync_start":
                _core_method("config_backup_manager", "backup")
                await websocket.send(json.dumps({
                    "type": "security:sync_status",
                    "data": {"syncing": True},
                }))

            elif msg_type == "security:backup_export":
                result = _core_method("config_backup_manager", "export")
                await websocket.send(json.dumps({
                    "type": "security:backup_done",
                    "data": result,
                }))

            # ── Aggregated Panel: Extensions ──────────────────────────
            elif msg_type == "extensions:get":
                plugins = _core_method("extension_manager", "scan_all")
                await websocket.send(json.dumps({
                    "type": "extensions:list",
                    "data": plugins if isinstance(plugins, dict) else {"plugins": [], "packages": [], "programs": []},
                }))

            elif msg_type == "extensions:scan":
                _core_method("extension_manager", "scan_all")
                await websocket.send(json.dumps({"type": "extensions:scanned", "ok": True}))

            elif msg_type == "team:get":
                result = _core_method("team_manager", "get_members")
                await websocket.send(json.dumps({
                    "type": "team:status",
                    "data": result if isinstance(result, list) else [],
                }))

            elif msg_type == "runner:get":
                result = _core_method("runner_server", "get_nodes")
                await websocket.send(json.dumps({
                    "type": "runner:nodes",
                    "data": result if isinstance(result, list) else [],
                }))

            else:
                await websocket.send(json.dumps({
                    "type": "error",
                    "message": f"Unknown message type: {msg_type}",
                }))

        except json.JSONDecodeError:
            await websocket.send(json.dumps({"type": "error", "message": "Invalid JSON"}))
        except Exception as e:
            await websocket.send(json.dumps({
                "type": "error",
                "error_type": type(e).__name__,
                "message": str(e),
            }))


# ── Server Lifecycle ────────────────────────────────────────────

_http_server = None
_ws_server = None
_server_thread = None
_ws_thread = None


def start(host='127.0.0.1', port=8080):
    """Start the HTTP + WebSocket bridge server in background threads."""
    global _http_server, _ws_server, _server_thread, _ws_thread

    # Start HTTP server
    _http_server = ThreadedHTTPServer((host, port), BridgeHTTPHandler)
    _server_thread = threading.Thread(target=_http_server.serve_forever, daemon=True)
    _server_thread.start()
    logger.info(f"Bridge HTTP server started on http://{host}:{port}")

    # Start WebSocket server
    if HAS_WEBSOCKETS:
        import asyncio
        loop = asyncio.new_event_loop()

        def _run_ws():
            asyncio.set_event_loop(loop)
            start_server = websockets.serve(_ws_handler, host, port + 1, ping_interval=30)
            loop.run_until_complete(start_server)
            loop.run_forever()

        _ws_thread = threading.Thread(target=_run_ws, daemon=True)
        _ws_thread.start()
        logger.info(f"Bridge WebSocket server started on ws://{host}:{port + 1}")
        # Expose WS port for frontend
        os.environ["BUTLER_WS_PORT"] = str(port + 1)
    else:
        logger.warning("WebSocket server not started (websockets module not available)")

    os.environ["BUTLER_BRIDGE_PORT"] = str(port)


def stop():
    """Stop the bridge server."""
    global _http_server, _ws_server
    if _http_server:
        _http_server.shutdown()
        _http_server = None
    if _ws_server:
        import asyncio
        loop = asyncio.new_event_loop()
        loop.run_until_complete(_ws_server.close())
        _ws_server = None
    logger.info("Bridge server stopped")
