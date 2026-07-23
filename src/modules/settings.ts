import type { ButlerAPI } from '../services/api';
import type { NotificationManager } from '../services/notification';
import WakeManager from '../services/wakeManager';

// ── Shared Settings Interface ─────────────────────────────────
export interface WakeIslandSettings {
    // Island
    islandEnabled: boolean;
    islandChat: boolean;
    islandSkill: boolean;
    islandMusic: boolean;
    islandFocus: boolean;
    islandTerminal: boolean;
    islandDelay: number;
    // Wake
    wakeHeadset: boolean;
    wakeEdge: boolean;
    wakePower: boolean;
    // Headset custom actions
    headsetSingle: string;
    headsetDouble: string;
    headsetTriple: string;
}

const STORAGE_KEY = 'butler-wake-island-settings';

const DEFAULT_SETTINGS: WakeIslandSettings = {
    islandEnabled: true,
    islandChat: true,
    islandSkill: true,
    islandMusic: true,
    islandFocus: true,
    islandTerminal: true,
    islandDelay: 3000,
    wakeHeadset: true,
    wakeEdge: true,
    wakePower: true,
    headsetSingle: 'wake',
    headsetDouble: 'wake',
    headsetTriple: 'prev',
};

/** Read wake & island settings from localStorage (with safe defaults) */
export function getWakeIslandSettings(): WakeIslandSettings {
    try {
        const raw = localStorage.getItem(STORAGE_KEY);
        if (!raw) return { ...DEFAULT_SETTINGS };
        return { ...DEFAULT_SETTINGS, ...JSON.parse(raw) };
    } catch {
        return { ...DEFAULT_SETTINGS };
    }
}

/** Persist wake & island settings to localStorage + backend */
export function saveWakeIslandSettings(s: WakeIslandSettings): void {
    try {
        localStorage.setItem(STORAGE_KEY, JSON.stringify(s));
    } catch {
        // ignore
    }
}

// SettingsManager - handles settings save/load from backend, theme toggle, font size,
// plus wake & island configuration
export class SettingsManager {
    private api: ButlerAPI;
    private notify: NotificationManager;

    constructor(api: ButlerAPI, notify: NotificationManager) {
        this.api = api;
        this.notify = notify;
    }

    async init(): Promise<void> {
        this.bindThemeToggle();
        this.bindSaveModel();
        this.bindFontSize();
        this.bindBlur();
        this.bindIslandSettings();
        this.bindWakeSettings();
        this.bindAccessibilityStatus();
        await this.loadSettings();
        this.applyWakeIslandToUI();
    }

    private bindThemeToggle(): void {
        const toggle = document.getElementById('s-theme-toggle') as HTMLInputElement;
        if (!toggle) return;
        toggle.addEventListener('change', () => {
            const isDark = toggle.checked;
            document.body.className = isDark ? 'theme-dark' : 'theme-light';
            this.api.saveSettings({ theme: isDark ? 'dark' : 'light' });
        });
    }

    private bindSaveModel(): void {
        const saveBtn = document.getElementById('save-model-btn');
        if (!saveBtn) return;
        saveBtn.addEventListener('click', async () => {
            const provider = (document.getElementById('s-provider') as HTMLSelectElement)?.value;
            const model = (document.getElementById('s-model') as HTMLInputElement)?.value;
            const apikey = (document.getElementById('s-apikey') as HTMLInputElement)?.value;
            const baseurl = (document.getElementById('s-baseurl') as HTMLInputElement)?.value;

            const config = { provider, model, apikey, baseurl };

            const ok = await this.api.saveSettings(config);
            if (ok) {
                saveBtn.textContent = '已保存';
                this.notify.show('配置已保存到后端', 'success');
            } else {
                localStorage.setItem('butler-model-config', JSON.stringify(config));
                saveBtn.textContent = '已保存(本地)';
                this.notify.show('后端不可用，已保存到本地', 'warning');
            }
            setTimeout(() => { saveBtn.textContent = '保存配置'; }, 1500);
        });
    }

    private bindFontSize(): void {
        const select = document.getElementById('s-fontsize') as HTMLSelectElement;
        if (!select) return;
        select.addEventListener('change', () => {
            document.documentElement.style.setProperty('--font-size-base', select.value);
            this.api.saveSettings({ font_size: select.value });
        });
    }

