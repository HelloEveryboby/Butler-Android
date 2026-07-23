import type { ButlerAPI } from '../services/api';
import type { WebSocketService } from '../services/websocket';
import type { NotificationManager } from '../services/notification';

// MediaCenter - music player + media library + audio spectrum, aggregating
//   music_player + media_manager + skill_audio_core
export class MediaCenter {
    private api: ButlerAPI;
    private ws: WebSocketService;
    private notify: NotificationManager;
    private currentTrack: number = -1;
    private isPlaying: boolean = false;

    constructor(api: ButlerAPI, ws: WebSocketService, notify: NotificationManager) {
        this.api = api;
        this.ws = ws;
        this.notify = notify;
    }

    init(): void {
        this.bindControls();
        this.loadLibrary();
        this.listenWsResponses();
    }

    private listenWsResponses(): void {
        this.ws.on('message', (data: Record<string, unknown>) => {
            const msg = data as { type: string; data?: unknown };
            if (msg.type === 'media:library') {
                this.renderLibrary(msg.data as Array<{ name: string; type: string; path: string }>);
            }
            if (msg.type === 'media:playlist') {
                this.renderPlaylist(msg.data as Array<{ name: string; path: string }>);
            }
            if (msg.type === 'media:status') {
                this.updateStatus(msg.data as { playing: boolean; track: number });
            }
            if (msg.type === 'media:spectrum') {
                this.renderSpectrum(msg.data as number[]);
            }
        });
    }

    private bindControls(): void {
        const playBtn = document.getElementById('media-play-btn');
        playBtn?.addEventListener('click', () => {
            if (this.isPlaying) {
                this.ws.send({ type: 'music:pause' });
            } else {
                this.ws.send({ type: 'music:play' });
            }
        });

        const prevBtn = document.getElementById('media-prev-btn');
        prevBtn?.addEventListener('click', () => this.ws.send({ type: 'music:prev' }));

        const nextBtn = document.getElementById('media-next-btn');
        nextBtn?.addEventListener('click', () => this.ws.send({ type: 'music:next' }));
    }

    private loadLibrary(): void {
        this.ws.send({ type: 'media:get_library' });
        this.ws.send({ type: 'music:get_playlist' });
    }

    private renderLibrary(items: Array<{ name: string; type: string; path: string }>): void {
        const container = document.getElementById('media-library');
        if (!container) return;

        const audioItems = items.filter(i => i.type === 'audio' || /\.(mp3|wav)$/i.test(i.name));
        const imageItems = items.filter(i => i.type === 'image' || /\.(jpg|jpeg|png)$/i.test(i.name));

        container.innerHTML = `
            <div class="media-section">
                <h4 class="media-section-title"><i class="fas fa-music"></i> 音频 (${audioItems.length})</h4>
                <div class="media-list">
                    ${audioItems.map((item, idx) => `
                        <div class="media-item" data-path="${item.path}" data-idx="${idx}">
                            <i class="fas fa-file-audio"></i>
                            <span class="media-item-name">${item.name}</span>
                        </div>
                    `).join('')}
                    ${audioItems.length === 0 ? '<div class="media-empty-small">暂无音频</div>' : ''}
                </div>
            </div>
            <div class="media-section">
                <h4 class="media-section-title"><i class="fas fa-image"></i> 图片 (${imageItems.length})</h4>
                <div class="media-list">
                    ${imageItems.map(item => `
                        <div class="media-item" data-path="${item.path}">
                            <i class="fas fa-file-image"></i>
                            <span class="media-item-name">${item.name}</span>
                        </div>
                    `).join('')}
                    ${imageItems.length === 0 ? '<div class="media-empty-small">暂无图片</div>' : ''}
                </div>
            </div>
        `;

        container.querySelectorAll('.media-item[data-idx]').forEach(el => {
            el.addEventListener('click', () => {
                const idx = parseInt(el.getAttribute('data-idx') || '0');
                this.ws.send({ type: 'music:play', index: idx });
                this.notify.show('开始播放', 'info');
            });
        });
    }

    private renderPlaylist(tracks: Array<{ name: string; path: string }>): void {
        const container = document.getElementById('media-playlist');
        if (!container) return;
        if (!tracks || tracks.length === 0) {
            container.innerHTML = '<div class="media-empty-small">播放列表为空</div>';
            return;
        }
        container.innerHTML = tracks.map((t, i) => `
            <div class="playlist-item ${i === this.currentTrack ? 'active' : ''}" data-idx="${i}">
                <span class="playlist-num">${i + 1}</span>
                <span class="playlist-name">${t.name}</span>
            </div>
        `).join('');
    }

    private updateStatus(status: { playing: boolean; track: number }): void {
        this.isPlaying = status.playing;
        this.currentTrack = status.track;
        const playBtn = document.getElementById('media-play-btn') as HTMLElement;
        if (playBtn) playBtn.innerHTML = this.isPlaying ? '<i class="fas fa-pause"></i>' : '<i class="fas fa-play"></i>';
    }

    private renderSpectrum(data: number[]): void {
        const canvas = document.getElementById('media-spectrum') as HTMLCanvasElement;
        if (!canvas || !data || data.length === 0) return;
        const ctx = canvas.getContext('2d');
        if (!ctx) return;
        const w = canvas.width = canvas.clientWidth;
        const h = canvas.height = canvas.clientHeight;
        ctx.clearRect(0, 0, w, h);
        const bars = Math.min(data.length, 32);
        const barW = w / bars;
        for (let i = 0; i < bars; i++) {
            const val = data[i] / 255;
            const barH = val * h * 0.9;
            ctx.fillStyle = `hsl(${200 + val * 60}, 80%, ${50 + val * 20}%)`;
            ctx.fillRect(i * barW + 1, h - barH, barW - 2, barH);
        }
    }
}
