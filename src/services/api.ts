import type { WebSocketService } from './websocket';

// ButlerAPI - REST + WebSocket API client
export class ButlerAPI {
    private ws: WebSocketService;
    private baseUrl: string = 'http://localhost:8080/api';

    constructor(ws: WebSocketService) {
        this.ws = ws;
    }

    // ── Chat ──────────────────────────────────────────────────────
    async chat(message: string, stream = true): Promise<void> {
        this.ws.send({ type: 'chat', message, stream });
    }

    // ── Skills ───────────────────────────────────────────────────
    async getSkills(): Promise<SkillItem[]> {
        try {
            const res = await fetch(`${this.baseUrl}/skills`);
            return await res.json();
        } catch {
            return [];
        }
    }

    async runSkill(skillId: string, params?: Record<string, unknown>): Promise<void> {
        this.ws.send({ type: 'skill:run', skillId, params });
    }

    // ── Settings ─────────────────────────────────────────────────
    async getSettings(): Promise<Record<string, unknown>> {
        try {
            const res = await fetch(`${this.baseUrl}/settings`);
            return await res.json();
        } catch {
            return {};
        }
    }

    async saveSettings(settings: Record<string, unknown>): Promise<boolean> {
        try {
            const res = await fetch(`${this.baseUrl}/settings`, {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(settings),
            });
            return res.ok;
        } catch {
            return false;
        }
    }

    // ── Memos ────────────────────────────────────────────────────
    async getMemos(limit = 20, offset = 0): Promise<MemoItem[]> {
        try {
            const res = await fetch(`${this.baseUrl}/memos?limit=${limit}&offset=${offset}`);
            return await res.json();
        } catch {
            return [];
        }
    }

    async saveMemo(content: string, tags: string[] = []): Promise<boolean> {
        try {
            const res = await fetch(`${this.baseUrl}/memos`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ content, tags }),
            });
            return res.ok;
        } catch {
            return false;
        }
    }

    // ── Terminal ─────────────────────────────────────────────────
    async runTerminal(command: string): Promise<void> {
        this.ws.send({ type: 'terminal', command });
    }

    // ── Tasks ───────────────────────────────────────────────────
    async getTasks(): Promise<TaskItem[]> {
        try {
            const res = await fetch(`${this.baseUrl}/tasks`);
            return await res.json();
        } catch {
            return [];
        }
    }

    async addTask(task: Partial<TaskItem>): Promise<boolean> {
        try {
            const res = await fetch(`${this.baseUrl}/tasks`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(task),
            });
            return res.ok;
        } catch {
            return false;
        }
    }

    async deleteTask(id: string): Promise<boolean> {
        try {
            const res = await fetch(`${this.baseUrl}/tasks/${id}`, { method: 'DELETE' });
            return res.ok;
        } catch {
            return false;
        }
    }

    // ── Vault (Secret Vault) ────────────────────────────────────
    async getVault(): Promise<VaultItem[]> {
        try {
            const res = await fetch(`${this.baseUrl}/vault`);
            return await res.json();
        } catch {
            return [];
        }
    }

    async putVault(key: string, value: string, category = 'general'): Promise<void> {
        this.ws.send({ type: 'vault:put', params: { key, value, category } });
    }

    async deleteVault(key: string): Promise<void> {
        this.ws.send({ type: 'vault:delete', key });
    }

    // ── Focus Mode ──────────────────────────────────────────────
    async getFocusStatus(): Promise<Record<string, unknown>> {
        try {
            const res = await fetch(`${this.baseUrl}/focus`);
            return await res.json();
        } catch {
            return {};
        }
    }

    async startFocus(duration = 25): Promise<void> {
        this.ws.send({ type: 'focus:start', duration });
    }

    async stopFocus(): Promise<void> {
        this.ws.send({ type: 'focus:stop' });
    }

    // ── Cron Scheduler ───────────────────────────────────────────
    async getCronJobs(): Promise<CronJob[]> {
        try {
            const res = await fetch(`${this.baseUrl}/cron`);
            return await res.json();
        } catch {
            return [];
        }
    }

    async addCronJob(job: Partial<CronJob>): Promise<void> {
        this.ws.send({ type: 'cron:add', params: job });
    }

    async removeCronJob(jobId: string): Promise<void> {
        this.ws.send({ type: 'cron:remove', jobId });
    }

    // ── Workflow Engine ───────────────────────────────────────────
    async createWorkflow(name: string, steps: WorkflowStep[]): Promise<void> {
        this.ws.send({ type: 'workflow:create', name, steps });
    }

    async executeWorkflow(workflowId: string): Promise<void> {
        this.ws.send({ type: 'workflow:execute', workflowId });
    }

    // ── Time Machine ────────────────────────────────────────────
    async getSnapshotAt(timestamp: number): Promise<void> {
        this.ws.send({ type: 'time_machine:snapshot', timestamp });
    }

    async getRange(start: number, end: number): Promise<void> {
        this.ws.send({ type: 'time_machine:range', start, end });
    }

    // ── Voice Service ───────────────────────────────────────────
    async startVoice(): Promise<void> {
        this.ws.send({ type: 'voice:start' });
    }

    async stopVoice(): Promise<void> {
        this.ws.send({ type: 'voice:stop' });
    }

    // ── Code Interpreter ────────────────────────────────────────
    async runCode(code: string, language = 'python'): Promise<void> {
        this.ws.send({ type: 'code:run', code, language });
    }

    // ── Cluster ──────────────────────────────────────────────────
    async getClusterNodes(): Promise<void> {
        this.ws.send({ type: 'cluster:nodes' });
    }

    async clusterHealthCheck(): Promise<void> {
        this.ws.send({ type: 'cluster:health' });
    }

    // ── Profile / Habits ────────────────────────────────────────
    async getProfile(): Promise<Record<string, unknown>> {
        try {
            const res = await fetch(`${this.baseUrl}/profile`);
            return await res.json();
        } catch {
            return {};
        }
    }

    async updateProfile(habits: Record<string, unknown>): Promise<void> {
        this.ws.send({ type: 'profile:update', params: habits });
    }

    // ── System ───────────────────────────────────────────────────
    async getSystemInfo(): Promise<Record<string, unknown>> {
        try {
            const res = await fetch(`${this.baseUrl}/system`);
            return await res.json();
        } catch {
            return {};
        }
    }

    async getSystemStats(): Promise<void> {
        this.ws.send({ type: 'system:stats' });
    }

    async setSystemMode(mode: string): Promise<void> {
        this.ws.send({ type: 'system:mode', mode });
    }
}

// ── Type definitions ────────────────────────────────────────────

export interface SkillItem {
    id: string;
    name: string;
    icon: string;
    color: string;
    desc: string;
    version?: string;
    status?: string;
}

export interface MemoItem {
    id: string;
    content: string;
    tags: string[];
    time?: string;
    created_at?: string;
}

export interface TaskItem {
    id: string;
    title: string;
    status: 'pending' | 'running' | 'completed' | 'failed';
    priority?: 'low' | 'medium' | 'high';
    created_at?: string;
    tags?: string[];
}

export interface VaultItem {
    key: string;
    category?: string;
    created_at?: string;
    // value is masked
}

export interface CronJob {
    id: string;
    name: string;
    cron_expr: string;
    action: string;
    enabled: boolean;
    last_run?: string;
}

export interface WorkflowStep {
    id: string;
    intent: string;
    entities?: Record<string, unknown>;
    depends_on?: string[];
}
