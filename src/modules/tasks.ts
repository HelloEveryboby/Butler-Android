import type { ButlerAPI, TaskItem } from '../services/api';
import type { NotificationManager } from '../services/notification';

// TasksBoard - task management kanban board connected to backend task_manager
export class TasksBoard {
    private api: ButlerAPI;
    private notify: NotificationManager;

    constructor(api: ButlerAPI, notify: NotificationManager) {
        this.api = api;
        this.notify = notify;
    }

    init(): void {
        this.bindAddTask();
        this.loadTasks();
    }

    private bindAddTask(): void {
        const form = document.getElementById('task-add-form');
        if (!form) return;
        form.addEventListener('submit', async (e) => {
            e.preventDefault();
            const input = document.getElementById('task-input') as HTMLInputElement;
            const title = input?.value.trim();
            if (!title) return;

            const ok = await this.api.addTask({ title, status: 'pending' });
            if (ok) {
                input.value = '';
                this.notify.show('任务已添加', 'success');
                setTimeout(() => this.loadTasks(), 300);
            }
        });
    }

    private async loadTasks(): Promise<void> {
        const tasks = await this.api.getTasks();
        this.renderTasks(tasks);
    }

    private renderTasks(tasks: TaskItem[]): void {
        const columns: Record<string, TaskItem[]> = {
            pending: [],
            running: [],
            completed: [],
            failed: [],
        };

        for (const t of tasks) {
            const col = columns[t.status] || columns.pending;
            col.push(t);
        }

        for (const [status, items] of Object.entries(columns)) {
            const container = document.getElementById(`task-col-${status}`);
            if (!container) continue;
            if (items.length === 0) {
                container.innerHTML = '<div class="task-empty">暂无任务</div>';
                continue;
            }
            container.innerHTML = items.map(t => `
                <div class="task-card" data-id="${t.id}">
                    <div class="task-card-title">${this.escapeHtml(t.title)}</div>
                    <div class="task-card-meta">
                        ${t.priority ? `<span class="task-priority task-priority-${t.priority}">${t.priority}</span>` : ''}
                        ${t.tags?.length ? `<span class="task-tags">${t.tags.map(tag => `#${tag}`).join(' ')}</span>` : ''}
                    </div>
                    <div class="task-card-actions">
                        ${t.status === 'pending' ? `<button class="task-btn" data-action="start" data-id="${t.id}"><i class="fas fa-play"></i></button>` : ''}
                        ${t.status === 'running' ? `<button class="task-btn" data-action="complete" data-id="${t.id}"><i class="fas fa-check"></i></button>` : ''}
                        <button class="task-btn task-btn-danger" data-action="delete" data-id="${t.id}"><i class="fas fa-trash"></i></button>
                    </div>
                </div>
            `).join('');

            // Bind actions
            container.querySelectorAll('.task-btn').forEach(btn => {
                btn.addEventListener('click', async () => {
                    const action = btn.getAttribute('data-action');
                    const id = btn.getAttribute('data-id');
                    if (!id) return;
                    if (action === 'delete') {
                        await this.api.deleteTask(id);
                        this.notify.show('任务已删除', 'success');
                    }
                    setTimeout(() => this.loadTasks(), 300);
                });
            });
        }
    }

    private escapeHtml(text: string): string {
        const div = document.createElement('div');
        div.textContent = text;
        return div.innerHTML;
    }
}
