import type { ButlerAPI, MemoItem } from '../services/api';
import type { NotificationManager } from '../services/notification';

// MemosManager - memos overlay with search and CRUD from backend
export class MemosManager {
    private api: ButlerAPI;
    private notify: NotificationManager;

    constructor(api: ButlerAPI, notify: NotificationManager) {
        this.api = api;
        this.notify = notify;
    }

    init(): void {
        this.bindToggle();
        this.loadMemos();
    }

    private bindToggle(): void {
        const toggle = document.getElementById('memos-toggle');
        if (!toggle) return;
        toggle.addEventListener('click', () => {
            const overlay = document.getElementById('memos-overlay');
            if (overlay) {
                overlay.classList.toggle('hidden');
                // Reload memos each time overlay opens
                if (!overlay.classList.contains('hidden')) {
                    this.loadMemos();
                }
            }
        });
        const newBtn = document.getElementById('new-memo-btn');
        if (newBtn) {
            newBtn.addEventListener('click', () => this.handleNewMemo());
        }
    }

    private async loadMemos(): Promise<void> {
        const list = document.getElementById('memos-list');
        if (!list) return;

        list.innerHTML = '<div class="loading-hint">加载备忘录...</div>';

        const memos = await this.api.getMemos(20, 0);
        if (memos && memos.length > 0) {
            this.renderMemos(memos);
        } else {
            list.innerHTML = '<div class="loading-hint">暂无备忘录</div>';
        }
    }

    private renderMemos(memos: MemoItem[]): void {
        const list = document.getElementById('memos-list');
        if (!list) return;
        list.innerHTML = memos.map(m => {
            const time = m.time || (m.created_at ? this.formatTime(m.created_at) : '');
            const tags = m.tags || [];
            return `
                <div class="memo-item" data-id="${m.id}">
                    <div class="memo-content">${this.escapeHtml(m.content)}</div>
                    <div class="memo-meta">
                        <div class="memo-tags">${tags.map(t => `<span class="memo-tag">#${t}</span>`).join('')}</div>
                        ${time ? `<span class="memo-time">${time}</span>` : ''}
                    </div>
                </div>
            `;
        }).join('');
    }

    private handleNewMemo(): void {
        // Try to find a memo input, otherwise show a prompt-style input
        const input = document.getElementById('memo-input') as HTMLTextAreaElement;
        if (input) {
            const content = input.value.trim();
            if (content) {
                this.api.saveMemo(content, ['新标签']);
                input.value = '';
                this.notify.show('备忘录已保存', 'success');
                setTimeout(() => this.loadMemos(), 500);
            } else {
                this.notify.show('请输入备忘录内容', 'info');
            }
        } else {
            // No dedicated input, create inline input
            this.createInlineInput();
        }
    }

    private createInlineInput(): void {
        const list = document.getElementById('memos-list');
        if (!list) return;

        // Check if input already exists
        if (document.getElementById('memo-input')) return;

        const wrapper = document.createElement('div');
        wrapper.className = 'memo-new-form';
        wrapper.innerHTML = `
            <textarea id="memo-input" placeholder="输入备忘录内容..." rows="3"></textarea>
            <div class="memo-form-actions">
                <input id="memo-tags-input" placeholder="标签(逗号分隔)" />
                <button id="memo-save-btn" class="btn-primary">保存</button>
            </div>
        `;
        list.prepend(wrapper);

        const saveBtn = wrapper.querySelector('#memo-save-btn');
        saveBtn?.addEventListener('click', () => {
            const content = (document.getElementById('memo-input') as HTMLTextAreaElement)?.value.trim();
            const tagsRaw = (document.getElementById('memo-tags-input') as HTMLInputElement)?.value;
            const tags = tagsRaw ? tagsRaw.split(',').map(t => t.trim()).filter(Boolean) : [];
            if (content) {
                this.api.saveMemo(content, tags);
                wrapper.remove();
                this.notify.show('备忘录已保存', 'success');
                setTimeout(() => this.loadMemos(), 500);
            }
        });
    }

    private formatTime(iso: string): string {
        try {
            const d = new Date(iso);
            const now = new Date();
            const diff = now.getTime() - d.getTime();
            const mins = Math.floor(diff / 60000);
            if (mins < 1) return '刚刚';
            if (mins < 60) return `${mins}分钟前`;
            const hrs = Math.floor(mins / 60);
            if (hrs < 24) return `${hrs}小时前`;
            const days = Math.floor(hrs / 24);
            if (days < 7) return `${days}天前`;
            return iso.split('T')[0];
        } catch {
            return iso;
        }
    }

    private escapeHtml(text: string): string {
        const div = document.createElement('div');
        div.textContent = text;
        return div.innerHTML;
    }
}
