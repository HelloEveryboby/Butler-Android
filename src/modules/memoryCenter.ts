import type { ButlerAPI } from '../services/api';
import type { WebSocketService } from '../services/websocket';
import type { NotificationManager } from '../services/notification';

// MemoryCenter - memory engine + review engine + dream engine + memos search
// Aggregates: memory_engine + review_engine + dream_engine + memos
export class MemoryCenter {
    private api: ButlerAPI;
    private ws: WebSocketService;
    private notify: NotificationManager;

    constructor(api: ButlerAPI, ws: WebSocketService, notify: NotificationManager) {
        this.api = api;
        this.ws = ws;
        this.notify = notify;
    }

    init(): void {
        this.bindSearch();
        this.bindReview();
        this.loadDueReviews();
        this.listenWsResponses();
    }

    private listenWsResponses(): void {
        this.ws.on('message', (data: Record<string, unknown>) => {
            const msg = data as { type: string; data?: unknown };
            if (msg.type === 'memory:search') {
                this.renderSearchResults(msg.data as Array<Record<string, unknown>>);
            }
            if (msg.type === 'memory:due') {
                this.renderDueReviews(msg.data as Array<Record<string, unknown>>);
            }
            if (msg.type === 'dream:status') {
                this.renderDreamStatus(msg.data as Record<string, unknown>);
            }
        });
    }

    private bindSearch(): void {
        const form = document.getElementById('memory-search-form');
        if (!form) return;
        form.addEventListener('submit', (e) => {
            e.preventDefault();
            const query = (document.getElementById('memory-search-input') as HTMLInputElement).value.trim();
            if (!query) return;
            this.ws.send({ type: 'memory:search', query });
        });
    }

    private bindReview(): void {
        const btn = document.getElementById('memory-review-btn');
        btn?.addEventListener('click', () => {
            this.ws.send({ type: 'memory:mark_reviewed' });
            this.notify.show('已标记为已复习', 'success');
            setTimeout(() => this.loadDueReviews(), 500);
        });
    }

    private loadDueReviews(): void {
        this.ws.send({ type: 'memory:get_due' });
        this.ws.send({ type: 'dream:get_status' });
    }

    private renderSearchResults(results: Array<Record<string, unknown>>): void {
        const container = document.getElementById('memory-search-results');
        if (!container) return;
        if (!results || results.length === 0) {
            container.innerHTML = '<div class="memory-empty-small">未找到相关记忆</div>';
            return;
        }
        container.innerHTML = results.map(r => `
            <div class="memory-result-item">
                <div class="memory-result-content">${this.escapeHtml(String(r.content || r.text || ''))}</div>
                <div class="memory-result-meta">
                    ${r.source ? `<span class="memory-result-source">来源: ${r.source}</span>` : ''}
                    ${r.score ? `<span class="memory-result-score">相似度: ${(r.score as number * 100).toFixed(0)}%</span>` : ''}
                    ${r.created_at ? `<span class="memory-result-time">${r.created_at}</span>` : ''}
                </div>
            </div>
        `).join('');
    }

    private renderDueReviews(items: Array<Record<string, unknown>>): void {
        const container = document.getElementById('memory-due-reviews');
        if (!container) return;
        if (!items || items.length === 0) {
            container.innerHTML = '<div class="memory-empty-small">暂无待复习内容</div>';
            return;
        }
        container.innerHTML = `<div class="memory-due-count">${items.length} 项待复习</div>` +
            items.map(item => `
                <div class="memory-due-item">
                    <span class="memory-due-content">${this.escapeHtml(String(item.content || ''))}</span>
                    <span class="memory-due-stage">阶段 ${item.stage || 1}</span>
                </div>
            `).join('');
    }

    private renderDreamStatus(status: Record<string, unknown>): void {
        const container = document.getElementById('memory-dream-status');
        if (!container) return;
        const canDream = status.can_dream as boolean;
        const lastDream = status.last_dream as string;
        container.innerHTML = `
            <div class="dream-status-item">
                <span class="dream-label">做梦引擎</span>
                <span class="dream-value ${canDream ? 'dream-ready' : 'dream-waiting'}">
                    ${canDream ? '可执行' : '等待中'}
                </span>
            </div>
            ${lastDream ? `<div class="dream-status-item"><span class="dream-label">上次做梦</span><span class="dream-value">${lastDream}</span></div>` : ''}
        `;
    }

    private escapeHtml(text: string): string {
        const div = document.createElement('div');
        div.textContent = text;
        return div.innerHTML;
    }
}
