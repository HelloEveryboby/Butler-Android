// WebSocketService - manages WebSocket connection to Butler backend
export class WebSocketService {
    private socket: WebSocket | null = null;
    private url: string = 'ws://localhost:8080/ws';
    private reconnectTimer: number | null = null;
    private listeners: Map<string, Set<Function>> = new Map();

    init(): void {
        this.connect();
    }

    private connect(): void {
        try {
            this.socket = new WebSocket(this.url);
            this.socket.onopen = () => {
                console.log('[WS] Connected');
                this.emit('connected', null);
            };
            this.socket.onclose = () => {
                console.log('[WS] Disconnected');
                this.emit('disconnected', null);
                this.scheduleReconnect();
            };
            this.socket.onerror = (e) => {
                console.warn('[WS] Error', e);
            };
            this.socket.onmessage = (event) => {
                try {
                    const data = JSON.parse(event.data);
                    this.emit('message', data);
                } catch {
                    // ignore non-JSON messages
                }
            };
        } catch {
            this.scheduleReconnect();
        }
    }

    private scheduleReconnect(): void {
        if (this.reconnectTimer) clearTimeout(this.reconnectTimer);
        this.reconnectTimer = window.setTimeout(() => this.connect(), 3000);
    }

    send(data: unknown): void {
        if (this.socket?.readyState === WebSocket.OPEN) {
            this.socket.send(JSON.stringify(data));
        }
    }

    on(event: string, fn: Function): void {
        if (!this.listeners.has(event)) this.listeners.set(event, new Set());
        this.listeners.get(event)!.add(fn);
    }

    off(event: string, fn: Function): void {
        this.listeners.get(event)?.delete(fn);
    }

    private emit(event: string, data: unknown): void {
        this.listeners.get(event)?.forEach(fn => fn(data));
    }

    isConnected(): boolean {
        return this.socket?.readyState === WebSocket.OPEN;
    }
}
