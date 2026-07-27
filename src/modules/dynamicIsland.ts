import DynamicIsland from '../services/dynamicIsland';
import type { ButlerAPI } from '../services/api';
import type { WebSocketService } from '../services/websocket';
import type { NotificationManager } from '../services/notification';
import { getWakeIslandSettings } from './settings';

// DynamicIslandController - manages Dynamic Island display across all modules
// Shows compact pill on skill execution, expands on tap with details
// All display behavior is configurable from the settings panel
export class DynamicIslandController {
    private api: ButlerAPI;
    private ws: WebSocketService;
    private notify: NotificationManager;
    private available: boolean = false;
    private permissionChecked: boolean = false;

    constructor(api: ButlerAPI, ws: WebSocketService, notify: NotificationManager) {
        this.api = api;
        this.ws = ws;
        this.notify = notify;
    }

    async init(): Promise<void> {
        if (typeof window === 'undefined' || !('Capacitor' in window)) {
            return;
        }

        // Check if island is enabled in settings
        if (!getWakeIslandSettings().islandEnabled) {
            return;
        }

        try {
            const result = await DynamicIsland.checkPermission();
            if (!result.granted) {
                this.available = false;
                return;
            }
            this.available = true;
            this.permissionChecked = true;
        } catch {
            this.available = false;
            return;
        }

        this.bindAutoTriggers();
    }

    async requestPermission(): Promise<boolean> {
        try {
            const result = await DynamicIsland.requestPermission();
            if (result.granted) {
                this.available = true;
                this.permissionChecked = true;
                this.bindAutoTriggers();
            }
            return result.granted;
        } catch {
            return false;
        }
    }

    isAvailable(): boolean {
        return this.available;
    }

    // ── Manual Control ────────────────────────────────────────────

    async showCompact(icon: string, title: string, content: string): Promise<void> {
        if (!this.available) return;
        if (!getWakeIslandSettings().islandEnabled) return;
        try {
            await DynamicIsland.showCompact({ icon, title, content });
        } catch {
            // Silently fail if plugin not available
        }
    }

    async expand(): Promise<void> {
        if (!this.available) return;
        if (!getWakeIslandSettings().islandEnabled) return;
        try { await DynamicIsland.expand(); } catch {}
    }

    async update(title: string, content: string, icon?: string): Promise<void> {
        if (!this.available) return;
        if (!getWakeIslandSettings().islandEnabled) return;
        try { await DynamicIsland.update({ title, content, icon }); } catch {}
    }

    async hide(): Promise<void> {
        if (!this.available) return;
        try { await DynamicIsland.hide(); } catch {}
    }

    // ── Auto Triggers ─────────────────────────────────────────────

    private bindAutoTriggers(): void {
        this.ws.on('message', (data: Record<string, unknown>) => {
            const msg = data as { type: string; data?: unknown; status?: string; skillId?: string };
            const s = getWakeIslandSettings();

            // Master switch
            if (!s.islandEnabled) return;

            switch (msg.type) {
                case 'chat:response':
                    if (s.islandChat) {
                        this.showCompact('info', 'Butler', '回复已生成');
                        this.autoHide();
                    }
                    break;

                case 'skill:run':
                    if (s.islandSkill) {
                        this.showCompact('processing', '技能执行中', msg.skillId || '');
                    }
                    break;

                case 'skill:result':
                    if (s.islandSkill) {
                        const success = msg.status === 'success';
                        this.update(
                            success ? '完成' : '失败',
                            String(msg.skillId || ''),
                            success ? 'success' : 'error'
                        );
                        this.autoHide();
                    }
                    break;

                case 'terminal:output':
                    if (s.islandTerminal) {
                        this.showCompact('info', '终端', '命令执行完成');
                        this.autoHide();
                    }
                    break;

                case 'music:status':
                    if (s.islandMusic) {
                        const playing = (msg.data as Record<string, unknown>)?.playing;
                        if (playing) {
                            this.showCompact('music', '正在播放', '');
                        } else {
                            this.hide();
                        }
                    }
                    break;

                case 'focus:status':
                    if (s.islandFocus) {
                        this.showCompact('processing', '专注模式', '进行中');
                    }
                    break;

                case 'code:output':
                    if (s.islandTerminal) {
                        this.showCompact('success', '代码', '执行完成');
                        this.autoHide();
                    }
                    break;

                case 'security:scan_result':
                    if (s.islandSkill) {
                        this.showCompact('warning', '安全扫描', '扫描完成');
                        this.autoHide();
                    }
                    break;

                case 'convert:result':
                    if (s.islandSkill) {
                        const ok = msg.status === 'success';
                        this.update(ok ? '转换完成' : '转换失败', '', ok ? 'success' : 'error');
                        this.autoHide();
                    }
                    break;

                case 'workflow:status':
                    if (s.islandSkill) {
                        this.showCompact('processing', '工作流', '执行中...');
                    }
                    break;
            }
        });
    }

    /** Auto-hide island after configured delay (0 = never auto-hide) */
    private autoHide(): void {
        const delay = getWakeIslandSettings().islandDelay;
        if (delay > 0) {
            setTimeout(() => this.hide(), delay);
        }
    }
}
