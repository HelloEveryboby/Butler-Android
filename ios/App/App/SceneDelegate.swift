//
//  SceneDelegate.swift
//  App
//
//  Created with Capacitor v8.
//

import UIKit
import Capacitor

class SceneDelegate: UIResponder, UIWindowSceneDelegate {

    var window: UIWindow?

    func scene(_ scene: UIScene, willConnectTo session: UISceneSession, options connectionOptions: UIScene.ConnectionOptions) {
        // Use this method to optionally configure and attach the UIWindow `window`
        // to the provided `windowScene` `scene`.
        // If using a storyboard, the `window` property will automatically be
        // initialized and attached to the scene.
        // The delegate may already be set up by the time this is called - it is
        // important to set the delegate to nil and then set it to the
        // CAPBridgeDelegate.
        guard let windowScene = (scene as? UIWindowScene) else { return }
        let window = UIWindow(windowScene: windowScene)
        window.rootViewController = CAPBridgeViewController()
        self.window = window
        window.makeKeyAndVisible()
    }

    func sceneDidDisconnect(_ scene: UIScene) {
        // Called as the scene is being released by the system.
        // This occurs shortly after the scene enters the background, or when its
        // session is discarded.
        // Release any resources associated with this scene that can be re-created
        // the next time the scene connects.
    }

    func sceneDidBecomeActive(_ scene: UIScene) {
        // Called when the scene has moved from an inactive state to an active state.
        // Use this method to restart any tasks that were paused (or not yet
        // started) when the scene was inactive.
    }

    func sceneWillResignActive(_ scene: UIScene) {
        // Called when the scene will move from an active state to an inactive state.
        // This may occur due to temporary interruptions (such as an incoming phone
        // call or SMS message) or when the user quits the application and it begins
        // the transition to the background state.
        // Use this method to pause ongoing tasks, disable timers, and invalidate
        // graphics rendering callbacks. Games should use this method to pause the
        // game.
    }

    func sceneWillEnterForeground(_ scene: UIScene) {
        // Called as the scene transitions from the background state to the active
        // state.
        // Use this method to undo the changes made on entering the background.
    }

    func sceneDidEnterBackground(_ scene: UIScene) {
        // Called as the scene transitions from the foreground state to the
        // background state.
        // Use this method to save data, release shared resources, and store enough
        // scene-specific state information to restore the scene back to its current
        // state.
    }
}
