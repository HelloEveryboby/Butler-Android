import WakeManager, { type WakeEvent } from '../services/wakeManager';
import type { ButlerAPI } from '../services/api';
import type { WebSocketService } from '../services/websocket';
import type { NotificationManager } from '../services/notification';
import type { DynamicIslandController } from './dynamicIsland';
import { getWakeIslandSettings } from './settings';

// WakeManagerModule - manages all wake triggers and routes to Butler actions
// Respects user settings from the settings panel
export class WakeManagerModule {
    private api: ButlerAPI;
    private ws: WebSocketService;
    private notify: NotificationManager;
    private island: DynamicIslandController;
    private available: boolean = false;

    constructor(
        api: ButlerAPI,
        ws: WebSocketService,
        notify: NotificationManager,
        island: DynamicIslandController
    ) {
        this.api = api;
        this.ws = ws;
        this.notify = notify;
        this.island = island;
    }

    async init(): Promise<void> {
        if (typeof window === 'undefined' || !('Capacitor' in window)) {
            return;
        }

        const settings = getWakeIslandSettings();

        // Only start if at least one wake method is enabled
        if (!settings.wakeHeadset && !settings.wakeEdge && !settings.wakePower) {
            return;
        }

        try {
            const result = await WakeManager.isAccessibilityEnabled();
            if (result.enabled) {
                this.available = true;
            }
        } catch {
            return;
        }

        try {
            await WakeManager.startListening();
            if (settings.wakeHeadset) {
                await WakeManager.requestMediaButtonFocus();
            }
            this.available = true;

            await WakeManager.addListener('wake', (event: WakeEvent) => {
                this.handleWake(event.source);
            });
        } catch {
            // Not available, silently continue
        }
    }

    isAvailable(): boolean {
        return this.available;
    }

    async requestAccessibility(): Promise<void> {
        try {
            await WakeManager.openAccessibilitySettings();
        } catch {}
    }

    // ── Wake Handler ─────────────────────────────────────────────

    private async handleWake(source: string): Promise<void> {
        const settings = getWakeIslandSettings();

        // Filter by user's wake method preferences
        if (source === 'edge_swipe' && !settings.wakeEdge) return;
        if (source === 'power_double_click' && !settings.wakePower) return;
        if ((source === 'media_play_pause' || source === 'headset_hook' || source === 'media_next') && !settings.wakeHeadset) return;

        // Handle headset custom actions
        if (source === 'media_play_pause' || source === 'headset_hook') {
            const action = settings.headsetSingle;
            if (action === 'none') return;
            if (action === 'play_pause') {
                this.ws.send({ type: 'music:toggle' });
                this.notify.show('音乐播放/暂停', 'info');
                return;
            }
            // action === 'wake' → fall through to default wake behavior
        }

        if (source === 'media_next') {
            const action = settings.headsetDouble;
            if (action === 'none') return;
            if (action === 'next') {
                this.ws.send({ type: 'music:next' });
                this.notify.show('下一首', 'info');
                return;
            }
            // action === 'wake' → fall through
        }

        // Visual feedback via Dynamic Island (respects island settings)
        const islandSettings = getWakeIslandSettings();
        if (islandSettings.islandEnabled) {
            switch (source) {
                case 'edge_swipe':
                    await this.island.showCompact('info', 'Butler', '边缘滑动唤醒');
                    break;
                case 'power_double_click':
                    await this.island.showCompact('info', 'Butler', '电源键双击唤醒');
                    break;
                case 'media_play_pause':
                case 'headset_hook':
                    await this.island.expand();
                    break;
                case 'media_next':
                    await this.island.showCompact('success', 'Butler', '耳机唤醒');
                    break;
            }

            // Auto-hide using configured delay
            if (islandSettings.islandDelay > 0) {
                setTimeout(() => this.island.hide(), islandSettings.islandDelay);
            }
        }

        // Bring app to foreground / switch to chat panel
        this.focusApp();

        this.notify.show(`已通过${this.sourceLabel(source)}唤醒`, 'info');
    }

    private focusApp(): void {
        const chatTab = document.querySelector('[data-tab="chat"]') as HTMLElement;
        if (chatTab) {
            chatTab.click();
        }

        setTimeout(() => {
            const input = document.getElementById('chat-input') as HTMLTextAreaElement;
            if (input) {
                input.focus();
            }
        }, 300);
    }

    private sourceLabel(source: string): string {
        switch (source) {
            case 'edge_swipe': return '边缘滑动';
            case 'power_double_click': return '电源键双击';
            case 'media_play_pause':
            case 'headset_hook': return '耳机按键';
            case 'media_next': return '耳机双击';
            default: return '快捷操作';
        }
    }
}
