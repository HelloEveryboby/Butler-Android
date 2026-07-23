// HeatmapRenderer - canvas-based background heatmap animation
export class HeatmapRenderer {
    private canvas: HTMLCanvasElement | null = null;
    private ctx: CanvasRenderingContext2D | null = null;
    private animId: number = 0;
    private points: Array<{ x: number; y: number; vx: number; vy: number; r: number; color: string }> = [];

    init(): void {
        this.canvas = document.getElementById('substrate-heatmap') as HTMLCanvasElement;
        if (!this.canvas) return;
        this.ctx = this.canvas.getContext('2d');
        this.resize();
        this.createPoints();
        this.animate();
        window.addEventListener('resize', () => this.resize());
    }

    private resize(): void {
        if (!this.canvas) return;
        this.canvas.width = window.innerWidth;
        this.canvas.height = window.innerHeight;
    }

    private createPoints(): void {
        this.points = [];
        const count = 20;
        const colors = ['rgba(0,122,255,0.08)', 'rgba(175,82,222,0.06)', 'rgba(52,199,89,0.05)', 'rgba(255,149,0,0.06)'];
        for (let i = 0; i < count; i++) {
            this.points.push({
                x: Math.random() * (this.canvas?.width || 400),
                y: Math.random() * (this.canvas?.height || 800),
                vx: (Math.random() - 0.5) * 0.3,
                vy: (Math.random() - 0.5) * 0.3,
                r: Math.random() * 100 + 60,
                color: colors[Math.floor(Math.random() * colors.length)],
            });
        }
    }

    private animate(): void {
        if (!this.ctx || !this.canvas) return;
        const { ctx, canvas } = this;
        ctx.clearRect(0, 0, canvas.width, canvas.height);

        this.points.forEach(p => {
            p.x += p.vx;
            p.y += p.vy;
            if (p.x < 0 || p.x > canvas.width) p.vx *= -1;
            if (p.y < 0 || p.y > canvas.height) p.vy *= -1;

            const gradient = ctx.createRadialGradient(p.x, p.y, 0, p.x, p.y, p.r);
            gradient.addColorStop(0, p.color);
            gradient.addColorStop(1, 'transparent');
            ctx.fillStyle = gradient;
            ctx.beginPath();
            ctx.arc(p.x, p.y, p.r, 0, Math.PI * 2);
            ctx.fill();
        });

        this.animId = requestAnimationFrame(() => this.animate());
    }

    destroy(): void {
        cancelAnimationFrame(this.animId);
    }
}
