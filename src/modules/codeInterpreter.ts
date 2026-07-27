import type { ButlerAPI } from '../services/api';
import type { WebSocketService } from '../services/websocket';
import type { NotificationManager } from '../services/notification';

// CodeInterpreter - code execution with approval flow
export class CodeInterpreter {
    private api: ButlerAPI;
    private ws: WebSocketService;
    private notify: NotificationManager;

    constructor(api: ButlerAPI, ws: WebSocketService, notify: NotificationManager) {
        this.api = api;
        this.ws = ws;
        this.notify = notify;
    }

    init(): void {
        this.bindRun();
        this.bindLangSelect();
        this.listenWsResponses();
    }

    private listenWsResponses(): void {
        this.ws.on('message', (data: Record<string, unknown>) => {
            const msg = data as { type: string; data?: unknown };
            if (msg.type === 'code:output') {
                this.appendOutput(JSON.stringify(msg.data, null, 2));
            }
        });
    }

    private bindRun(): void {
        const btn = document.getElementById('code-run-btn');
        btn?.addEventListener('click', () => {
            const editor = document.getElementById('code-editor') as HTMLTextAreaElement;
            const lang = (document.getElementById('code-lang') as HTMLSelectElement)?.value || 'python';
            const code = editor?.value.trim();
            if (!code) {
                this.notify.show('请输入代码', 'warning');
                return;
            }
            this.appendOutput(`// Running ${lang}...`);
            this.api.runCode(code, lang);
        });
    }

    private bindLangSelect(): void {
        const select = document.getElementById('code-lang') as HTMLSelectElement;
        if (!select) return;
        select.addEventListener('change', () => {
            const placeholder = document.getElementById('code-placeholder');
            if (placeholder) {
                const lang = select.value;
                placeholder.textContent = `// 在此输入 ${lang} 代码...`;
            }
        });
    }

    private appendOutput(text: string): void {
        const output = document.getElementById('code-output');
        if (!output) return;
        const line = document.createElement('pre');
        line.className = 'code-output-line';
        line.textContent = text;
        output.appendChild(line);
        output.scrollTop = output.scrollHeight;
    }
}
