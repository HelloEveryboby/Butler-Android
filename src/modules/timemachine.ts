import type { ButlerAPI } from '../services/api';
import type { WebSocketService } from '../services/websocket';

// TimeMachine - metrics dashboard and history timeline, connected to backend
export class TimeMachine {
    private api: ButlerAPI;
    private ws: WebSocketService;

    constructor(api: ButlerAPI, ws: WebSocketService) {
        this.api = api;
        this.ws = ws;
    }

    init(): void {
        this.renderMetrics();
        this.renderLogs();
        this.bindSlider();
        this.listenWsResponses();
        this.loadSystemInfo();
    }

    private listenWsResponses(): void {
        this.ws.on('message', (data: Record<string, unknown>) => {
            const msg = data as { type: string; data?: unknown };
            if (msg.type === 'system:stats') {
                this.updateMetrics(msg.data as Record<string, unknown>);
            }
            if (msg.type === 'tm:snapshot' || msg.type === 'tm:range') {
                this.updateLogs(msg.data as Array<{ time: string; msg: string; type: string }>);
            }
        });
    }

    private async loadSystemInfo(): Promise<void> {
        const info = await this.api.getSystemInfo();
        if (info && info.battery) {
            this.updateMetrics(info as Record<string, unknown>);
        }
    }

    private updateMetrics(stats: Record<string, unknown>): void {
        const grid = document.getElementById('tm-metrics');
        if (!grid) return;
        const battery = stats.battery as Record<string, unknown> | undefined;
        const metrics = [
            { label: 'CPU 占用', value: battery?.cpu || '--', icon: 'fa-microchip', color: '#34C759' },
            { label: '内存使用', value: battery?.memory || '--', icon: 'fa-memory', color: '#007AFF' },
            { label: '磁盘 I/O', value: battery?.disk_io || '--', icon: 'fa-hard-drive', color: '#FF9500' },
            { label: '网络流量', value: battery?.network || '--', icon: 'fa-wifi', color: '#AF52DE' },
        ];
        grid.innerHTML = metrics.map(m => `
            <div class="metric-card glass-surface">
                <div class="metric-icon" style="color:${m.color};"><i class="fas ${m.icon}"></i></div>
                <div class="metric-info">
                    <span class="metric-value">${m.value}</span>
                    <span class="metric-label">${m.label}</span>
                </div>
            </div>
        `).join('');
    }

    private renderMetrics(): void {
        // Show loading metrics initially, will be updated by loadSystemInfo
        this.updateMetrics({});
    }

    private updateLogs(entries: Array<{ time: string; msg: string; type: string }> | null): void {
        const logs = document.getElementById('tm-logs');
        if (!logs) return;
        if (entries && entries.length > 0) {
            logs.innerHTML = entries.map(e => `
                <div class="log-entry log-${e.type || 'info'}">
                    <span class="log-time">${e.time}</span>
                    <span class="log-msg">${e.msg}</span>
                </div>
            `).join('');
        }
    }

    private renderLogs(): void {
        // Show placeholder; real data comes via WS time_machine:snapshot/range
        const logs = document.getElementById('tm-logs');
        if (!logs) return;
        logs.innerHTML = '<div class="loading-hint">拖动时间轴查看历史快照</div>';
    }

    private bindSlider(): void {
        const slider = document.getElementById('tm-slider') as HTMLInputElement;
        const label = document.querySelector('.tm-time-label');
        if (!slider || !label) return;
        slider.addEventListener('input', () => {
            const v = parseInt(slider.value);
            if (v === 100) {
                label.textContent = '现在';
                // Load current snapshot
                this.api.getSnapshotAt(Date.now() / 1000);
            } else {
                const mins = Math.round((100 - v) * 0.6);
                label.textContent = `${mins}分钟前`;
                // Request historical snapshot
                const ts = (Date.now() / 1000) - (mins * 60);
                this.api.getSnapshotAt(ts);
            }
        });
    }
}
