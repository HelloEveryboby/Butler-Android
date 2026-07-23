// StateMatrix - manages settings tabs and global UI state
// Updated for unified Apple-style navigation (NavController handles tab/page switching)
export class StateMatrix {
    init(): void {
        this.bindSettingsTabs();
        this.updateIsland();
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