    private bindBlur(): void {
        const slider = document.getElementById('s-blur') as HTMLInputElement;
        if (!slider) return;
        slider.addEventListener('input', () => {
            document.documentElement.style.setProperty('--glass-blur', slider.value + 'px');
        });
        slider.addEventListener('change', () => {
            this.api.saveSettings({ glass_blur: slider.value });
        });
    }

    // ── Island Settings ──────────────────────────────────────────

    private bindIslandSettings(): void {
        const ids = [
            's-island-enabled', 's-island-chat', 's-island-skill',
            's-island-music', 's-island-focus', 's-island-terminal',
        ];
        ids.forEach(id => {
            const el = document.getElementById(id) as HTMLInputElement;
            if (!el) return;
            el.addEventListener('change', () => this.saveIslandSettings());
        });

        const delay = document.getElementById('s-island-delay') as HTMLSelectElement;
        if (delay) {
            delay.addEventListener('change', () => this.saveIslandSettings());
        }
    }

    private saveIslandSettings(): void {
        const s = getWakeIslandSettings();
        s.islandEnabled = this.getChecked('s-island-enabled');
        s.islandChat = this.getChecked('s-island-chat');
        s.islandSkill = this.getChecked('s-island-skill');
        s.islandMusic = this.getChecked('s-island-music');
        s.islandFocus = this.getChecked('s-island-focus');
        s.islandTerminal = this.getChecked('s-island-terminal');
        const delayEl = document.getElementById('s-island-delay') as HTMLSelectElement;
        if (delayEl) s.islandDelay = parseInt(delayEl.value, 10);
        saveWakeIslandSettings(s);
        this.api.saveSettings({ island: s }).catch(() => {});
    }

    // ── Wake Settings ────────────────────────────────────────────

    private bindWakeSettings(): void {
        ['s-wake-headset', 's-wake-edge', 's-wake-power'].forEach(id => {
            const el = document.getElementById(id) as HTMLInputElement;
            if (!el) return;
            el.addEventListener('change', () => {
                this.saveWakeSettings();
                // Re-check accessibility status when wake toggles change
                this.checkAccessibilityStatus();
            });
        });

        ['s-wake-headset-single', 's-wake-headset-double', 's-wake-headset-triple'].forEach(id => {
            const el = document.getElementById(id) as HTMLSelectElement;
            if (!el) return;
            el.addEventListener('change', () => this.saveWakeSettings());
        });
    }

    private saveWakeSettings(): void {
        const s = getWakeIslandSettings();
        s.wakeHeadset = this.getChecked('s-wake-headset');
        s.wakeEdge = this.getChecked('s-wake-edge');
        s.wakePower = this.getChecked('s-wake-power');
        s.headsetSingle = this.getSelectValue('s-wake-headset-single');
        s.headsetDouble = this.getSelectValue('s-wake-headset-double');
        s.headsetTriple = this.getSelectValue('s-wake-headset-triple');
        saveWakeIslandSettings(s);
        this.api.saveSettings({ wake: s }).catch(() => {});
    }

    // ── Accessibility Status ─────────────────────────────────────

    private bindAccessibilityStatus(): void {
        const btn = document.getElementById('s-wake-open-accessibility');
        if (btn) {
            btn.addEventListener('click', async () => {
                try {
                    await WakeManager.openAccessibilitySettings();
                    this.notify.show('已跳转至无障碍设置，请启用 Butler 服务', 'info');
                } catch {
                    this.notify.show('无法打开无障碍设置', 'error');
                }
            });
        }
    }

    private async checkAccessibilityStatus(): Promise<void> {
        const warnEl = document.getElementById('s-wake-accessibility-status');
        if (!warnEl) return;

        const edgeOn = this.getChecked('s-wake-edge');
        const powerOn = this.getChecked('s-wake-power');

        if (!edgeOn && !powerOn) {
            warnEl.style.display = 'none';
            return;
        }

        if (typeof window === 'undefined' || !('Capacitor' in window)) {
            warnEl.style.display = 'none';
            return;
        }

        try {
            const result = await WakeManager.isAccessibilityEnabled();
            if (result.enabled) {
                warnEl.style.display = 'none';
            } else {
                warnEl.style.display = '';
            }
        } catch {
            warnEl.style.display = 'none';
        }
    }

