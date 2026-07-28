"""Skill 市场后端 FastAPI 主应用。

提供 Skill 包的上传、浏览、搜索、下载、版本管理与删除等 API 端点，
供 Android 客户端与管理端使用。

运行方式
--------
::

    uvicorn app:app --host 0.0.0.0 --port 8000 --reload

或直接运行本模块::

    python app.py

API 端点总览
------------
- ``GET  /api/health``                          健康检查
- ``GET  /api/categories``                      获取所有分类
- ``POST /api/skills/upload``                   上传 .bsk 包
- ``GET  /api/skills``                          浏览/搜索 skill 列表
- ``GET  /api/skills/{skill_id}``               获取 skill 详情
- ``GET  /api/skills/{skill_id}/versions``      获取所有版本
- ``GET  /api/skills/{skill_id}/{version}/download``  下载 .bsk
- ``DELETE /api/skills/{skill_id}/{version}``   删除指定版本（需 API Key）
"""

from __future__ import annotations

import json
import os
from typing import List, Optional

from fastapi import (
    Depends,
    FastAPI,
    File,
    Form,
    Header,
    HTTPException,
    Query,
    Request,
    UploadFile,
    status,
)
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, JSONResponse

import config
from models import (
    ErrorResponse,
    SkillDetail,
    SkillListItem,
    SkillListResponse,
    UploadResponse,
    VersionInfo,
)
from storage import SkillStorage

# ======================================================================
# 应用初始化
# ======================================================================

#: 全局存储管理器实例。
storage = SkillStorage(config.STORAGE_DIR)

app = FastAPI(
    title="Butler Skill Market API",
    description="Butler-Android Skill 市场后端，提供 .bsk 包的分发与管理。",
    version="1.0.0",
)

# CORS 中间件：允许 Android 客户端与 Web 管理端跨域访问
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.on_event("startup")
def _on_startup() -> None:
    """应用启动时确保存储目录存在。"""
    config.ensure_storage_dir()


# ======================================================================
# 认证依赖
# ======================================================================


async def require_api_key(
    request: Request,
    x_api_key: Optional[str] = Header(default=None, alias=config.API_KEY_HEADER),
) -> None:
    """验证请求是否携带有效的 API Key。

    用于保护管理类端点（如删除 skill 版本）。
    若服务端未配置 ``API_KEY``，则放行所有请求（仅限开发环境）。

    :param request: FastAPI 请求对象。
    :param x_api_key: 从请求头 ``X-API-Key`` 读取的值。
    :raises HTTPException: API Key 缺失或不匹配时抛出 401。
    """
    # 若服务端未配置 API_KEY，则跳过校验（方便开发/测试）
    if not config.API_KEY:
        return

    if not x_api_key or x_api_key != config.API_KEY:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="无效或缺失的 API Key",
        )


# ======================================================================
# 健康检查
# ======================================================================


@app.get(
    "/api/health",
    summary="健康检查",
    tags=["系统"],
)
async def health_check() -> dict:
    """返回服务健康状态与基本运行信息。

    :return: 包含 ``status``、``storage_dir``、``version`` 的字典。
    """
    return {
        "status": "ok",
        "storage_dir": str(config.STORAGE_DIR),
        "storage_exists": config.STORAGE_DIR.is_dir(),
        "version": app.version,
    }


# ======================================================================
# 分类列表
# ======================================================================


@app.get(
    "/api/categories",
    summary="获取所有 Skill 分类",
    tags=["分类"],
)
async def get_categories() -> dict:
    """返回所有已上传 skill 使用过的分类列表。

    :return: ``{"categories": ["productivity", ...]}``
    """
    categories = storage.get_categories()
    return {"categories": categories}


# ======================================================================
# 上传 Skill
# ======================================================================


