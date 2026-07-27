import type { ButlerAPI, MarketplaceSkill, SkillInstallResult, SkillScanResult } from '../services/api';
import type { NotificationManager } from '../services/notification';
import { escapeHtml } from '../utils';

// MarketplaceManager — 技能市场浏览、安装、管理
export class MarketplaceManager {
    private api: ButlerAPI;
    private notify: NotificationManager;

    constructor(api: ButlerAPI, notify: NotificationManager) {
        this.api = api;
        this.notify = notify;
    }

    init(): void {
        this.bindEvents();
    }

    // ── 事件绑定 ──────────────────────────────────────────────────

    private bindEvents(): void {
        // 市场标签切换
        document.querySelectorAll('.marketplace-tab').forEach(tab => {
            tab.addEventListener('click', () => {
                const tabName = (tab as HTMLElement).dataset.tab;
                this.switchMarketTab(tabName || 'market');
            });
        });

        // 扫描自定义目录按钮
        const scanBtn = document.getElementById('scan-custom-dir-btn');
        if (scanBtn) {
            scanBtn.addEventListener('click', () => this.scanCustomDir());
        }

        // 自定义目录路径输入
        const customDirInput = document.getElementById('custom-dir-input') as HTMLInputElement;
        if (customDirInput) {
            customDirInput.placeholder = '/sdcard/Butler/skills/custom';
        }

        // 市场搜索
        const searchInput = document.getElementById('marketplace-search') as HTMLInputElement;
        if (searchInput) {
            searchInput.addEventListener('input', () => {
                this.filterMarketplace(searchInput.value);
            });
        }
    }

    // ── 标签切换 ──────────────────────────────────────────────────

    switchMarketTab(tab: string): void {
        document.querySelectorAll('.marketplace-tab').forEach(t => t.classList.remove('active'));
        const activeTab = document.querySelector(`.marketplace-tab[data-tab="${tab}"]`);
        if (activeTab) activeTab.classList.add('active');

        // 隐藏所有面板
        document.querySelectorAll('.marketplace-panel').forEach(p => p.classList.add('hidden'));
        const panel = document.getElementById(`marketplace-panel-${tab}`);
        if (panel) panel.classList.remove('hidden');

        if (tab === 'market') {
            this.loadMarketplace();
        } else if (tab === 'local') {
            this.loadLocalView();
        }
    }

    // ── 市场浏览 ──────────────────────────────────────────────────

    async loadMarketplace(): Promise<void> {
        const container = document.getElementById('marketplace-list');
        if (!container) return;

        container.innerHTML = '<div class="loading-hint">加载技能市场...</div>';

        const skills = await this.api.getMarketplaceSkills();
        if (skills.length === 0) {
            container.innerHTML = '<div class="empty-state"><i class="fas fa-store-slash"></i><p>无法连接到技能市场</p></div>';
            return;
        }

        this.renderMarketplaceCards(skills, container);
    }

    private renderMarketplaceCards(skills: MarketplaceSkill[], container: HTMLElement): void {
        container.innerHTML = '';
        skills.forEach(skill => {
            const card = document.createElement('div');
            card.className = 'marketplace-card';
            card.setAttribute('data-category', skill.category);
            card.setAttribute('data-search', `${skill.name} ${skill.description} ${skill.tags.join(' ')}`.toLowerCase());

            const stars = this.renderStars(skill.rating);
            const actionBtn = skill.installed
                ? `<button class="marketplace-btn installed" disabled>
                     <i class="fas fa-check"></i> 已安装 ${escapeHtml(skill.installed_version || '')}
                   </button>`
                : `<button class="marketplace-btn install" data-skill-id="${escapeHtml(skill.id)}" data-url="${escapeHtml(skill.download_url)}">
                     <i class="fas fa-download"></i> 安装
                   </button>`;

            card.innerHTML = `
                <div class="marketplace-card-header">
                    <div class="marketplace-card-icon" style="color:${escapeHtml(skill.color)};background:${escapeHtml(skill.color)}18;">
                        <i class="fas ${escapeHtml(skill.icon)}"></i>
                    </div>
                    <div class="marketplace-card-meta">
                        <span class="marketplace-card-name">${escapeHtml(skill.name)}</span>
                        <span class="marketplace-card-author">${escapeHtml(skill.author)} · v${escapeHtml(skill.version)}</span>
                    </div>
                </div>
                <p class="marketplace-card-desc">${escapeHtml(skill.description)}</p>
                <div class="marketplace-card-tags">
                    ${skill.tags.map(t => `<span class="marketplace-tag">${escapeHtml(t)}</span>`).join('')}
                </div>
                <div class="marketplace-card-footer">
                    <div class="marketplace-card-stats">
                        <span class="marketplace-stat">${stars} ${skill.rating}</span>
                        <span class="marketplace-stat"><i class="fas fa-download"></i> ${this.formatDownloads(skill.downloads)}</span>
                        <span class="marketplace-stat"><i class="fas fa-hdd"></i> ${escapeHtml(skill.size)}</span>
                    </div>
                    ${actionBtn}
                </div>
            `;

            // 绑定安装按钮
            const installBtn = card.querySelector('.marketplace-btn.install');
            if (installBtn) {
                installBtn.addEventListener('click', (e) => {
                    e.stopPropagation();
                    const btn = e.currentTarget as HTMLElement;
                    const url = btn.dataset.url || '';
                    const skillId = btn.dataset.skillId || '';
                    this.installFromMarketplace(skillId, url, btn);
                });
            }

            container.appendChild(card);
        });
    }

