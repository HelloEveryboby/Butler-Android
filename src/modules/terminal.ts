import type { ButlerAPI, MarketplaceSkill } from '../services/api';
import type { WebSocketService } from '../services/websocket';

// Dangerous commands that need confirmation
const DANGEROUS_COMMANDS = [
    'rm -rf', 'rm -r', 'rmdir', 'del /f', 'del /s',
    'format', 'mkfs', 'fdisk', 'dd if=', '> /dev/',
    'chmod 777', 'sudo rm', ':(){ :|:& };:',  // fork bomb
    'shutdown', 'reboot', 'halt', 'poweroff',
];

const SKILLS_HELP = [
    '╔══════════════════════════════════════════════╗',
    '║       Butler Skills CLI — 技能管理命令      ║',
    '╠══════════════════════════════════════════════╣',
    '║  skills add <url>                           ║',
    '║    从 URL 直接下载安装技能包                ║',
    '║  skills add <source>/<name>                 ║',
    '║    从市场安装技能 (如: skills add butler/   ║',
    '║    audio_denoiser)                          ║',
    '║  skills add <id>                            ║',
    '║    按 ID 从市场搜索安装                     ║',
    '║  skills remove <id>                         ║',
    '║    卸载已安装的技能                         ║',
    '║  skills list                                ║',
    '║    列出所有已安装的技能                     ║',
    '║  skills search <query>                      ║',
    '║    搜索技能市场                             ║',
    '║  skills market                              ║',
    '║    浏览技能市场全部技能                     ║',
    '║  skills help                                ║',
    '║    显示此帮助信息                           ║',
    '╚══════════════════════════════════════════════╝',
].join('\n');

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

    // ── Command routing ────────────────────────────────────────

    private executeCommand(cmd: string): void {
        const lowerCmd = cmd.toLowerCase();

        // ── skills CLI — 本地处理，不发送到后端 ──────────────
        if (lowerCmd === 'skills' || lowerCmd.startsWith('skills ')) {
            this.handleSkillsCommand(cmd.slice('skills'.length).trim());
            return;
        }

        // ── 危险命令拦截 ──────────────────────────────────────
        const isDangerous = DANGEROUS_COMMANDS.some(dc => lowerCmd.includes(dc));
        if (isDangerous) {
            this.appendOutput('⚠️ 危险命令已拦截，请确认后重试', 'error');
            this.appendOutput('如果确认执行，请再次输入相同命令', 'response');
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

    // ── Skills CLI ────────────────────────────────────────────

    private handleSkillsCommand(args: string): void {
        if (!args || args === 'help') {
            this.appendOutput(SKILLS_HELP, 'response');
            return;
        }

        const parts = args.split(/\s+/);
        const subcommand = parts[0].toLowerCase();
        const rest = parts.slice(1).join(' ');

        switch (subcommand) {
            case 'add':
                this.skillsAdd(rest);
                break;
            case 'install':
                this.skillsAdd(rest);
                break;
            case 'remove':
            case 'rm':
            case 'uninstall':
                this.skillsRemove(rest);
                break;
            case 'list':
            case 'ls':
                this.skillsList();
                break;
            case 'search':
            case 'find':
                this.skillsSearch(rest);
                break;
            case 'market':
            case 'store':
                this.skillsMarket();
                break;
            case 'help':
            case '--help':
            case '-h':
                this.appendOutput(SKILLS_HELP, 'response');
                break;
            default:
                this.appendOutput(`未知子命令: ${subcommand}`, 'error');
                this.appendOutput('输入 skills help 查看可用命令', 'response');
        }
    }

    // ── skills add ────────────────────────────────────────────

    private async skillsAdd(target: string): Promise<void> {
        if (!target) {
            this.appendOutput('用法: skills add <url | source/name | id>', 'error');
            this.appendOutput('示例: skills add butler/audio_denoiser', 'response');
            this.appendOutput('示例: skills add https://example.com/skill.bsk', 'response');
            return;
        }

        // 1. Direct URL — is it a URL?
        if (target.startsWith('http://') || target.startsWith('https://')) {
            this.appendOutput(`📥 正在从 URL 下载技能包...`, 'response');
            this.appendOutput(`   ${target}`, 'response');
            const result = await this.api.installSkill(target);
            if (result.ok) {
                this.appendOutput(`✅ 安装成功!`, 'response');
                if (result.skill) {
                    this.appendOutput(`   技能: ${result.skill.name} (${result.skill.id})`, 'response');
                }
            } else {
                this.appendOutput(`❌ 安装失败: ${result.error || '未知错误'}`, 'error');
            }
            return;
        }

        // 2. "source/name" format — search marketplace
        this.appendOutput(`🔍 正在技能市场中搜索 "${target}"...`, 'response');
        const marketplace = await this.api.getMarketplaceSkills();

        let match: MarketplaceSkill | undefined;

        if (target.includes('/')) {
            // "source/name" format: e.g., "butler/audio_denoiser"
            const [source, ...nameParts] = target.split('/');
            const name = nameParts.join('/');
            match = marketplace.find(s => {
                const authorLower = s.author.toLowerCase().replace(/\s+/g, '').replace('team', '');
                const sourceLower = source.toLowerCase();
                return (authorLower.includes(sourceLower) || s.id.toLowerCase().includes(sourceLower))
                    && (s.id.toLowerCase().includes(name.toLowerCase())
                        || s.name.toLowerCase().includes(name.toLowerCase()));
            });
        } else {
            // Single id/name — try exact match first, then fuzzy
            match = marketplace.find(s =>
                s.id.toLowerCase() === target.toLowerCase()
            ) || marketplace.find(s =>
                s.id.toLowerCase().includes(target.toLowerCase())
                || s.name.toLowerCase().includes(target.toLowerCase())
            );
        }

        if (!match) {
            this.appendOutput(`❌ 未找到匹配的技能: "${target}"`, 'error');
            this.appendOutput('💡 输入 skills market 浏览可用技能，或 skills search <关键词> 搜索', 'response');
            return;
        }

        if (match.installed) {
            this.appendOutput(`⚠️ 技能 "${match.name}" (${match.id}) 已安装 (v${match.installed_version})`, 'response');
            return;
        }

        this.appendOutput(`📦 找到: ${match.name} — ${match.description}`, 'response');
        this.appendOutput(`   作者: ${match.author} | 版本: v${match.version} | 大小: ${match.size}`, 'response');
        this.appendOutput(`📥 正在安装...`, 'response');

        const result = await this.api.installSkill(match.download_url);
        if (result.ok) {
            this.appendOutput(`✅ "${match.name}" 安装成功!`, 'response');
        } else {
            this.appendOutput(`❌ 安装失败: ${result.error || '未知错误'}`, 'error');
        }
    }

    // ── skills remove ─────────────────────────────────────────

    private async skillsRemove(skillId: string): Promise<void> {
        if (!skillId) {
            this.appendOutput('用法: skills remove <id>', 'error');
            this.appendOutput('示例: skills remove audio_denoiser', 'response');
            return;
        }

        this.appendOutput(`🗑️ 正在卸载技能 "${skillId}"...`, 'response');
        const result = await this.api.uninstallSkill(skillId);
        if (result.ok) {
            this.appendOutput(`✅ 技能 "${skillId}" 已卸载`, 'response');
        } else {
            this.appendOutput(`❌ 卸载失败: ${result.error || '未知错误'}`, 'error');
        }
    }

    // ── skills list ───────────────────────────────────────────

    private async skillsList(): Promise<void> {
        this.appendOutput('📋 正在获取已安装技能...', 'response');
        const skills = await this.api.getSkills();
        if (skills.length === 0) {
            this.appendOutput('(暂无已安装的技能)', 'response');
            this.appendOutput('💡 输入 skills market 浏览可安装的技能', 'response');
            return;
        }

        this.appendOutput(`── 已安装技能 (${skills.length}) ──`, 'response');
        skills.forEach(s => {
            const status = s.status === 'running' ? '●' : '○';
            this.appendOutput(
                `  ${status} ${s.id.padEnd(24)} ${s.name.padEnd(14)} v${(s.version || '1.0.0').padEnd(8)} ${s.status || 'idle'}`,
                'response'
            );
        });
    }

    // ── skills search ─────────────────────────────────────────

    private async skillsSearch(query: string): Promise<void> {
        if (!query) {
            this.appendOutput('用法: skills search <关键词>', 'error');
            return;
        }

        this.appendOutput(`🔍 搜索 "${query}"...`, 'response');
        const marketplace = await this.api.getMarketplaceSkills();

        const q = query.toLowerCase();
        const results = marketplace.filter(s =>
            s.id.toLowerCase().includes(q)
            || s.name.toLowerCase().includes(q)
            || s.description.toLowerCase().includes(q)
            || s.tags.some(t => t.toLowerCase().includes(q))
            || s.author.toLowerCase().includes(q)
        );

        if (results.length === 0) {
            this.appendOutput(`未找到匹配 "${query}" 的技能`, 'response');
            return;
        }

        this.appendOutput(`── 搜索结果 (${results.length}) ──`, 'response');
        results.forEach(s => {
            const status = s.installed ? '✓ 已安装' : '+ 可安装';
            this.appendOutput(
                `  ${s.id.padEnd(24)} ${s.name.padEnd(14)} v${s.version.padEnd(8)} ${s.author.padEnd(16)} ${status}`,
                'response'
            );
        });
        this.appendOutput('💡 输入 skills add <id> 安装技能', 'response');
    }

    // ── skills market ─────────────────────────────────────────

    private async skillsMarket(): Promise<void> {
        this.appendOutput('🛒 正在加载技能市场...', 'response');
        const marketplace = await this.api.getMarketplaceSkills();
        if (marketplace.length === 0) {
            this.appendOutput('(市场暂无可用的技能)', 'response');
            return;
        }

        this.appendOutput(`── 技能市场 (${marketplace.length}) ──`, 'response');
        marketplace.forEach(s => {
            const stars = '★'.repeat(Math.floor(s.rating)) + '☆'.repeat(5 - Math.floor(s.rating));
            const status = s.installed ? '✓ 已安装' : '+ 可安装';
            this.appendOutput(
                `  ${s.id.padEnd(24)} ${stars} ${s.rating}`,
                'response'
            );
            this.appendOutput(
                `    ${s.name.padEnd(14)} v${s.version.padEnd(8)} ${s.author.padEnd(16)} ${s.size.padEnd(8)} ${status}`,
                'response'
            );
        });
        this.appendOutput('💡 输入 skills add <id> 安装技能', 'response');
    }

    // ── Output helpers ────────────────────────────────────────

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