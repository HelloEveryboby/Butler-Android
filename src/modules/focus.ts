import type { ButlerAPI } from '../services/api';
import type { WebSocketService } from '../services/websocket';
import type { NotificationManager } from '../services/notification';

// FocusMode - Pomodoro-style focus timer connected to focus_mode core
export class FocusMode {
    private api: ButlerAPI;
    private ws: WebSocketService;
    private notify: NotificationManager;
    private timer: number | null = null;
    private remaining = 0;
    private isRunning = false;

    constructor(api: ButlerAPI, ws: WebSocketService, notify: NotificationManager) {
        this.api = api;
        this.ws = ws;
        this.notify = notify;
    }

    init(): void {
        this.bindControls();
        this.bindDuration();
        this.listenWsResponses();
        this.loadStatus();
    }

    private listenWsResponses(): void {
        this.ws.on('message', (data: Record<string, unknown>) => {
            const msg = data as { type: string; data?: unknown };
            if (msg.type === 'focus:status') {
                this.updateFromBackend(msg.data as Record<string, unknown>);
            }
        });
    }

    private bindControls(): void {
        const startBtn = document.getElementById('focus-start-btn');
        const stopBtn = document.getElementById('focus-stop-btn');

        startBtn?.addEventListener('click', () => {
            const duration = parseInt((document.getElementById('focus-duration') as HTMLSelectElement)?.value || '25');
            this.remaining = duration * 60;
            this.start();
        });

        stopBtn?.addEventListener('click', () => {
            this.stop();
        });
    }

    private bindDuration(): void {
        const select = document.getElementById('focus-duration') as HTMLSelectElement;
        if (!select) return;
        select.addEventListener('change', () => {
            if (!this.isRunning) {
                const mins = parseInt(select.value);
                this.remaining = mins * 60;
                this.updateDisplay();
            }
        });
    }

    private start(): void {
        this.isRunning = true;
        this.api.startFocus(Math.ceil(this.remaining / 60));
        this.notify.show('专注模式已开启', 'info');
        this.timer = window.setInterval(() => {
            this.remaining--;
            this.updateDisplay();
            if (this.remaining <= 0) {
                this.stop();
                this.notify.show('专注时间结束！', 'success');
            }
        }, 1000);
    }

    private stop(): void {
        this.isRunning = false;
        if (this.timer) {
            clearInterval(this.timer);
            this.timer = null;
        }
        this.api.stopFocus();
    }

    private updateDisplay(): void {
        const display = document.getElementById('focus-display');
        if (!display) return;
        const mins = Math.floor(this.remaining / 60);
        const secs = this.remaining % 60;
        display.textContent = `${mins.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}`;
    }

    private async loadStatus(): Promise<void> {
        const status = await this.api.getFocusStatus();
        this.updateFromBackend(status);
    }

    private updateFromBackend(data: Record<string, unknown>): void {
        if (!data) return;
        const isRunning = data.is_running as boolean;
        const remaining = data.remaining as number;

        if (isRunning && remaining) {
            this.remaining = remaining;
            this.isRunning = true;
            this.start();
        }
    }
}
