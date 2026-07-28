import type { ButlerAPI } from '../services/api';
import type { WebSocketService } from '../services/websocket';
import type { NotificationManager } from '../services/notification';

// Converter - universal format converter aggregating markitdown + docx + pdf + archive_manager
export class Converter {
    private api: ButlerAPI;
    private ws: WebSocketService;
    private notify: NotificationManager;

    constructor(api: ButlerAPI, ws: WebSocketService, notify: NotificationManager) {
        this.api = api;
        this.ws = ws;
        this.notify = notify;
    }

    init(): void {
        this.bindConvert();
        this.listenWsResponses();
    }

    private listenWsResponses(): void {
        this.ws.on('message', (data: Record<string, unknown>) => {
            const msg = data as { type: string; data?: unknown; status?: string };
            if (msg.type === 'convert:result') {
                this.showResult(msg.data, msg.status);
            }
            if (msg.type === 'archive:contents') {
                this.showArchiveContents(msg.data as Array<{ name: string; size: string }>);
            }
        });
    }

    private bindConvert(): void {
        const form = document.getElementById('converter-form');
        if (!form) return;
        form.addEventListener('submit', (e) => {
            e.preventDefault();
            const file = (document.getElementById('converter-file') as HTMLInputElement).value.trim();
            const format = (document.getElementById('converter-format') as HTMLSelectElement).value;
            if (!file) {
                this.notify.show('请输入文件路径', 'warning');
                return;
            }
            this.ws.send({ type: 'convert', file, target: format });
            this.setOutput('正在转换...');
        });

        const archiveBtn = document.getElementById('converter-archive-btn');
        archiveBtn?.addEventListener('click', () => {
            const file = (document.getElementById('converter-file') as HTMLInputElement).value.trim();
            if (!file) {
                this.notify.show('请输入压缩包路径', 'warning');
                return;
            }
            this.ws.send({ type: 'archive:list', path: file });
            this.setOutput('正在读取压缩包...');
        });
    }

    private showResult(data: unknown, status?: string): void {
        if (status === 'error') {
            this.setOutput(`转换失败: ${JSON.stringify(data)}`);
            this.notify.show('转换失败', 'error');
        } else {
            this.setOutput(typeof data === 'string' ? data : JSON.stringify(data, null, 2));
            this.notify.show('转换完成', 'success');
        }
    }

    private showArchiveContents(files: Array<{ name: string; size: string }>): void {
        if (!files || files.length === 0) {
            this.setOutput('压缩包为空或无法读取');
            return;
        }
        const lines = files.map(f => `  ${f.name}  (${f.size || '?'})`).join('\n');
        this.setOutput(`共 ${files.length} 个文件:\n${lines}`);
    }

    private setOutput(text: string): void {
        const output = document.getElementById('converter-output');
        if (output) output.textContent = text;
    }
}
