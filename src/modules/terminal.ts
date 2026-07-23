import type { ButlerAPI } from '../services/api';
import type { WebSocketService } from '../services/websocket';

// TerminalManager - embedded terminal overlay, connected to backend
export class TerminalManager {
    private api: ButlerAPI;
    private ws: WebSocketService;

    constructor(api: ButlerAPI, ws: WebSocketService) {
        this.api = api;
        this.ws = ws;
    }

    init(): void {
        this.bindToggle();
        this.bindInput();
        this.listenWsResponses();
    }

    private listenWsResponses(): void {
        this.ws.on('message', (data: Record<string, unknown>) => {
            const msg = data as { type: string; output?: string; status?: string };
            if (msg.type === 'terminal:output') {
                this.appendOutput(msg.output || '(无输出)', msg.status === 'error' ? 'error' : 'response');
            }
        });
    }

    private bindToggle(): void {
        const toggle = document.getElementById('terminal-toggle');
        if (!toggle) return;
        toggle.addEventListener('click', () => {
            const overlay = document.getElementById('terminal-overlay');
            if (overlay) overlay.classList.toggle('hidden');
        });
    }

    private bindInput(): void {
        const input = document.getElementById('terminal-input') as HTMLInputElement;
        if (!input) return;
        input.addEventListener('keydown', (e) => {
            if (e.key === 'Enter') {
                const cmd = input.value.trim();
                if (!cmd) return;
                this.appendOutput(`$ ${cmd}`, 'command');
                input.value = '';
                // Send to backend
                this.api.runTerminal(cmd);
            }
        });
    }

    appendOutput(text: string, type: 'command' | 'response' | 'error' = 'response'): void {
        const output = document.getElementById('terminal-output');
        if (!output) return;
        const line = document.createElement('div');
        line.className = `terminal-line terminal-${type}`;
        line.textContent = text;
        output.appendChild(line);
        output.scrollTop = output.scrollHeight;
    }
}
