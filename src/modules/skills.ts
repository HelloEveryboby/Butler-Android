import type { ButlerAPI, SkillItem } from '../services/api';
import type { NotificationManager } from '../services/notification';

// SkillsManager - renders and manages the skill card grid from backend
export class SkillsManager {
    private api: ButlerAPI;
    private notify: NotificationManager;

    constructor(api: ButlerAPI, notify: NotificationManager) {
        this.api = api;
        this.notify = notify;
    }

    init(): void {
        this.loadSkills();
    }

    private async loadSkills(): Promise<void> {
        const grid = document.getElementById('skills-grid');
        if (!grid) return;

        // Show loading
        grid.innerHTML = '<div class="loading-hint">加载技能列表...</div>';

        const skills = await this.api.getSkills();
        if (skills && skills.length > 0) {
            this.renderSkills(skills);
        } else {
            // Fallback: show default skills if backend returns empty
            this.renderFallbackSkills();
        }
    }

    private renderSkills(skills: SkillItem[]): void {
        const grid = document.getElementById('skills-grid');
        if (!grid) return;
        grid.innerHTML = '';
        skills.forEach(skill => {
            const card = document.createElement('div');
            card.className = 'skill-card';
            card.innerHTML = `
                <div class="skill-card-icon" style="color:${skill.color};background:${skill.color}18;">
                    <i class="fas ${skill.icon || 'fa-puzzle-piece'}"></i>
                </div>
                <div class="skill-card-info">
                    <span class="skill-card-name">${skill.name}</span>
                    <span class="skill-card-desc">${skill.desc}</span>
                </div>
                <button class="skill-card-run" data-skill="${skill.id}" title="运行">
                    <i class="fas fa-play"></i>
                </button>
            `;
            card.querySelector('.skill-card-run')?.addEventListener('click', (e) => {
                e.stopPropagation();
                this.api.runSkill(skill.id);
                this.notify.show(`正在执行: ${skill.name}`, 'info');
            });
            grid.appendChild(card);
        });
    }

    private renderFallbackSkills(): void {
        // Fallback skill set when backend is unavailable
        const fallback: SkillItem[] = [
            { id: 'sys_cleaner', name: '系统清理', icon: 'fa-broom', color: '#FF9500', desc: '清除冗余缓存恢复磁盘' },
            { id: 'memos', name: '备忘录', icon: 'fa-sticky-note', color: '#007AFF', desc: '快速记录和管理备忘' },
            { id: 'translator', name: '翻译器', icon: 'fa-language', color: '#34C759', desc: '多语言智能翻译' },
            { id: 'pdf', name: 'PDF 处理', icon: 'fa-file-pdf', color: '#FF3B30', desc: 'PDF 文档解析与转换' },
            { id: 'docx', name: 'Word 处理', icon: 'fa-file-word', color: '#5AC8FA', desc: 'Word 文档读写' },
            { id: 'media_manager', name: '媒体管理', icon: 'fa-photo-film', color: '#AF52DE', desc: '图片视频批量处理' },
            { id: 'archive_manager', name: '压缩管理', icon: 'fa-file-zipper', color: '#FFCC00', desc: '压缩解压归档文件' },
            { id: 'task_management', name: '任务管理', icon: 'fa-list-check', color: '#FF2D55', desc: '创建和管理任务清单' },
            { id: 'frontend_design', name: '前端设计', icon: 'fa-palette', color: '#64D2FF', desc: '基于描述生成前端代码' },
        ];
        this.renderSkills(fallback);
    }
}
