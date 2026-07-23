import type { ButlerAPI } from '../services/api';
import type { NotificationManager } from '../services/notification';
import type { WebSocketService } from '../services/websocket';
import { escapeHtml } from '../utils';

// ChatManager - handles chat messaging, quick actions, voice, attachments
export class ChatManager {
    private api: ButlerAPI;
    private ws: WebSocketService;
    private notify: NotificationManager;

    constructor(api: ButlerAPI, ws: WebSocketService, notify: NotificationManager) {
        this.api = api;
        this.ws = ws;
        this.notify = notify;
    }

    init(): void {
        this.bindQuickActions();
        this.bindInput();
        this.bindSend();
        this.listenWsResponses();
    }

    private listenWsResponses(): void {
        this.ws.on('message', (data: Record<string, unknown>) => {
            const msg = data as { type: string; data?: unknown; status?: string };
            if (msg.type === 'chat:response') {
                this.showThinking(false);
                const responseData = msg.data;
                if (msg.status === 'error') {
                    this.addBotMessage(typeof responseData === 'object' ? (responseData as Record<string, unknown>).message as string || '处理出错' : '处理出错', 'error');
                } else {
                    const text = typeof responseData === 'string'
                        ? responseData
                        : JSON.stringify(responseData);
                    this.addBotMessage(text);
                }
            }
        });
    }

    private bindQuickActions(): void {
        document.querySelectorAll('.quick-action-card').forEach(card => {
            card.addEventListener('click', () => {
                const action = card.getAttribute('data-action');
                if (action) this.sendUserMessage(action);
            });
        });
    }

    private bindInput(): void {
        const textarea = document.getElementById('chat-input') as HTMLTextAreaElement;
        if (!textarea) return;
        textarea.addEventListener('input', () => {
            textarea.style.height = 'auto';
            textarea.style.height = Math.min(textarea.scrollHeight, 120) + 'px';
        });
        textarea.addEventListener('keydown', (e) => {
            if (e.key === 'Enter' && !e.shiftKey) {
                e.preventDefault();
                this.handleSend();
            }
        });
    }

    private bindSend(): void {
        const sendBtn = document.getElementById('send-btn');
        if (sendBtn) sendBtn.addEventListener('click', () => this.handleSend());

        const voiceBtn = document.getElementById('voice-btn');
        if (voiceBtn) voiceBtn.addEventListener('click', () => {
            this.api.startVoice();
            this.notify.show('语音输入已开启', 'info');
        });

        const attachBtn = document.getElementById('attach-btn');
        if (attachBtn) attachBtn.addEventListener('click', () => {
            this.notify.show('附件上传功能开发中...', 'info');
        });
    }

    private handleSend(): void {
        const textarea = document.getElementById('chat-input') as HTMLTextAreaElement;
        const text = textarea?.value.trim();
        if (!text) return;
        textarea.value = '';
        textarea.style.height = 'auto';
        this.sendUserMessage(text);
    }

    private sendUserMessage(text: string): void {
        // Hide welcome, show message
        const welcome = document.getElementById('chat-welcome');
        if (welcome) welcome.style.display = 'none';

        const container = document.getElementById('chat-messages');
        if (!container) return;

        const msgEl = document.createElement('div');
        msgEl.className = 'chat-message user-message';
        msgEl.innerHTML = `<div class="message-content">${escapeHtml(text)}</div>`;
        container.appendChild(msgEl);
        container.scrollTop = container.scrollHeight;

        // Show thinking indicator
        this.showThinking(true);

        // Send to backend via WebSocket
        this.api.chat(text);
    }

    private addBotMessage(text: string, type: 'normal' | 'error' = 'normal'): void {
        const container = document.getElementById('chat-messages');
        if (!container) return;
        const msgEl = document.createElement('div');
        msgEl.className = `chat-message bot-message ${type === 'error' ? 'bot-error' : ''}`;
        msgEl.innerHTML = `<div class="message-content">${escapeHtml(text)}</div>`;
        container.appendChild(msgEl);
        container.scrollTop = container.scrollHeight;
    }

    private showThinking(show: boolean): void {
        const dot = document.getElementById('thinking-status');
        if (dot) {
            dot.classList.toggle('active', show);
            dot.classList.toggle('pulse', show);
        }
    }

}
