import { registerPlugin } from '@capacitor/core';

export interface DynamicIslandPlugin {
  showCompact(options?: { icon?: string; title?: string; content?: string }): Promise<{ ok: boolean }>;
  expand(): Promise<void>;
  collapse(): Promise<void>;
  update(options: { title?: string; content?: string; icon?: string }): Promise<{ ok: boolean }>;
  hide(): Promise<{ ok: boolean }>;
  getState(): Promise<{ state: string }>;
  checkPermission(): Promise<{ granted: boolean }>;
  requestPermission(): Promise<{ granted: boolean }>;
}

const DynamicIsland = registerPlugin<DynamicIslandPlugin>('DynamicIsland');

export default DynamicIsland;
