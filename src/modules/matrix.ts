// StateMatrix - manages tab switching, dynamic island, and global UI state
export class StateMatrix {
    init(): void {
        this.bindDock();
        this.bindSettingsTabs();
        this.bindCloseButtons();
        this.updateIsland();
    }

    private bindDock(): void {
        document.querySelectorAll('.dock-item').forEach(item => {
            item.addEventListener('click', () => {
                const tab = item.getAttribute('data-tab');
                if (!tab) return;
                // Update dock active state
                document.querySelectorAll('.dock-item').forEach(d => d.classList.remove('active'));
                item.classList.add('active');
                // Switch panel
                document.querySelectorAll('.panel').forEach(p => p.classList.remove('active'));
                const panel = document.getElementById(`panel-${tab}`);
                if (panel) panel.classList.add('active');
                // Update dynamic island text
                const label = item.querySelector('.dock-label')?.textContent || 'Butler';
                this.updateIslandText(label);
            });
        });
    }

    private bindSettingsTabs(): void {
        document.querySelectorAll('.settings-nav-item').forEach(item => {
            item.addEventListener('click', () => {
                const tab = item.getAttribute('data-stab');
                if (!tab) return;
                document.querySelectorAll('.settings-nav-item').forEach(n => n.classList.remove('active'));
                item.classList.add('active');
                document.querySelectorAll('.settings-tab').forEach(t => t.classList.remove('active'));
                const stab = document.getElementById(`stab-${tab}`);
                if (stab) stab.classList.add('active');
            });
        });
    }

    private bindCloseButtons(): void {
        const closeTerminal = document.getElementById('close-terminal');
        if (closeTerminal) {
            closeTerminal.addEventListener('click', () => {
                document.getElementById('terminal-overlay')?.classList.add('hidden');
            });
        }
        const closeMemos = document.getElementById('close-memos');
        if (closeMemos) {
            closeMemos.addEventListener('click', () => {
                document.getElementById('memos-overlay')?.classList.add('hidden');
            });
        }
    }

    private updateIsland(): void {
        const island = document.getElementById('dynamic-island');
        if (island) {
            setTimeout(() => island.classList.add('expanded'), 500);
        }
    }

    updateIslandText(text: string): void {
        const islandText = document.querySelector('.island-text');
        if (islandText) islandText.textContent = text;
        const island = document.getElementById('dynamic-island');
        if (island) {
            island.classList.remove('expanded');
            requestAnimationFrame(() => {
                requestAnimationFrame(() => island.classList.add('expanded'));
            });
        }
    }
}
