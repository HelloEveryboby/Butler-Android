import './style.css';
import { ChatManager } from './modules/chat';
import { StateMatrix } from './modules/matrix';
import { SettingsManager } from './modules/settings';
import { SkillsManager } from './modules/skills';
import { TerminalManager } from './modules/terminal';
import { MemosManager } from './modules/memos';
import { TimeMachine } from './modules/timemachine';
import { DagEngine } from './modules/dag';
import { HeatmapRenderer } from './modules/heatmap';
import { TasksBoard } from './modules/tasks';
import { SecretVault } from './modules/vault';
import { FocusMode } from './modules/focus';
import { ClusterView } from './modules/cluster';
import { CronScheduler } from './modules/cron';
import { CodeInterpreter } from './modules/codeInterpreter';
import { UserProfile } from './modules/profile';
import { Converter } from './modules/converter';
import { MediaCenter } from './modules/media';
import { MemoryCenter } from './modules/memoryCenter';
import { NotificationCenter } from './modules/notificationCenter';
import { SecurityToolkit } from './modules/securityToolkit';
import { Extensions } from './modules/extensions';
import { DynamicIslandController } from './modules/dynamicIsland';
import { WakeManagerModule } from './modules/wakeManager';
import { WebSocketService } from './services/websocket';
import { NotificationManager } from './services/notification';
import { ButlerAPI } from './services/api';

// ── Global instances ───────────────────────────────────────────
export const ws = new WebSocketService();
export const api = new ButlerAPI(ws);
export const notify = new NotificationManager();

// ── Module registry (tools page) ───────────────────────────────
const MODULES: Array<{
    id: string; panel: string; title: string;
    icon: string; color: string; desc: string;
}> = [
    { id: 'terminal',    panel: 'panel-terminal',    title: '终端',       icon: 'fa-terminal',      color: 'g', desc: '命令行终端' },
    { id: 'memos',       panel: 'panel-memos',       title: '备忘录',     icon: 'fa-sticky-note',   color: 'b', desc: '快速记录' },
    { id: 'timemachine', panel: 'panel-timemachine', title: '时间机器',   icon: 'fa-clock-rotate-left', color: 'p', desc: '系统历史' },
    { id: 'tasks',       panel: 'panel-tasks',       title: '任务看板',   icon: 'fa-list-check',    color: 'b', desc: '任务管理' },
    { id: 'vault',       panel: 'panel-vault',       title: '密钥库',     icon: 'fa-key',           color: 'o', desc: '加密存储' },
    { id: 'focus',       panel: 'panel-focus',       title: '专注模式',   icon: 'fa-hourglass-half', color: 'r', desc: '番茄钟' },
    { id: 'cluster',     panel: 'panel-cluster',     title: '集群管理',   icon: 'fa-server',        color: 'g', desc: '分布式节点' },
    { id: 'cron',        panel: 'panel-cron',        title: '定时任务',   icon: 'fa-clock',         color: 't', desc: 'Cron 调度' },
    { id: 'code',        panel: 'panel-code',        title: '代码执行',   icon: 'fa-code',          color: 'g', desc: '运行代码' },
    { id: 'profile',     panel: 'panel-profile',     title: '习惯档案',   icon: 'fa-user',          color: 'p', desc: '习惯追踪' },
    { id: 'converter',   panel: 'panel-converter',   title: '格式转换',   icon: 'fa-exchange-alt',  color: 'b', desc: '文档转换' },
    { id: 'media',       panel: 'panel-media',       title: '媒体中心',   icon: 'fa-music',         color: 'p', desc: '音乐与媒体' },
    { id: 'memory',      panel: 'panel-memory',      title: '记忆引擎',   icon: 'fa-brain',         color: 't', desc: '记忆与复习' },
    { id: 'notify',      panel: 'panel-notify',      title: '通知中心',   icon: 'fa-bell',          color: 'o', desc: '通知与建议' },
    { id: 'security',    panel: 'panel-security',    title: '安全工具',   icon: 'fa-shield-halved', color: 'r', desc: '安全扫描' },
    { id: 'extensions',  panel: 'panel-extensions',  title: '扩展管理',   icon: 'fa-puzzle-piece',  color: 'y', desc: '插件与团队' },
    { id: 'workflow',    panel: 'panel-workflow',    title: '工作流',     icon: 'fa-diagram-project', color: 'g', desc: 'DAG 编排' },
];

// ── Tab title map ──────────────────────────────────────────────
const TAB_TITLES: Record<string, string> = {
    home: 'Butler AI', chat: '对话', skills: '技能',
    tools: '工具', settings: '设置',
};

