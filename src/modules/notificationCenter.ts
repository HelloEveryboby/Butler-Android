import type { ButlerAPI } from '../services/api';
import type { WebSocketService } from '../services/websocket';
import type { NotificationManager } from '../services/notification';

// NotificationCenter - notifier + proactive_agent + self_healing
// Aggregates: notifier_system + proactive_agent + self_healing
export class NotificationCenter {
    private api: ButlerAPI;
    private ws: WebSocketService;
    private notify: NotificationManager;

    constructor(api: ButlerAPI, ws: WebSocketService, notify: NotificationManager) {
        this.api = api;
        this.ws = ws;
        this.notify = notify;
    }

    init(): void {
        this.bindDismiss();
        this.loadNotifications();
        this.listenWsResponses();
    }

    private listenWsResponses(): void {
        this.ws.on('message', (data: Record<string, unknown>) => {
            const msg = data as { type: string; data?: unknown };
            if (msg.type === 'notifications:list') {
                this.renderNotifications(msg.data as Array<Record<string, unknown>>);
            }
            if (msg.type === 'proactive:suggestion') {
                this.renderSuggestion(msg.data as Record<string, unknown>);
            }
            if (msg.type === 'self_healing:report') {
                this.renderHealingReport(msg.data as Record<string, unknown>);
            }
        });
    }

    private bindDismiss(): void {
        // Delegate to container
        const container = document.getElementById('notify-list');
        if (!container) return;
        container.addEventListener('click', (e) => {
            const target = e.target as HTMLElement;
            const dismissBtn = target.closest('.notify-dismiss');
            if (dismissBtn) {
                const id = dismissBtn.getAttribute('data-id');
                const item = dismissBtn.closest('.notify-item');
                item?.remove();
                this.ws.send({ type: 'notification:dismiss', id });
            }
        });
    }

    private loadNotifications(): void {
        this.ws.send({ type: 'notifications:get' });
        this.ws.send({ type: 'proactive:get' });
        this.ws.send({ type: 'self_healing:get' });
    }

    private renderNotifications(items: Array<Record<string, unknown>>): void {
        const container = document.getElementById('notify-list');
        if (!container) return;
        if (!items || items.length === 0) {
            container.innerHTML = '<div class="memory-empty-small">暂无通知</div>';
            return;
        }
        container.innerHTML = items.map(item => {
            const priority = item.priority as string || 'normal';
            return `
                <div class="notify-item notify-${priority}" data-id="${item.id || ''}">
                    <div class="notify-header">
                        <span class="notify-title">${this.escapeHtml(String(item.title || ''))}</span>
                        <button class="notify-dismiss" data-id="${item.id}"><i class="fas fa-times"></i></button>
                    </div>
                    <div class="notify-body">${this.escapeHtml(String(item.content || ''))}</div>
                    <div class="notify-meta">
                        ${item.source ? `<span>来源: ${item.source}</span>` : ''}
                        ${item.created_at ? `<span>${item.created_at}</span>` : ''}
                    </div>
                </div>
            `;
        }).join('');
    }

    private renderSuggestion(data: Record<string, unknown>): void {
        const container = document.getElementById('notify-proactive');
        if (!container) return;
        if (!data || !data.message) {
            container.innerHTML = '<div class="memory-empty-small">暂无建议</div>';
            return;
        }
        container.innerHTML = `
            <div class="notify-suggestion">
                <i class="fas fa-lightbulb"></i>
                <span>${this.escapeHtml(String(data.message))}</span>
            </div>
        `;
    }

    private renderHealingReport(data: Record<string, unknown>): void {
        const container = document.getElementById('notify-healing');
        if (!container) return;
        const issues = data.issues as Array<Record<string, unknown>> | undefined;
        if (!issues || issues.length === 0) {
            container.innerHTML = '<div class="healing-ok"><i class="fas fa-check-circle"></i> 系统运行正常</div>';
            return;
        }
        container.innerHTML = issues.map(i => `
            <div class="healing-item">
                <span class="healing-strategy">${i.strategy || 'unknown'}</span>
                <span class="healing-message">${this.escapeHtml(String(i.message || i.error || ''))}</span>
            </div>
        `).join('');
    }

    private escapeHtml(text: string): string {
        const div = document.createElement('div');
        div.textContent = text;
        return div.innerHTML;
    }
}
