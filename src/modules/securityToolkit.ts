import type { ButlerAPI } from '../services/api';
import type { WebSocketService } from '../services/websocket';
import type { NotificationManager } from '../services/notification';
import { escapeHtml } from '../utils';

// SecurityToolkit - port scanner + network tools + backup sync
// Aggregates: skill_sec_radar + hybrid_net + skill_local_sync + config_backup
export class SecurityToolkit {
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
        this.bindNetTools();
        this.bindSync();
        this.bindBackup();
        this.listenWsResponses();
    }

    private listenWsResponses(): void {
        this.ws.on('message', (data: Record<string, unknown>) => {
            const msg = data as { type: string; data?: unknown };
            if (msg.type === 'security:scan_result') {
                this.renderScanResults(msg.data as Array<Record<string, unknown>>);
            }
            if (msg.type === 'security:net_result') {
                this.appendLog(String(msg.data));
            }
            if (msg.type === 'security:sync_status') {
                this.updateSyncStatus(msg.data as Record<string, unknown>);
            }
        });
    }

    private bindScan(): void {
        const form = document.getElementById('security-scan-form');
        if (!form) return;
        form.addEventListener('submit', (e) => {
            e.preventDefault();
            const target = (document.getElementById('security-scan-target') as HTMLInputElement).value.trim();
            const ports = (document.getElementById('security-scan-ports') as HTMLInputElement).value.trim();
            if (!target) {
                this.notify.show('请输入扫描目标', 'warning');
                return;
            }
            this.ws.send({ type: 'security:scan', target, ports: ports || '1-1024' });
            this.setScanOutput('正在扫描...');
        });
    }

    private bindNetTools(): void {
        const urlCheck = document.getElementById('security-url-check');
        urlCheck?.addEventListener('click', () => {
            const url = (document.getElementById('security-url-input') as HTMLInputElement)?.value.trim();
            if (url) this.ws.send({ type: 'security:check_url', url });
        });

        const pingBtn = document.getElementById('security-ping-btn');
        pingBtn?.addEventListener('click', () => {
            const host = (document.getElementById('security-ping-input') as HTMLInputElement)?.value.trim();
            if (host) this.ws.send({ type: 'security:ping', host });
        });
    }

    private bindSync(): void {
        const syncBtn = document.getElementById('security-sync-btn');
        syncBtn?.addEventListener('click', () => {
            this.ws.send({ type: 'security:sync_start' });
            this.notify.show('局域网同步已启动', 'info');
        });
    }

    private bindBackup(): void {
        const exportBtn = document.getElementById('security-backup-export');
        exportBtn?.addEventListener('click', () => {
            this.ws.send({ type: 'security:backup_export' });
            this.notify.show('正在导出配置备份...', 'info');
        });

        const importBtn = document.getElementById('security-backup-import');
        importBtn?.addEventListener('click', () => {
            this.notify.show('配置备份导入功能开发中', 'info');
        });
    }

    private renderScanResults(results: Array<Record<string, unknown>>): void {
        const container = document.getElementById('security-scan-results');
        if (!container) return;
        if (!results || results.length === 0) {
            container.innerHTML = '<div class="empty-state">未发现开放端口</div>';
            return;
        }
        container.innerHTML = results.map(r => `
            <div class="scan-result-item">
                <span class="scan-port">${r.port}</span>
                <span class="scan-state ${r.state === 'open' ? 'scan-open' : 'scan-closed'}">${r.state || 'unknown'}</span>
                <span class="scan-service">${r.service || ''}</span>
            </div>
        `).join('');
    }

    private appendLog(text: string): void {
        const log = document.getElementById('security-net-log');
        if (!log) return;
        const line = document.createElement('div');
        line.className = 'security-log-line';
        line.textContent = text;
        log.appendChild(line);
        log.scrollTop = log.scrollHeight;
    }

    private setScanOutput(text: string): void {
        const container = document.getElementById('security-scan-results');
        if (container) container.innerHTML = `<div class="empty-state">${text}</div>`;
    }

    private updateSyncStatus(data: Record<string, unknown>): void {
        const indicator = document.getElementById('security-sync-status');
        if (indicator) {
            const syncing = data.syncing as boolean;
            indicator.className = `sync-indicator ${syncing ? 'syncing' : 'idle'}`;
            indicator.textContent = syncing ? '同步中...' : '空闲';
        }
    }
}
