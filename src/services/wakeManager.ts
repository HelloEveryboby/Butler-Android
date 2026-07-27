import { registerPlugin, PluginListenerHandle } from '@capacitor/core';

export interface WakeEvent {
    source: string;      // 'edge_swipe' | 'power_double_click' | 'media_play_pause' | 'media_next' | 'headset_hook'
    timestamp: number;
}

export interface WakeManagerPlugin {
    isAccessibilityEnabled(): Promise<{ enabled: boolean }>;
    openAccessibilitySettings(): Promise<void>;
    isMediaButtonEnabled(): Promise<{ enabled: boolean }>;
    getLastWake(): Promise<{ source: string; timestamp: number }>;
    requestMediaButtonFocus(): Promise<{ ok: boolean }>;
    startListening(): Promise<{ ok: boolean }>;
    stopListening(): Promise<{ ok: boolean }>;
    addListener(eventName: 'wake', listenerFunc: (event: WakeEvent) => void): Promise<PluginListenerHandle>;
}

const WakeManager = registerPlugin<WakeManagerPlugin>('WakeManager');

export default WakeManager;
