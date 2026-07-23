import './style.css';
import { ChatManager } from './modules/chat';
import { StateMatrix } from './modules/matrix';
import { SettingsManager } from './modules/settings';
import { SkillsManager } from './modules/skills';
import { TerminalManager } from './modules/terminal';
import { MemosManager } from './modules/memos';
import { TimeMachine } from './modules/timemachine';
import { DagEngine } from './modules/dag';
import { HeatmapRenderer } from './modules/heatmap';
import { TasksBoard } from './modules/tasks';
import { SecretVault } from './modules/vault';
import { FocusMode } from './modules/focus';
import { ClusterView } from './modules/cluster';
import { CronScheduler } from './modules/cron';
import { CodeInterpreter } from './modules/codeInterpreter';
import { UserProfile } from './modules/profile';
import { Converter } from './modules/converter';
import { MediaCenter } from './modules/media';
import { MemoryCenter } from './modules/memoryCenter';
import { NotificationCenter } from './modules/notificationCenter';
import { SecurityToolkit } from './modules/securityToolkit';
import { Extensions } from './modules/extensions';
import { DynamicIslandController } from './modules/dynamicIsland';
import { WakeManagerModule } from './modules/wakeManager';
import { WebSocketService } from './services/websocket';
import { NotificationManager } from './services/notification';
import { ButlerAPI } from './services/api';

// Global instances
export const ws = new WebSocketService();
export const api = new ButlerAPI(ws);
export const notify = new NotificationManager();

// Core modules
const chat = new ChatManager(api, ws, notify);
const matrix = new StateMatrix();
const settings = new SettingsManager(api, notify);
const skills = new SkillsManager(api, notify);
const terminal = new TerminalManager(api, ws);
const memos = new MemosManager(api, notify);
const timemachine = new TimeMachine(api, ws);
const dag = new DagEngine(api, ws, notify);
const heatmap = new HeatmapRenderer();

// Feature modules
const tasks = new TasksBoard(api, notify);
const vault = new SecretVault(api, notify);
const focus = new FocusMode(api, ws, notify);
const cluster = new ClusterView(api, ws, notify);
const cron = new CronScheduler(api, ws, notify);
const code = new CodeInterpreter(api, ws, notify);
const profile = new UserProfile(api, ws, notify);

// Aggregated panels
const converter = new Converter(api, ws, notify);
const media = new MediaCenter(api, ws, notify);
const memory = new MemoryCenter(api, ws, notify);
const notifyCenter = new NotificationCenter(api, ws, notify);
const security = new SecurityToolkit(api, ws, notify);
const extensions = new Extensions(api, ws, notify);

// Dynamic Island + Wake
const island = new DynamicIslandController(api, ws, notify);
const wakeManager = new WakeManagerModule(api, ws, notify, island);

// Boot
document.addEventListener('DOMContentLoaded', () => {
    ws.init();
    matrix.init();
    chat.init();
    settings.init();
    skills.init();
    terminal.init();
    memos.init();
    timemachine.init();
    dag.init();
    heatmap.init();
    tasks.init();
    vault.init();
    focus.init();
    cluster.init();
    cron.init();
    code.init();
    profile.init();
    converter.init();
    media.init();
    memory.init();
    notifyCenter.init();
    security.init();
    extensions.init();
    island.init();
    wakeManager.init();

    console.log('[Butler] All 22 modules initialized with wake support.');
});
