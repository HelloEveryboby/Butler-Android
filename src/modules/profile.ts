import type { ButlerAPI } from '../services/api';
import type { WebSocketService } from '../services/websocket';
import type { NotificationManager } from '../services/notification';
import { escapeHtml } from '../utils';

// UserProfile - habit tracking and user profile connected to habit_manager
export class UserProfile {
    private api: ButlerAPI;
    private ws: WebSocketService;
    private notify: NotificationManager;

    constructor(api: ButlerAPI, ws: WebSocketService, notify: NotificationManager) {
        this.api = api;
        this.ws = ws;
        this.notify = notify;
    }

    init(): void {
        this.bindUpdate();
        this.loadProfile();
    }

    private bindUpdate(): void {
        const btn = document.getElementById('profile-save-btn');
        btn?.addEventListener('click', () => {
            const habits: Record<string, unknown> = {};
            document.querySelectorAll('.profile-habit-item').forEach(item => {
                const key = item.getAttribute('data-key');
                const value = (item.querySelector('.profile-habit-value') as HTMLInputElement)?.value;
                if (key && value) habits[key] = value;
            });
            this.api.updateProfile(habits);
            this.notify.show('习惯已更新', 'success');
        });
    }

    private async loadProfile(): Promise<void> {
        const profile = await this.api.getProfile();
        this.renderProfile(profile);
    }

    private renderProfile(profile: Record<string, unknown>): void {
        const container = document.getElementById('profile-habits');
        if (!container) return;

        const habits = (profile.habits || profile) as Record<string, unknown>;
        const entries = Object.entries(habits).filter(([key]) => !['id', 'created_at', 'updated_at'].includes(key));

        if (entries.length === 0) {
            container.innerHTML = '<div class="empty-state">暂无习惯记录</div>';
            return;
        }

        container.innerHTML = entries.map(([key, value]) => `
            <div class="profile-habit-item" data-key="${key}">
                <span class="profile-habit-key">${key}</span>
                <input class="profile-habit-value" type="text" value="${value}" />
            </div>
        `).join('');
    }
}