// ── Navigation Controller ──────────────────────────────────────
class NavController {
    private currentTab = 'home';
    private stack: Array<{ page: HTMLElement; title: string }> = [];
    private content!: HTMLElement;
    private tabBar!: HTMLElement;
    private navBack!: HTMLElement;
    private navTitle!: HTMLElement;
    private navRight!: HTMLElement;
    private hiddenPanels!: HTMLElement;
    private pages: Record<string, HTMLElement> = {};

    init(): void {
        this.content    = document.getElementById('content')!;
        this.tabBar     = document.getElementById('tabBar')!;
        this.navBack    = document.getElementById('navBack')!;
        this.navTitle   = document.getElementById('navTitle')!;
        this.navRight   = document.getElementById('navRight')!;
        this.hiddenPanels = document.getElementById('hidden-panels')!;

        // Tab bar clicks
        this.tabBar.querySelectorAll<HTMLElement>('.tab').forEach(tab => {
            tab.addEventListener('click', () => {
                const t = tab.dataset.tab;
                if (t) this.switchTab(t);
            });
        });

        // Back button
        this.navBack.addEventListener('click', () => this.popPage());

        // Build static pages
        this.buildHomePage();
        this.buildToolsPage();

        // Show home
        this.showPage('home');
        this.updateClock();
        setInterval(() => this.updateClock(), 30_000);
    }

    // ── Build Home Page ──────────────────────────────────────────

    private buildHomePage(): void {
        const el = document.getElementById('page-home');
        if (!el) return;
        el.innerHTML = `
            <div class="home-scroll">
                <div class="hero">
                    <div class="hero-time" id="heroTime">9:41</div>
                    <div class="hero-date" id="heroDate"></div>
                </div>
                <div class="widgets">
                    <div class="widget">
                        <div class="card-head">
                            <span class="card-label">系统状态</span>
                            <span class="card-action" id="home-detail-btn">详情</span>
                        </div>
                        <div class="stats-row">
                            <div class="stat-item">
                                <div class="stat-num blue" id="home-cpu">--</div>
                                <div class="stat-label">CPU</div>
                            </div>
                            <div class="stat-item">
                                <div class="stat-num green" id="home-mem">--</div>
                                <div class="stat-label">内存</div>
                            </div>
                            <div class="stat-item">
                                <div class="stat-num orange" id="home-disk">--</div>
                                <div class="stat-label">磁盘</div>
                            </div>
                        </div>
                    </div>
                    <div class="widget">
                        <div class="card-head">
                            <span class="card-label">快捷操作</span>
                        </div>
                        <div class="w-grid">
                            <div class="w-grid-item" data-module="chat">
                                <div class="w-gi-icon b"><i class="fas fa-comments"></i></div>
                                <div class="w-gi-text">
                                    <div class="w-gi-name">对话</div>
                                    <div class="w-gi-desc">AI 助手</div>
                                </div>
                            </div>
                            <div class="w-grid-item" data-module="terminal">
                                <div class="w-gi-icon g"><i class="fas fa-terminal"></i></div>
                                <div class="w-gi-text">
                                    <div class="w-gi-name">终端</div>
                                    <div class="w-gi-desc">命令行</div>
                                </div>
                            </div>
                            <div class="w-grid-item" data-module="tasks">
                                <div class="w-gi-icon p"><i class="fas fa-list-check"></i></div>
                                <div class="w-gi-text">
                                    <div class="w-gi-name">任务</div>
                                    <div class="w-gi-desc">看板管理</div>
                                </div>
                            </div>
                            <div class="w-grid-item" data-module="focus">
                                <div class="w-gi-icon o"><i class="fas fa-hourglass-half"></i></div>
                                <div class="w-gi-text">
                                    <div class="w-gi-name">专注</div>
                                    <div class="w-gi-desc">番茄时钟</div>
                                </div>
                            </div>
                        </div>
                    </div>
                    <div class="widget">
                        <div class="card-head">
                            <span class="card-label">最近活动</span>
                            <span class="card-action">查看全部</span>
                        </div>
                        <div class="w-list">
                            <div class="w-list-item">
                                <div class="w-li-avatar" style="background:rgba(0,122,255,0.1);"><i class="fas fa-robot" style="color:var(--blue);"></i></div>
                                <div class="w-li-body">
                                    <div class="w-li-title">Butler AI</div>
                                    <div class="w-li-sub">系统已就绪，随时为您服务</div>
                                </div>
                                <div class="w-li-meta">刚刚</div>
                            </div>
                            <div class="w-list-item">
                                <div class="w-li-avatar" style="background:rgba(52,199,89,0.1);"><i class="fas fa-check-circle" style="color:var(--green);"></i></div>
                                <div class="w-li-body">
                                    <div class="w-li-title">系统自检</div>
                                    <div class="w-li-sub">所有服务运行正常</div>
                                </div>
                                <div class="w-li-meta">2 分钟前</div>
                            </div>
                        </div>
                    </div>
                </div>
            </div>`;

        // Bind widget clicks
        el.querySelectorAll<HTMLElement>('.w-grid-item').forEach(item => {
            item.addEventListener('click', () => {
                const mod = item.dataset.module;
                if (mod === 'chat') { this.switchTab('chat'); return; }
                const info = MODULES.find(m => m.id === mod);
                if (info) this.pushModule(info);
            });
        });

        // "详情" → time machine
        const detailBtn = el.querySelector('#home-detail-btn');
        detailBtn?.addEventListener('click', () => {
            const tm = MODULES.find(m => m.id === 'timemachine');
            if (tm) this.pushModule(tm);
        });

        this.pages['home'] = el;
    }

