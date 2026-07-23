// NotificationManager - Toast notification system
export class NotificationManager {
    private container: HTMLElement | null = null;

    init(): void {
        this.container = document.getElementById('notifier-container');
    }

    show(message: string, type: 'info' | 'success' | 'warning' | 'error' = 'info', duration = 3000): void {
        if (!this.container) return;
        const toast = document.createElement('div');
        toast.className = `notifier-toast notifier-${type}`;
        toast.innerHTML = `
            <i class="fas ${this.getIcon(type)}"></i>
            <span>${message}</span>
        `;
        this.container.appendChild(toast);
        // Trigger animation
        requestAnimationFrame(() => toast.classList.add('show'));
        setTimeout(() => {
            toast.classList.remove('show');
            setTimeout(() => toast.remove(), 300);
        }, duration);
    }

    private getIcon(type: string): string {
        switch (type) {
            case 'success': return 'fa-check-circle';
            case 'warning': return 'fa-exclamation-triangle';
            case 'error': return 'fa-times-circle';
            default: return 'fa-info-circle';
        }
    }
}
