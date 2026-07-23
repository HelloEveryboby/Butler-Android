import type { ButlerAPI, VaultItem } from '../services/api';
import type { NotificationManager } from '../services/notification';
import { escapeHtml } from '../utils';

// SecretVault - encrypted secret management connected to secret_vault core
export class SecretVault {
    private api: ButlerAPI;
    private notify: NotificationManager;

    constructor(api: ButlerAPI, notify: NotificationManager) {
        this.api = api;
        this.notify = notify;
    }

    init(): void {
        this.bindAdd();
        this.loadVault();
    }

    private bindAdd(): void {
        const form = document.getElementById('vault-add-form');
        if (!form) return;
        form.addEventListener('submit', (e) => {
            e.preventDefault();
            const key = (document.getElementById('vault-key') as HTMLInputElement)?.value.trim();
            const value = (document.getElementById('vault-value') as HTMLInputElement)?.value;
            const category = (document.getElementById('vault-category') as HTMLSelectElement)?.value;
            if (!key || !value) return;

            this.api.putVault(key, value, category);
            this.notify.show('密钥已保存', 'success');
            // Clear form
            (document.getElementById('vault-key') as HTMLInputElement).value = '';
            (document.getElementById('vault-value') as HTMLInputElement).value = '';
            setTimeout(() => this.loadVault(), 500);
        });
    }

    private async loadVault(): Promise<void> {
        const items = await this.api.getVault();
        this.renderVault(items);
    }

    private renderVault(items: VaultItem[]): void {
        const list = document.getElementById('vault-list');
        if (!list) return;

        if (items.length === 0) {
            list.innerHTML = '<div class="empty-state"><i class="fas fa-lock"></i><p>暂无存储的密钥</p></div>';
            return;
        }

        list.innerHTML = items.map(item => `
            <div class="vault-item" data-key="${item.key}">
                <div class="vault-item-header">
                    <span class="vault-item-key"><i class="fas fa-key"></i> ${item.key}</span>
                    <span class="vault-item-category">${item.category || 'general'}</span>
                </div>
                <div class="vault-item-value">********</div>
                <button class="vault-item-delete" data-key="${item.key}" title="删除">
                    <i class="fas fa-trash-alt"></i>
                </button>
            </div>
        `).join('');

        list.querySelectorAll('.vault-item-delete').forEach(btn => {
            btn.addEventListener('click', () => {
                const key = btn.getAttribute('data-key');
                if (key) {
                    this.api.deleteVault(key);
                    this.notify.show('密钥已删除', 'success');
                    setTimeout(() => this.loadVault(), 500);
                }
            });
        });
    }
}
