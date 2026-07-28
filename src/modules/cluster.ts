import type { ButlerAPI } from '../services/api';
import type { WebSocketService } from '../services/websocket';
import type { NotificationManager } from '../services/notification';
import { escapeHtml } from '../utils';

// ClusterView - distributed node management connected to cluster_manager
export class ClusterView {
    private api: ButlerAPI;
    private ws: WebSocketService;
    private notify: NotificationManager;

    constructor(api: ButlerAPI, ws: WebSocketService, notify: NotificationManager) {
        this.api = api;
        this.ws = ws;
        this.notify = notify;
    }

    init(): void {
        this.bindRefresh();
        this.bindHealthCheck();
        this.listenWsResponses();
        this.loadNodes();
    }

    private listenWsResponses(): void {
        this.ws.on('message', (data: Record<string, unknown>) => {
            const msg = data as { type: string; data?: unknown };
            if (msg.type === 'cluster:nodes') {
                this.renderNodes(msg.data as Array<Record<string, unknown>>);
            }
            if (msg.type === 'cluster:health') {
                this.updateHealth(msg.data as Record<string, unknown>);
            }
        });
    }

    private bindRefresh(): void {
        const btn = document.getElementById('cluster-refresh-btn');
        btn?.addEventListener('click', () => {
            this.api.getClusterNodes();
            this.notify.show('刷新节点列表...', 'info');
        });
    }

    private bindHealthCheck(): void {
        const btn = document.getElementById('cluster-health-btn');
        btn?.addEventListener('click', () => {
            this.api.clusterHealthCheck();
            this.notify.show('执行健康检查...', 'info');
        });
    }

    private loadNodes(): void {
        this.api.getClusterNodes();
    }

    private renderNodes(nodes: Array<Record<string, unknown>> | null): void {
        const container = document.getElementById('cluster-nodes');
        if (!container) return;

        if (!nodes || nodes.length === 0) {
            container.innerHTML = '<div class="empty-state"><i class="fas fa-server"></i><p>无集群节点</p></div>';
            return;
        }

        container.innerHTML = nodes.map(n => `
            <div class="cluster-node">
                <div class="cluster-node-header">
                    <span class="cluster-node-name">${n.name || n.id || '未知节点'}</span>
                    <span class="cluster-node-status cluster-${n.status || 'unknown'}">${n.status || 'unknown'}</span>
                </div>
                <div class="cluster-node-info">
                    ${n.ip ? `<span><i class="fas fa-network-wired"></i> ${n.ip}</span>` : ''}
                    ${n.role ? `<span><i class="fas fa-tag"></i> ${n.role}</span>` : ''}
                    ${n.uptime ? `<span><i class="fas fa-clock"></i> ${n.uptime}</span>` : ''}
                </div>
            </div>
        `).join('');
    }

    private updateHealth(data: Record<string, unknown>): void {
        const indicator = document.getElementById('cluster-health-indicator');
        if (!indicator) return;
        const healthy = data.healthy as boolean;
        indicator.className = `cluster-health-indicator ${healthy ? 'healthy' : 'unhealthy'}`;
        indicator.textContent = healthy ? '健康' : '异常';
    }
}