    // ── Build Tools Page ─────────────────────────────────────────

    private buildToolsPage(): void {
        const el = document.getElementById('page-tools');
        if (!el) return;
        let html = `<div class="tools-scroll"><div class="tools-header"><h2>全部工具</h2><p>${MODULES.length} 个可用模块</p></div><div class="module-grid">`;
        for (const m of MODULES) {
            html += `<div class="module-card" data-mid="${m.id}">
                <div class="module-icon ${m.color}"><i class="fas ${m.icon}"></i></div>
                <div class="module-text"><div class="module-name">${m.title}</div>
                <div class="module-desc">${m.desc}</div></div></div>`;
        }
        html += '</div></div>';
        el.innerHTML = html;

        el.querySelectorAll<HTMLElement>('.module-card').forEach(card => {
            card.addEventListener('click', () => {
                const mid = card.dataset.mid;
                const info = MODULES.find(m => m.id === mid);
                if (info) this.pushModule(info);
            });
        });

        this.pages['tools'] = el;
    }

    // ── Tab Switching ────────────────────────────────────────────

    switchTab(tab: string): void {
        if (tab === this.currentTab && this.stack.length === 0) return;

        // Reset stack
        for (const s of this.stack) {
            this.moveBack(s.page);
        }
        this.stack = [];

        // Update tab bar
        this.currentTab = tab;
        this.tabBar.querySelectorAll<HTMLElement>('.tab').forEach(t =>
            t.classList.toggle('on', t.dataset.tab === tab));

        // Transition pages
        const oldPage = this.content.querySelector<HTMLElement>('.page.active');
        if (oldPage) {
            oldPage.classList.remove('active');
            this.moveBack(oldPage);
        }

        const newPage = this.getPage(tab);
        requestAnimationFrame(() => {
            this.content.appendChild(newPage);
            requestAnimationFrame(() => newPage.classList.add('active'));
        });

        this.updateNavBar();
    }

    // ── Push / Pop Module Pages ──────────────────────────────────

    pushModule(mod: { panel: string; title: string }): void {
        const panel = document.getElementById(mod.panel);
        if (!panel) return;

        // Create page wrapper
        const page = document.createElement('div');
        page.className = 'page ahead';
        page.dataset.pageTitle = mod.title;
        page.appendChild(panel);
        this.content.appendChild(page);

        // Animate in
        requestAnimationFrame(() => {
            const cur = this.content.querySelector<HTMLElement>('.page.active');
            if (cur) { cur.classList.remove('active'); cur.classList.add('behind'); }
            requestAnimationFrame(() => page.classList.replace('ahead', 'active'));
        });

        this.stack.push({ page, title: mod.title });
        this.updateNavBar();
    }

    popPage(): void {
        if (this.stack.length === 0) return;

        const { page } = this.stack.pop()!;
        const panel = page.firstElementChild as HTMLElement;

        // Animate out
        page.classList.remove('active');
        page.classList.add('ahead');

        const prev = this.content.querySelector<HTMLElement>('.page.behind');
        if (prev) {
            // Disable transition for instant reposition
            prev.style.transition = 'none';
            prev.classList.remove('behind');
            prev.classList.add('active');
            requestAnimationFrame(() => { prev.style.transition = ''; });
        }

        // After animation, move panel back
        setTimeout(() => {
            if (panel && this.hiddenPanels) this.hiddenPanels.appendChild(panel);
            page.remove();
        }, 320);

        this.updateNavBar();
    }

