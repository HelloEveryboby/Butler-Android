"""Skill 市场后端数据模型。

使用 Pydantic v2 定义所有 API 请求/响应的数据结构。
模型之间通过继承复用公共字段，保证类型安全与自动文档生成。

模型层级
--------
- :class:`SkillMetadata`: Skill 元信息（来自 manifest.json + 上传 metadata）
- :class:`VersionInfo`: 单个版本的附加信息（哈希、上传时间、下载量等）
- :class:`SkillListItem`: 列表项 = SkillMetadata + 版本/下载信息
- :class:`SkillDetail`: 详情 = SkillListItem + 全部版本历史
- :class:`UploadResponse` / :class:`SkillListResponse` / :class:`ErrorResponse`
"""

from __future__ import annotations

from typing import List, Optional

from pydantic import BaseModel, Field


# ======================================================================
# 基础元数据模型
# ======================================================================


class SkillMetadata(BaseModel):
    """Skill 元数据模型。

    该模型描述一个 Skill 的核心属性，字段来源于 ``manifest.json``
    与上传时附加的 metadata。注意 ``entry`` 对应 manifest 中的
    ``entry_file``（入口模块文件名）。

    :ivar id: Skill 唯一标识符（对应 manifest 中的 ``skill_id``）
    :ivar name: Skill 展示名称
    :ivar version: 语义化版本号，如 ``1.0.0``
    :ivar description: Skill 功能描述
    :ivar entry: 入口模块文件名，如 ``main.py``
    :ivar entry_function: 入口函数名称，如 ``run``
    :ivar author: 作者
    :ivar category: 分类，如 ``productivity``、``entertainment``
    :ivar language: 实现语言，如 ``python``
    :ivar min_app_version: 所需的最低 App 版本
    :ivar arch: 支持的架构列表，如 ``["arm64-v8a"]``
    :ivar skill_type: Skill 类型，如 ``python``、``script``
    :ivar dependencies: 依赖声明列表
    """

    id: str = Field(..., description="Skill 唯一标识符")
    name: str = Field(..., description="Skill 展示名称")
    version: str = Field(..., description="语义化版本号")
    description: str = Field(default="", description="Skill 功能描述")
    entry: str = Field(default="main.py", description="入口模块文件名")
    entry_function: str = Field(default="run", description="入口函数名称")
    author: str = Field(default="", description="作者")
    category: str = Field(default="other", description="分类")
    language: str = Field(default="python", description="实现语言")
    min_app_version: str = Field(default="0.0.0", description="所需最低 App 版本")
    arch: List[str] = Field(
        default_factory=lambda: ["universal"],
        description="支持的架构列表",
    )
    skill_type: str = Field(default="python", description="Skill 类型")
    dependencies: List[str] = Field(
        default_factory=list,
        description="依赖声明列表",
    )


# ======================================================================
# 版本信息模型
# ======================================================================


class VersionInfo(BaseModel):
    """单个 Skill 版本的附加信息。

    :ivar version: 版本号
    :ivar sha256: 包文件的 SHA256 校验和
    :ivar upload_date: 上传时间（ISO 8601 格式）
    :ivar download_count: 累计下载次数
    :ivar size_bytes: 包文件大小（字节）
    """

    version: str = Field(..., description="版本号")
    sha256: str = Field(..., description="包文件 SHA256 校验和")
    upload_date: str = Field(..., description="上传时间 (ISO 8601)")
    download_count: int = Field(default=0, description="累计下载次数")
    size_bytes: int = Field(default=0, description="包文件大小（字节）")


# ======================================================================
# API 响应模型
# ======================================================================


class SkillListItem(SkillMetadata):
    """Skill 列表项模型。

    在 :class:`SkillMetadata` 基础上附加版本与下载信息。
    当查询参数提供 ``is_installed`` 标记集合时，``is_installed``
    字段反映该 skill 是否在客户端已安装。

    :ivar latest_version: 最新版本号
    :ivar download_url: 下载链接
    :ivar download_count: 总下载次数（所有版本合计）
    :ivar is_installed: 客户端是否已安装该 skill
    """

    latest_version: str = Field(..., description="最新版本号")
    download_url: str = Field(..., description="下载链接")
    download_count: int = Field(default=0, description="总下载次数")
    is_installed: Optional[bool] = Field(
        default=None,
        description="客户端是否已安装（仅当查询提供 installed_ids 时有效）",
    )


class SkillDetail(SkillListItem):
    """Skill 详情模型。

    在列表项基础上附加全部版本历史。

    :ivar versions: 所有版本信息列表
    """

    versions: List[VersionInfo] = Field(
        default_factory=list,
        description="所有版本信息列表",
    )


class UploadResponse(BaseModel):
    """上传成功响应。

    :ivar success: 是否上传成功
    :ivar skill_id: Skill 唯一标识符
    :ivar version: 本次上传的版本号
    :ivar sha256: 包文件 SHA256 校验和
    :ivar download_url: 下载链接
    """

    success: bool = Field(..., description="是否上传成功")
    skill_id: str = Field(..., description="Skill 唯一标识符")
    version: str = Field(..., description="版本号")
    sha256: str = Field(..., description="包文件 SHA256 校验和")
    download_url: str = Field(..., description="下载链接")


class SkillListResponse(BaseModel):
    """Skill 列表响应。

    :ivar skills: Skill 列表
    :ivar total: 符合条件的 skill 总数（分页前）
    :ivar page: 当前页码（从 1 开始）
    :ivar page_size: 每页大小
    """

    skills: List[SkillListItem] = Field(default_factory=list, description="Skill 列表")
    total: int = Field(default=0, description="符合条件的 skill 总数")
    page: int = Field(default=1, description="当前页码")
    page_size: int = Field(default=20, description="每页大小")


class ErrorResponse(BaseModel):
    """通用错误响应。

    :ivar error: 错误类型/简短描述
    :ivar detail: 错误详细信息
    """

    error: str = Field(..., description="错误类型/简短描述")
    detail: str = Field(..., description="错误详细信息")