@app.post(
    "/api/skills/upload",
    response_model=UploadResponse,
    summary="上传 .bsk Skill 包",
    tags=["Skill"],
    responses={
        400: {"model": ErrorResponse, "description": "包格式非法或元数据缺失"},
        413: {"model": ErrorResponse, "description": "文件超过大小限制"},
    },
)
async def upload_skill(
    metadata: str = Form(..., description="Skill 元数据 JSON 字符串"),
    file: UploadFile = File(..., description=".bsk 包文件"),
) -> UploadResponse:
    """接收 multipart 上传的 .bsk 包并保存。

    请求体为 ``multipart/form-data``，包含两个字段：
    - ``metadata``: JSON 字符串，可包含 category、arch、min_app_version 等
      市场专属字段（核心字段以包内 manifest.json 为准）。
    - ``file``: ``.bsk`` 文件（ZIP 格式，含 manifest.json）。

    :param metadata: 元数据 JSON 字符串。
    :param file: 上传的 .bsk 文件。
    :return: 上传成功响应。
    :raises HTTPException: 文件过大、格式非法或元数据解析失败时抛出。
    """
    # 1. 校验文件名后缀
    filename = file.filename or ""
    if not filename.endswith(".bsk"):
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="文件必须为 .bsk 格式",
        )

    # 2. 读取文件内容并校验大小
    file_data = await file.read()
    if len(file_data) == 0:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="上传文件为空",
        )
    if len(file_data) > config.MAX_UPLOAD_SIZE:
        raise HTTPException(
            status_code=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
            detail=(
                f"文件大小 {len(file_data)} 字节超过上限 "
                f"{config.MAX_UPLOAD_SIZE} 字节"
            ),
        )

    # 3. 解析上传的 metadata JSON
    try:
        metadata_dict = json.loads(metadata) if metadata else {}
    except json.JSONDecodeError as exc:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"metadata 不是合法的 JSON: {exc}",
        )

    # 4. 保存 skill（内部会解析 manifest.json 并校验）
    try:
        result = storage.save_skill(metadata_dict, file_data)
    except ValueError as exc:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=str(exc),
        )
    except Exception as exc:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"保存 skill 时发生内部错误: {exc}",
        )

    skill_id = result["skill_id"]
    version = result["version"]
    sha256 = result["sha256"]

    # 构造下载 URL（相对路径，客户端拼接 base url）
    download_url = f"/api/skills/{skill_id}/{version}/download"

    return UploadResponse(
        success=True,
        skill_id=skill_id,
        version=version,
        sha256=sha256,
        download_url=download_url,
    )


# ======================================================================
# Skill 列表查询
# ======================================================================


@app.get(
    "/api/skills",
    response_model=SkillListResponse,
    summary="浏览/搜索 Skill 列表",
    tags=["Skill"],
)
async def list_skills(
    arch: str = Query(
        default=config.DEFAULT_ARCH,
        description="客户端架构，用于过滤兼容的 skill",
    ),
    app_version: Optional[str] = Query(
        default=None,
        description="客户端 App 版本，用于过滤最低版本要求",
    ),
    category: Optional[str] = Query(
        default=None,
        description="按分类过滤",
    ),
    search: Optional[str] = Query(
        default=None,
        description="全文搜索（匹配 name/description/id）",
    ),
    installed_ids: Optional[str] = Query(
        default=None,
        description="客户端已安装的 skill_id 列表（逗号分隔），用于标记 is_installed",
    ),
    page: int = Query(default=1, ge=1, description="页码，从 1 开始"),
    page_size: int = Query(
        default=config.DEFAULT_PAGE_SIZE,
        ge=1,
        le=config.MAX_PAGE_SIZE,
        description="每页数量",
    ),
) -> SkillListResponse:
    """查询符合条件并兼容客户端的 skill 列表（分页）。

    支持按架构、App 版本、分类过滤与全文搜索。

    :param arch: 客户端架构。
    :param app_version: 客户端 App 版本。
    :param category: 分类过滤。
    :param search: 搜索关键词。
    :param installed_ids: 已安装 skill_id 列表（逗号分隔）。
    :param page: 页码。
    :param page_size: 每页大小。
    :return: 分页的 skill 列表响应。
    """
    # 解析已安装 id 集合
    installed_set: set = set()
    if installed_ids:
        installed_set = {
            sid.strip() for sid in installed_ids.split(",") if sid.strip()
        }

    # 获取过滤后的全部 skill
    all_skills = storage.list_skills(
        arch=arch,
        app_version=app_version,
        category=category,
        search=search,
    )

    total = len(all_skills)

    # 分页切片
    start = (page - 1) * page_size
    end = start + page_size
    page_items = all_skills[start:end]

    # 转换为 SkillListItem
    skill_list: List[SkillListItem] = []
    for item in page_items:
        metadata = item.get("metadata", {})
        skill_id = item.get("skill_id", metadata.get("id", ""))
        # is_installed 仅在客户端提供了 installed_ids 时才有意义
        is_installed = (
            skill_id in installed_set if installed_ids is not None else None
        )

        skill_list.append(
            SkillListItem(
                id=metadata.get("id", skill_id),
                name=metadata.get("name", ""),
                version=metadata.get("version", item.get("latest_version", "")),
                description=metadata.get("description", ""),
                entry=metadata.get("entry", "main.py"),
                entry_function=metadata.get("entry_function", "run"),
                author=metadata.get("author", ""),
                category=metadata.get("category", "other"),
                language=metadata.get("language", "python"),
                min_app_version=metadata.get("min_app_version", "0.0.0"),
                arch=metadata.get("arch", ["universal"]),
                skill_type=metadata.get("skill_type", "python"),
                dependencies=metadata.get("dependencies", []),
                latest_version=item.get("latest_version", ""),
                download_url=f"/api/skills/{skill_id}/{item.get('latest_version', '')}/download",
                download_count=item.get("download_count", 0),
                is_installed=is_installed,
            )
        )

    return SkillListResponse(
        skills=skill_list,
        total=total,
        page=page,
        page_size=page_size,
    )