    private filterMarketplace(query: string): void {
        const q = query.toLowerCase().trim();
        document.querySelectorAll('.marketplace-card').forEach(card => {
            const searchText = (card as HTMLElement).dataset.search || '';
            (card as HTMLElement).style.display = q === '' || searchText.includes(q) ? '' : 'none';
        });
    }

    // ── 安装技能 ──────────────────────────────────────────────────

    private async installFromMarketplace(skillId: string, url: string, btn: HTMLElement): Promise<void> {
        btn.textContent = '下载中...';
        btn.setAttribute('disabled', 'true');
        (btn as HTMLButtonElement).disabled = true;

        this.notify.show(`正在安装技能...`, 'info');

        const result: SkillInstallResult = await this.api.installSkill(url);

        if (result.ok) {
            btn.innerHTML = '<i class="fas fa-check"></i> 已安装';
            btn.classList.add('installed');
            btn.classList.remove('install');
            this.notify.show(`技能安装成功!`, 'success');
            // 刷新技能列表
            setTimeout(() => this.loadMarketplace(), 1000);
        } else {
            btn.textContent = '安装';
            btn.classList.remove('installed');
            (btn as HTMLButtonElement).disabled = false;
            this.notify.show(`安装失败: ${result.error || '未知错误'}`, 'error');
        }
    }

    // ── 本地管理 ──────────────────────────────────────────────────

    private async loadLocalView(): Promise<void> {
        const container = document.getElementById('local-skills-list');
        if (!container) return;

        container.innerHTML = '<div class="loading-hint">加载已安装技能...</div>';

        const skills = await this.api.getSkills();
        if (skills.length === 0) {
            container.innerHTML = '<div class="empty-state"><i class="fas fa-box-open"></i><p>暂无已安装的技能</p></div>';
            return;
        }

        container.innerHTML = '';
        skills.forEach(skill => {
            const card = document.createElement('div');
            card.className = 'local-skill-card';
            card.innerHTML = `
                <div class="local-skill-icon" style="color:${escapeHtml(skill.color)};background:${escapeHtml(skill.color)}18;">
                    <i class="fas ${escapeHtml(skill.icon || 'fa-puzzle-piece')}"></i>
                </div>
                <div class="local-skill-info">
                    <span class="local-skill-name">${escapeHtml(skill.name)}</span>
                    <span class="local-skill-id">${escapeHtml(skill.id)} · v${escapeHtml(skill.version || '1.0.0')}</span>
                </div>
                <button class="local-skill-uninstall" data-skill-id="${escapeHtml(skill.id)}" title="卸载">
                    <i class="fas fa-trash-alt"></i>
                </button>
            `;

            const uninstallBtn = card.querySelector('.local-skill-uninstall') as HTMLElement;
            if (uninstallBtn) {
                uninstallBtn.addEventListener('click', () => {
                    const sid = uninstallBtn.dataset.skillId || '';
                    this.uninstallSkill(sid, card);
                });
            }
            container.appendChild(card);
        });
    }

    private async uninstallSkill(skillId: string, card: HTMLElement): Promise<void> {
        const confirmed = confirm(`确定要卸载技能 "${skillId}" 吗?`);
        if (!confirmed) return;

        this.notify.show('正在卸载...', 'info');
        const result = await this.api.uninstallSkill(skillId);

        if (result.ok) {
            card.style.opacity = '0';
            card.style.transform = 'translateX(20px)';
            card.style.transition = 'all 0.3s ease';
            setTimeout(() => card.remove(), 300);
            this.notify.show('技能已卸载', 'success');
        } else {
            this.notify.show(`卸载失败: ${result.error || '未知错误'}`, 'error');
        }
    }

    // ── 自定义目录扫描 ────────────────────────────────────────────

    private async scanCustomDir(): Promise<void> {
        const input = document.getElementById('custom-dir-input') as HTMLInputElement;
        const dirPath = input?.value?.trim() || '';

        const scanBtn = document.getElementById('scan-custom-dir-btn') as HTMLButtonElement;
        if (scanBtn) {
            scanBtn.disabled = true;
            scanBtn.textContent = '扫描中...';
        }

        this.notify.show('正在扫描目录...', 'info');
        const result: SkillScanResult = await this.api.scanSkillsDir(dirPath || undefined);

        if (scanBtn) {
            scanBtn.disabled = false;
            scanBtn.textContent = '扫描并安装';
        }

        if (result.ok) {
            const installed = result.installed || [];
            const failed = result.failed || [];
            if (installed.length > 0) {
                this.notify.show(`成功安装 ${installed.length} 个技能`, 'success');
            }
            if (failed.length > 0) {
                this.notify.show(`${failed.length} 个技能安装失败`, 'warning');
            }
            if (installed.length === 0 && failed.length === 0) {
                this.notify.show('未发现可安装的技能包', 'info');
            }
            // 刷新本地列表
            this.loadLocalView();
        } else {
            this.notify.show(`扫描失败: ${result.error || '未知错误'}`, 'error');
        }
    }

    // ── 辅助方法 ──────────────────────────────────────────────────

    private renderStars(rating: number): string {
        const full = Math.floor(rating);
        const half = rating - full >= 0.5 ? 1 : 0;
        const empty = 5 - full - half;
        return '<span class="stars">'
            + '<i class="fas fa-star"></i>'.repeat(full)
            + (half ? '<i class="fas fa-star-half-alt"></i>' : '')
            + '<i class="far fa-star"></i>'.repeat(empty)
            + '</span>';
    }

    private formatDownloads(n: number): string {
        if (n >= 10000) return (n / 1000).toFixed(1) + 'k';
        if (n >= 1000) return (n / 1000).toFixed(1) + 'k';
        return String(n);
    }
}