    // ── Load / Restore ───────────────────────────────────────────

    private async loadSettings(): Promise<void> {
        try {
            const settings = await this.api.getSettings();
            if (!settings || Object.keys(settings).length === 0) {
                this.restoreFromLocal();
                return;
            }

            if (settings.theme === 'dark') {
                const toggle = document.getElementById('s-theme-toggle') as HTMLInputElement;
                if (toggle) toggle.checked = true;
                document.body.className = 'theme-dark';
            }
            if (settings.provider) (document.getElementById('s-provider') as HTMLSelectElement).value = settings.provider as string;
            if (settings.model) (document.getElementById('s-model') as HTMLInputElement).value = settings.model as string;
            if (settings.apikey) (document.getElementById('s-apikey') as HTMLInputElement).value = settings.apikey as string;
            if (settings.baseurl) (document.getElementById('s-baseurl') as HTMLInputElement).value = settings.baseurl as string;
            if (settings.font_size) {
                document.documentElement.style.setProperty('--font-size-base', settings.font_size as string);
                const select = document.getElementById('s-fontsize') as HTMLSelectElement;
                if (select) select.value = settings.font_size as string;
            }
            if (settings.glass_blur) {
                document.documentElement.style.setProperty('--glass-blur', settings.glass_blur + 'px');
                const slider = document.getElementById('s-blur') as HTMLInputElement;
                if (slider) slider.value = settings.glass_blur as string;
            }
        } catch {
            this.restoreFromLocal();
        }
    }

    /** Apply saved wake & island settings to UI controls */
    private applyWakeIslandToUI(): void {
        const s = getWakeIslandSettings();

        this.setChecked('s-island-enabled', s.islandEnabled);
        this.setChecked('s-island-chat', s.islandChat);
        this.setChecked('s-island-skill', s.islandSkill);
        this.setChecked('s-island-music', s.islandMusic);
        this.setChecked('s-island-focus', s.islandFocus);
        this.setChecked('s-island-terminal', s.islandTerminal);

        const delayEl = document.getElementById('s-island-delay') as HTMLSelectElement;
        if (delayEl) delayEl.value = String(s.islandDelay);

        this.setChecked('s-wake-headset', s.wakeHeadset);
        this.setChecked('s-wake-edge', s.wakeEdge);
        this.setChecked('s-wake-power', s.wakePower);

        this.setSelectValue('s-wake-headset-single', s.headsetSingle);
        this.setSelectValue('s-wake-headset-double', s.headsetDouble);
        this.setSelectValue('s-wake-headset-triple', s.headsetTriple);

        // Check accessibility status after applying UI
        this.checkAccessibilityStatus();
    }

    private restoreFromLocal(): void {
        try {
            const saved = JSON.parse(localStorage.getItem('butler-model-config') || '{}');
            if (saved.provider) (document.getElementById('s-provider') as HTMLSelectElement).value = saved.provider;
            if (saved.model) (document.getElementById('s-model') as HTMLInputElement).value = saved.model;
            if (saved.apikey) (document.getElementById('s-apikey') as HTMLInputElement).value = saved.apikey;
            if (saved.baseurl) (document.getElementById('s-baseurl') as HTMLInputElement).value = saved.baseurl;
        } catch {
            // ignore
        }
    }

    // ── DOM Helpers ──────────────────────────────────────────────

    private getChecked(id: string): boolean {
        const el = document.getElementById(id) as HTMLInputElement;
        return el ? el.checked : false;
    }

    private setChecked(id: string, val: boolean): void {
        const el = document.getElementById(id) as HTMLInputElement;
        if (el) el.checked = val;
    }

    private getSelectValue(id: string): string {
        const el = document.getElementById(id) as HTMLSelectElement;
        return el ? el.value : '';
    }

    private setSelectValue(id: string, val: string): void {
        const el = document.getElementById(id) as HTMLSelectElement;
        if (el) el.value = val;
    }
}