# ======================================================================
# Skill 详情
# ======================================================================


@app.get(
    "/api/skills/{skill_id}",
    response_model=SkillDetail,
    summary="获取 Skill 详情",
    tags=["Skill"],
    responses={
        404: {"model": ErrorResponse, "description": "Skill 不存在"},
    },
)
async def get_skill_detail(
    skill_id: str,
    installed_ids: Optional[str] = Query(
        default=None,
        description="客户端已安装的 skill_id 列表（逗号分隔）",
    ),
) -> SkillDetail:
    """返回单个 skill 的详细信息，包括所有版本历史。

    :param skill_id: Skill 唯一标识符。
    :param installed_ids: 已安装 skill_id 列表（逗号分隔）。
    :return: Skill 详情。
    :raises HTTPException: skill 不存在时抛出 404。
    """
    detail = storage.get_skill_detail(skill_id)
    if detail is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Skill '{skill_id}' 不存在",
        )

    metadata = detail.get("metadata", {})
    latest_version = detail.get("latest_version", "")

    # 解析已安装集合
    installed_set: set = set()
    if installed_ids:
        installed_set = {
            sid.strip() for sid in installed_ids.split(",") if sid.strip()
        }
    is_installed = (
        skill_id in installed_set if installed_ids is not None else None
    )

    # 构建版本信息列表
    versions = [
        VersionInfo(
            version=v.get("version", ""),
            sha256=v.get("sha256", ""),
            upload_date=v.get("upload_date", ""),
            download_count=v.get("download_count", 0),
            size_bytes=v.get("size_bytes", 0),
        )
        for v in detail.get("versions", [])
    ]

    return SkillDetail(
        id=metadata.get("id", skill_id),
        name=metadata.get("name", ""),
        version=metadata.get("version", latest_version),
        description=metadata.get("description", ""),
        entry=metadata.get("entry", "main.py"),
        entry_function=metadata.get("entry_function", "run"),
        author=metadata.get("author", ""),
        category=metadata.get("category", "other"),
        language=metadata.get("language", "python"),
        min_app_version=metadata.get("min_app_version", "0.0.0"),
        arch=metadata.get("arch", ["universal"]),
        skill_type=metadata.get("skill_type", "python"),
        dependencies=metadata.get("dependencies", []),
        latest_version=latest_version,
        download_url=f"/api/skills/{skill_id}/{latest_version}/download",
        download_count=detail.get("download_count", 0),
        is_installed=is_installed,
        versions=versions,
    )


# ======================================================================
# 版本列表
# ======================================================================


