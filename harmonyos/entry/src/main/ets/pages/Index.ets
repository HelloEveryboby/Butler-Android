import { webview } from '@kit.ArkWeb';
import { common } from '@kit.AbilityKit';

@Entry
@Component
struct Index {
  controller: webview.WebviewController = new webview.WebviewController();

  aboutToAppear() {
    // 提前初始化 Web 引擎，加快首次加载速度
    webview.WebviewController.initializeWebEngine();
  }

  build() {
    Column() {
      Web({ src: $rawfile('index.html'), controller: this.controller })
        .domStorageAccess(true)
        .databaseAccess(true)
        .fileAccess(true)
        .mixedMode(MixedMode.All)
        .javaScriptAccess(true)
        .zoomAccess(false)
        .width('100%')
        .height('100%')
        .backgroundColor('#0a0a0f')
    }
    .width('100%')
    .height('100%')
    .backgroundColor('#0a0a0f')
  }
}
