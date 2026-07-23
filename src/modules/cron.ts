import type { ButlerAPI, CronJob } from '../services/api';
import type { WebSocketService } from '../services/websocket';
import type { NotificationManager } from '../services/notification';
import { escapeHtml } from '../utils';

// CronScheduler - cron job management connected to cron_scheduler core
export class CronScheduler {
    private api: ButlerAPI;
    private ws: WebSocketService;
    private notify: NotificationManager;

    constructor(api: ButlerAPI, ws: WebSocketService, notify: NotificationManager) {
        this.api = api;
        this.ws = ws;
        this.notify = notify;
    }

    init(): void {
        this.bindAdd();
        this.loadJobs();
    }

    private bindAdd(): void {
        const form = document.getElementById('cron-add-form');
        if (!form) return;
        form.addEventListener('submit', (e) => {
            e.preventDefault();
            const name = (document.getElementById('cron-name') as HTMLInputElement)?.value.trim();
            const cronExpr = (document.getElementById('cron-expr') as HTMLInputElement)?.value.trim();
            const action = (document.getElementById('cron-action') as HTMLInputElement)?.value.trim();
            if (!name || !cronExpr || !action) return;

            this.api.addCronJob({ name, cron_expr: cronExpr, action, enabled: true });
            this.notify.show(`定时任务 "${name}" 已添加`, 'success');
            (document.getElementById('cron-name') as HTMLInputElement).value = '';
            (document.getElementById('cron-expr') as HTMLInputElement).value = '';
            (document.getElementById('cron-action') as HTMLInputElement).value = '';
            setTimeout(() => this.loadJobs(), 500);
        });
    }

    private async loadJobs(): Promise<void> {
        const jobs = await this.api.getCronJobs();
        this.renderJobs(jobs);
    }

    private renderJobs(jobs: CronJob[]): void {
        const list = document.getElementById('cron-jobs');
        if (!list) return;

        if (jobs.length === 0) {
            list.innerHTML = '<div class="empty-state"><i class="fas fa-clock"></i><p>暂无定时任务</p></div>';
            return;
        }

        list.innerHTML = jobs.map(j => `
            <div class="cron-item" data-id="${j.id}">
                <div class="cron-item-header">
                    <span class="cron-item-name">${j.name}</span>
                    <span class="cron-item-enabled">${j.enabled ? '<i class="fas fa-check-circle"></i> 启用' : '<i class="fas fa-times-circle"></i> 禁用'}</span>
                </div>
                <div class="cron-item-info">
                    <code>${j.cron_expr}</code>
                    <span class="cron-item-action">${j.action}</span>
                </div>
                ${j.last_run ? `<div class="cron-item-last">上次运行: ${j.last_run}</div>` : ''}
                <button class="cron-item-delete" data-id="${j.id}" title="删除"><i class="fas fa-trash-alt"></i></button>
            </div>
        `).join('');

        list.querySelectorAll('.cron-item-delete').forEach(btn => {
            btn.addEventListener('click', () => {
                const id = btn.getAttribute('data-id');
                if (id) {
                    this.api.removeCronJob(id);
                    this.notify.show('定时任务已删除', 'success');
                    setTimeout(() => this.loadJobs(), 500);
                }
            });
        });
    }
}
