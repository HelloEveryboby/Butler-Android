import type { ButlerAPI } from '../services/api';
import type { WebSocketService } from '../services/websocket';
import type { NotificationManager } from '../services/notification';

// Extensions - extension manager + team manager + runner server + sensing
// Aggregates: extension_manager + team_manager + runner_server + sensing_api + display_protocol
export class Extensions {
    private api: ButlerAPI;
    private ws: WebSocketService;
    private notify: NotificationManager;

    constructor(api: ButlerAPI, ws: WebSocketService, notify: NotificationManager) {
        this.api = api;
        this.ws = ws;
        this.notify = notify;
    }

    init(): void {
        this.bindScan();
        this.loadExtensions();
        this.listenWsResponses();
    }

    private listenWsResponses(): void {
        this.ws.on('message', (data: Record<string, unknown>) => {
            const msg = data as { type: string; data?: unknown };
            if (msg.type === 'extensions:list') {
                this.renderExtensions(msg.data as Record<string, Array<Record<string, unknown>>>);
            }
            if (msg.type === 'team:status') {
                this.renderTeam(msg.data as Array<Record<string, unknown>>);
            }
            if (msg.type === 'runner:nodes') {
                this.renderRunners(msg.data as Array<Record<string, unknown>>);
            }
        });
    }

    private bindScan(): void {
        const scanBtn = document.getElementById('ext-scan-btn');
        scanBtn?.addEventListener('click', () => {
            this.ws.send({ type: 'extensions:scan' });
            this.notify.show('正在扫描扩展...', 'info');
        });
    }

    private loadExtensions(): void {
        this.ws.send({ type: 'extensions:get' });
        this.ws.send({ type: 'team:get' });
        this.ws.send({ type: 'runner:get' });
    }

    private renderExtensions(data: Record<string, Array<Record<string, unknown>>>): void {
        const container = document.getElementById('ext-plugins');
        if (!container) return;
        const plugins = data.plugins || [];
        const packages = data.packages || [];
        const programs = data.programs || [];

        container.innerHTML = `
            <div class="ext-section">
                <h4 class="ext-section-title"><i class="fas fa-puzzle-piece"></i> 插件 (${plugins.length})</h4>
                ${this._renderList(plugins, 'plugin')}
            </div>
            <div class="ext-section">
                <h4 class="ext-section-title"><i class="fas fa-box"></i> 包 (${packages.length})</h4>
                ${this._renderList(packages, 'package')}
            </div>
            <div class="ext-section">
                <h4 class="ext-section-title"><i class="fas fa-microchip"></i> 混合程序 (${programs.length})</h4>
                ${this._renderList(programs, 'program')}
            </div>
        `;
    }

    private renderTeam(members: Array<Record<string, unknown>>): void {
        const container = document.getElementById('ext-team');
        if (!container) return;
        if (!members || members.length === 0) {
            container.innerHTML = '<div class="memory-empty-small">无团队成员</div>';
            return;
        }
        container.innerHTML = members.map(m => `
            <div class="team-member">
                <span class="team-name">${this.escapeHtml(String(m.name || m.id || '未知'))}</span>
                <span class="team-role">${m.role || 'agent'}</span>
                <span class="team-status team-${m.status || 'idle'}">${m.status || 'idle'}</span>
            </div>
        `).join('');
    }

    private renderRunners(nodes: Array<Record<string, unknown>>): void {
        const container = document.getElementById('ext-runners');
        if (!container) return;
        if (!nodes || nodes.length === 0) {
            container.innerHTML = '<div class="memory-empty-small">无远程 Runner 节点</div>';
            return;
        }
        container.innerHTML = nodes.map(n => `
            <div class="runner-node">
                <span class="runner-name">${this.escapeHtml(String(n.name || n.host || '未知'))}</span>
                <span class="runner-status runner-${n.status || 'disconnected'}">${n.status || 'disconnected'}</span>
            </div>
        `).join('');
    }

    private _renderList(items: Array<Record<string, unknown>>, type: string): string {
        if (items.length === 0) return '<div class="memory-empty-small">无</div>';
        return '<div class="ext-list">' + items.map(item => `
            <div class="ext-item">
                <span class="ext-item-name">${this.escapeHtml(String(item.name || item.id || ''))}</span>
                <span class="ext-item-desc">${this.escapeHtml(String(item.description || ''))}</span>
            </div>
        `).join('') + '</div>';
    }

    private escapeHtml(text: string): string {
        const div = document.createElement('div');
        div.textContent = text;
        return div.innerHTML;
    }
}
