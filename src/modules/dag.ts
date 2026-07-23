import type { ButlerAPI } from '../services/api';
import type { WebSocketService } from '../services/websocket';
import type { NotificationManager } from '../services/notification';

// DagEngine - DAG workflow canvas with nodes and edges, connected to workflow_engine
export class DagEngine {
    private api: ButlerAPI;
    private ws: WebSocketService;
    private notify: NotificationManager;
    private nodes: Array<{ id: string; label: string; x: number; y: number; status: string }> = [];
    private edges: Array<{ from: string; to: string }> = [];
    private selectedNode: string | null = null;
    private isDragging = false;
    private dragNode: string | null = null;
    private dragOffset = { x: 0, y: 0 };

    constructor(api: ButlerAPI, ws: WebSocketService, notify: NotificationManager) {
        this.api = api;
        this.ws = ws;
        this.notify = notify;
    }

    init(): void {
        this.bindToolbar();
        this.bindCanvas();
        this.listenWsResponses();
    }

    private listenWsResponses(): void {
        this.ws.on('message', (data: Record<string, unknown>) => {
            const msg = data as { type: string; data?: unknown; workflowId?: string };
            if (msg.type === 'workflow:created') {
                this.notify.show('工作流已创建', 'success');
            }
            if (msg.type === 'workflow:status') {
                this.updateNodeStatuses(msg.data as Record<string, unknown>);
            }
        });
    }

    private bindToolbar(): void {
        document.querySelectorAll('.dag-toolbar .toolbar-btn').forEach(btn => {
            btn.addEventListener('click', () => {
                const action = btn.getAttribute('data-action');
                if (!action) return;
                switch (action) {
                    case 'add-node':
                        this.addNode();
                        break;
                    case 'execute':
                        this.executeWorkflow();
                        break;
                    case 'clear':
                        this.clearCanvas();
                        break;
                }
            });
        });
    }

    private bindCanvas(): void {
        const canvas = document.getElementById('dag-canvas') as HTMLElement;
        if (!canvas) return;

        canvas.addEventListener('click', (e) => {
            const target = e.target as HTMLElement;
            if (target.classList.contains('dag-node')) {
                this.selectedNode = target.dataset.id || null;
                this.highlightNode();
            } else if (target === canvas) {
                this.selectedNode = null;
                this.highlightNode();
            }
        });

        canvas.addEventListener('mousedown', (e) => {
            const target = e.target as HTMLElement;
            if (target.classList.contains('dag-node')) {
                this.isDragging = true;
                this.dragNode = target.dataset.id || null;
                const rect = target.getBoundingClientRect();
                this.dragOffset = { x: e.clientX - rect.left, y: e.clientY - rect.top };
            }
        });

        canvas.addEventListener('mousemove', (e) => {
            if (this.isDragging && this.dragNode) {
                const node = this.nodes.find(n => n.id === this.dragNode);
                if (node) {
                    const canvasRect = canvas.getBoundingClientRect();
                    node.x = e.clientX - canvasRect.left - this.dragOffset.x;
                    node.y = e.clientY - canvasRect.top - this.dragOffset.y;
                    this.renderNodes();
                }
            }
        });

        canvas.addEventListener('mouseup', () => {
            this.isDragging = false;
            this.dragNode = null;
        });
    }

    private addNode(): void {
        const id = `node-${Date.now()}`;
        const x = 50 + Math.random() * 200;
        const y = 50 + Math.random() * 200;
        this.nodes.push({ id, label: `步骤 ${this.nodes.length + 1}`, x, y, status: 'pending' });
        this.renderNodes();
        this.notify.show('节点已添加，拖拽排列后点击执行', 'info');
    }

    private clearCanvas(): void {
        this.nodes = [];
        this.edges = [];
        const canvas = document.getElementById('dag-canvas');
        if (canvas) {
            const nodeContainer = canvas.querySelector('.dag-nodes');
            const edgeContainer = canvas.querySelector('.dag-edges');
            if (nodeContainer) nodeContainer.innerHTML = '';
            if (edgeContainer) edgeContainer.innerHTML = '';
        }
    }

    private renderNodes(): void {
        const container = document.querySelector('#dag-canvas .dag-nodes') as HTMLElement;
        if (!container) return;
        container.innerHTML = this.nodes.map(n => `
            <div class="dag-node dag-${n.status}" data-id="${n.id}"
                 style="left:${n.x}px;top:${n.y}px;${this.selectedNode === n.id ? 'border-color:#007AFF;' : ''}">
                <span class="dag-node-label">${n.label}</span>
                <span class="dag-node-status"><i class="fas fa-${this.statusIcon(n.status)}"></i></span>
            </div>
        `).join('');

        // Re-bind drag on new elements
        container.querySelectorAll<HTMLElement>('.dag-node').forEach(el => {
            el.addEventListener('mousedown', (e) => {
                this.isDragging = true;
                this.dragNode = el.dataset.id || null;
                const rect = el.getBoundingClientRect();
                this.dragOffset = { x: (e as MouseEvent).clientX - rect.left, y: (e as MouseEvent).clientY - rect.top };
            });
        });
    }

    private highlightNode(): void {
        document.querySelectorAll<HTMLElement>('.dag-node').forEach(el => {
            el.style.borderColor = el.dataset.id === this.selectedNode ? '#007AFF' : '';
        });
    }

    private statusIcon(status: string): string {
        switch (status) {
            case 'running': return 'spinner fa-spin';
            case 'completed': return 'check-circle';
            case 'failed': return 'times-circle';
            default: return 'circle';
        }
    }

    private executeWorkflow(): void {
        if (this.nodes.length === 0) {
            this.notify.show('请先添加节点', 'warning');
            return;
        }

        // Convert visual nodes to workflow steps
        const steps = this.nodes.map(n => ({
            id: n.id,
            intent: n.label,
            entities: {},
            depends_on: this.edges
                .filter(e => e.to === n.id)
                .map(e => e.from),
        }));

        this.api.createWorkflow('自定义工作流', steps);
        // Execute immediately
        setTimeout(() => {
            if (steps.length > 0) {
                this.api.executeWorkflow('custom');
            }
        }, 500);
    }

    private updateNodeStatuses(data: Record<string, unknown>): void {
        // Update node statuses from workflow execution results
        const steps = data as { steps?: Array<{ id: string; status: string }> };
        if (steps?.steps) {
            for (const step of steps.steps) {
                const node = this.nodes.find(n => n.id === step.id);
                if (node) {
                    node.status = step.status;
                }
            }
            this.renderNodes();
        }
    }
}
