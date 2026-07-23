// ── Shared Utilities ─────────────────────────────────────────────

/** Escape HTML special characters to prevent XSS */
export function escapeHtml(text: string): string {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}

/** Generate a simple unique ID */
export function uid(): string {
    return Date.now().toString(36) + Math.random().toString(36).slice(2, 8);
}

/** Format a timestamp to HH:MM:SS */
export function formatTime(ts?: number | string): string {
    const d = typeof ts === 'number' ? new Date(ts) : new Date();
    return d.toLocaleTimeString('zh-CN', { hour12: false });
}

/** Format a timestamp to relative text (e.g. "3 分钟前") */
export function timeAgo(ts: number | string): string {
    const now = Date.now();
    const t = typeof ts === 'string' ? new Date(ts).getTime() : ts;
    const diff = Math.max(0, now - t);
    const sec = Math.floor(diff / 1000);
    if (sec < 60) return '刚刚';
    const min = Math.floor(sec / 60);
    if (min < 60) return `${min} 分钟前`;
    const hr = Math.floor(min / 60);
    if (hr < 24) return `${hr} 小时前`;
    const day = Math.floor(hr / 24);
    return `${day} 天前`;
}
