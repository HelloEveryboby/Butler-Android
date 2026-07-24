import type { ButlerAPI } from '../services/api';
import type { WebSocketService } from '../services/websocket';

// Dangerous commands that need confirmation
const DANGEROUS_COMMANDS = [
    'rm -rf', 'rm -r', 'rmdir', 'del /f', 'del /s',
    'format', 'mkfs', 'fdisk', 'dd if=', '> /dev/',
    'chmod 777', 'sudo rm', ':(){ :|:& };:',  // fork bomb
    'shutdown', 'reboot', 'halt', 'poweroff',
];

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
                this.executeCommand(cmd);
            }
        });
    }

    private executeCommand(cmd: string): void {
        const lowerCmd = cmd.toLowerCase();
        const isDangerous = DANGEROUS_COMMANDS.some(dc => lowerCmd.includes(dc));
        if (isDangerous) {
            this.appendOutput('⚠️ 危险命令已拦截，请确认后重试', 'error');
            this.appendOutput('如果确认执行，请再次输入相同命令', 'response');
            // Store pending dangerous command
            const input = document.getElementById('terminal-input') as HTMLInputElement;
            if (input) {
                input.dataset.pendingCmd = cmd;
                input.placeholder = '再次输入危险命令以确认...';
            }
            return;
        }
        // Check if this is a confirmation of a previously blocked command
        const input = document.getElementById('terminal-input') as HTMLInputElement;
        if (input?.dataset.pendingCmd) {
            const pending = input.dataset.pendingCmd;
            delete input.dataset.pendingCmd;
            input.placeholder = '';
            if (cmd === pending) {
                this.appendOutput('确认执行危险命令...', 'response');
                this.api.runTerminal(cmd);
                return;
            } else {
                this.appendOutput('命令不匹配，已取消', 'error');
                return;
            }
        }
        this.api.runTerminal(cmd);
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