@app.get(
    "/api/skills/{skill_id}/versions",
    summary="获取 Skill 所有版本",
    tags=["Skill"],
    responses={
        404: {"model": ErrorResponse, "description": "Skill 不存在"},
    },
)
async def get_skill_versions(skill_id: str) -> dict:
    """返回指定 skill 的所有版本列表。

    :param skill_id: Skill 唯一标识符。
    :return: ``{"skill_id": ..., "versions": [VersionInfo, ...]}``
    :raises HTTPException: skill 不存在时抛出 404。
    """
    detail = storage.get_skill_detail(skill_id)
    if detail is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Skill '{skill_id}' 不存在",
        )

    versions = detail.get("versions", [])
    return {
        "skill_id": skill_id,
        "versions": [
            {
                "version": v.get("version", ""),
                "sha256": v.get("sha256", ""),
                "upload_date": v.get("upload_date", ""),
                "download_count": v.get("download_count", 0),
                "size_bytes": v.get("size_bytes", 0),
            }
            for v in versions
        ],
    }


# ======================================================================
# 下载 Skill
# ======================================================================


@app.get(
    "/api/skills/{skill_id}/{version}/download",
    summary="下载 .bsk 包",
    tags=["Skill"],
    responses={
        404: {"model": ErrorResponse, "description": "Skill 或版本不存在"},
    },
)
async def download_skill(
    skill_id: str,
    version: str,
):
    """下载指定 skill 某版本的 .bsk 文件。

    设置 ``Content-Disposition: attachment`` 以触发浏览器/客户端的下载行为，
    并将该版本的下载计数加 1。

    :param skill_id: Skill 唯一标识符。
    :param version: 版本号。
    :return: ``.bsk`` 文件响应。
    :raises HTTPException: 文件不存在时抛出 404。
    """
    file_path = storage.get_skill_file_path(skill_id, version)
    if file_path is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Skill '{skill_id}' 版本 '{version}' 不存在或文件缺失",
        )

    # 增加下载计数（不阻塞响应）
    try:
        storage.increment_download_count(skill_id, version)
    except Exception:
        # 下载计数失败不应影响下载本身
        pass

    return FileResponse(
        path=str(file_path),
        media_type="application/octet-stream",
        filename=f"{skill_id}-{version}.bsk",
        headers={
            "Content-Disposition": f'attachment; filename="{skill_id}-{version}.bsk"',
        },
    )


# ======================================================================
# 删除 Skill 版本
# ======================================================================


@app.delete(
    "/api/skills/{skill_id}/{version}",
    summary="删除指定 Skill 版本",
    tags=["Skill"],
    dependencies=[Depends(require_api_key)],
    responses={
        401: {"model": ErrorResponse, "description": "API Key 无效"},
        404: {"model": ErrorResponse, "description": "Skill 或版本不存在"},
    },
)
async def delete_skill_version(
    skill_id: str,
    version: str,
) -> dict:
    """删除指定 skill 的某个版本（需要 API Key 认证）。

    若删除后该 skill 不再有任何版本，则整个 skill 目录将被移除。

    :param skill_id: Skill 唯一标识符。
    :param version: 版本号。
    :return: ``{"success": True, "skill_id": ..., "version": ...}``
    :raises HTTPException: 版本不存在时抛出 404。
    """
    deleted = storage.delete_skill_version(skill_id, version)
    if not deleted:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Skill '{skill_id}' 版本 '{version}' 不存在",
        )

    return {
        "success": True,
        "skill_id": skill_id,
        "version": version,
    }


# ======================================================================
# 全局异常处理
# ======================================================================


@app.exception_handler(HTTPException)
async def http_exception_handler(
    request: Request, exc: HTTPException
) -> JSONResponse:
    """统一 HTTP 异常响应格式。"""
    return JSONResponse(
        status_code=exc.status_code,
        content=ErrorResponse(
            error=exc.__class__.__name__,
            detail=str(exc.detail),
        ).model_dump(),
    )


@app.exception_handler(Exception)
async def unhandled_exception_handler(
    request: Request, exc: Exception
) -> JSONResponse:
    """捕获未处理的异常，返回 500 并避免泄露内部堆栈。"""
    return JSONResponse(
        status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
        content=ErrorResponse(
            error="InternalServerError",
            detail="服务器内部错误",
        ).model_dump(),
    )


# ======================================================================
# 入口
# ======================================================================


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(
        "app:app",
        host=config.HOST,
        port=config.PORT,
        reload=False,
    )