    // ── Helpers ──────────────────────────────────────────────────

    private getPage(tab: string): HTMLElement {
        if (this.pages[tab]) return this.pages[tab];

        // For chat / skills / settings — move from hidden-panels
        const panelId = `panel-${tab}`;
        const panel = document.getElementById(panelId);
        if (!panel) {
            const fallback = document.createElement('div');
            fallback.className = 'page';
            fallback.innerHTML = `<div style="padding:40px;text-align:center;color:var(--text3);">页面不存在</div>`;
            this.pages[tab] = fallback;
            return fallback;
        }

        const page = document.createElement('div');
        page.className = 'page';
        page.dataset.pageTitle = TAB_TITLES[tab] || tab;
        page.appendChild(panel);
        this.pages[tab] = page;
        return page;
    }

    private showPage(tab: string): void {
        const page = this.getPage(tab);
        this.content.appendChild(page);
        requestAnimationFrame(() => page.classList.add('active'));
    }

    private moveBack(el: HTMLElement): void {
        if (!el) return;
        const tab = el.dataset?.pageTitle;
        if (tab && this.pages[tab] === el) {
            // It's a tab page — keep reference, just detach
            el.remove();
        } else {
            this.hiddenPanels.appendChild(el);
        }
    }

    private updateNavBar(): void {
        const depth = this.stack.length;
        if (depth > 0) {
            this.navBack.style.display = '';
            this.navTitle.textContent = this.stack[depth - 1].title;
        } else {
            this.navBack.style.display = 'none';
            this.navTitle.textContent = TAB_TITLES[this.currentTab] || 'Butler AI';
        }
        this.navRight.style.display = 'none';
    }

    private updateClock(): void {
        const now = new Date();
        const h = now.getHours();
        const m = String(now.getMinutes()).padStart(2, '0');
        const timeStr = `${h}:${m}`;

        const clock = document.getElementById('statusTime');
        if (clock) clock.textContent = timeStr;

        const heroTime = document.getElementById('heroTime');
        if (heroTime) heroTime.textContent = timeStr;

        const days = ['星期日','星期一','星期二','星期三','星期四','星期五','星期六'];
        const dateStr = `${now.getMonth() + 1}月${now.getDate()}日 ${days[now.getDay()]}`;
        const heroDate = document.getElementById('heroDate');
        if (heroDate) heroDate.textContent = dateStr;
    }
}

// ── Boot ───────────────────────────────────────────────────────
const nav = new NavController();

document.addEventListener('DOMContentLoaded', () => {
    // Navigation
    nav.init();

    // Core modules
    ws.init();
    const matrix     = new StateMatrix();
    const chat       = new ChatManager(api, ws, notify);
    const settings   = new SettingsManager(api, notify);
    const skills     = new SkillsManager(api, notify);
    const terminal   = new TerminalManager(api, ws);
    const memos      = new MemosManager(api, notify);
    const timemachine = new TimeMachine(api, ws);
    const dag        = new DagEngine(api, ws, notify);
    const heatmap    = new HeatmapRenderer();

    // Feature modules
    const tasks      = new TasksBoard(api, notify);
    const vault      = new SecretVault(api, notify);
    const focus      = new FocusMode(api, ws, notify);
    const cluster    = new ClusterView(api, ws, notify);
    const cron       = new CronScheduler(api, ws, notify);
    const code       = new CodeInterpreter(api, ws, notify);
    const profile    = new UserProfile(api, ws, notify);

    // Aggregated panels
    const converter  = new Converter(api, ws, notify);
    const media      = new MediaCenter(api, ws, notify);
    const memory     = new MemoryCenter(api, ws, notify);
    const notifyCenter = new NotificationCenter(api, ws, notify);
    const security   = new SecurityToolkit(api, ws, notify);
    const extensions = new Extensions(api, ws, notify);

    // Dynamic Island + Wake
    const island     = new DynamicIslandController(api, ws, notify);
    const wakeManager = new WakeManagerModule(api, ws, notify, island);

    // Init all modules
    matrix.init();
    chat.init();
    settings.init();
    skills.init();
    terminal.init();
    memos.init();
    timemachine.init();
    dag.init();
    heatmap.init();
    tasks.init();
    vault.init();
    focus.init();
    cluster.init();
    cron.init();
    code.init();
    profile.init();
    converter.init();
    media.init();
    memory.init();
    notifyCenter.init();
    security.init();
    extensions.init();
    island.init();
    wakeManager.init();

    console.log('[Butler] All 22 modules initialized with unified Apple-style navigation.');
});
