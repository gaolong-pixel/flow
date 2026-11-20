import XCTest

/// Verifies that an iOS TIMER schedules a real local notification and that the
/// notification is delivered while the app is in the background. Notification
/// scheduling logic is also covered by unit tests with a fake scheduler; this
/// test exercises the system notification center end to end.
final class FlowTimerNotificationUITests: XCTestCase {
    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launchArguments = ["-FlowUITestReset"]
        app.launch()
    }

    func testTimerNotificationArrivesInBackground() {
        createTwoSecondTimerPlan()
        startPlan()
        allowNotificationsIfNeeded()

        XCTAssertTrue(app.buttons["run-primary"].waitForExistence(timeout: 8))
        app.buttons["run-primary"].tap()

        // Background the app before the 2s timer fires.
        XCUIDevice.shared.press(.home)

        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        let banner = springboard.staticTexts
            .matching(NSPredicate(format: "label CONTAINS %@", "计时结束"))
            .firstMatch
        let bannerAppeared = banner.waitForExistence(timeout: 8)

        if !bannerAppeared {
            // Banners disappear quickly; pull down Notification Center.
            let start = springboard.coordinate(
                withNormalizedOffset: CGVector(dx: 0.5, dy: 0.01)
            )
            let end = springboard.coordinate(
                withNormalizedOffset: CGVector(dx: 0.5, dy: 0.7)
            )
            start.press(forDuration: 0.1, thenDragTo: end)
        }

        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = "ios-local-notification-background"
        attachment.lifetime = .keepAlways
        add(attachment)

        let notificationText = springboard.staticTexts
            .matching(NSPredicate(format: "label CONTAINS %@", "计时结束"))
            .firstMatch
        XCTAssertTrue(notificationText.waitForExistence(timeout: 8))

        // Re-entering the app recomputes the expired state from the monotonic
        // clock; the notification never auto-completes the node.
        app.activate()
        XCTAssertTrue(app.staticTexts["时间到"].waitForExistence(timeout: 8))
        XCTAssertTrue(app.staticTexts["1 / 1"].exists)

        app.buttons["run-exit"].tap()
        let alert = app.alerts["退出本次运行？"]
        XCTAssertTrue(alert.waitForExistence(timeout: 5))
        alert.buttons["退出运行"].tap()
    }

    private func createTwoSecondTimerPlan() {
        app.buttons["home-new"].tap()

        let add = app.buttons["add-node"].firstMatch
        XCTAssertTrue(add.waitForExistence(timeout: 8))
        add.tap()
        app.buttons["计时步骤"].tap()

        replaceText(in: app.textFields["步骤名称"], with: "Notify")
        replaceText(in: app.textFields["计时时长（秒）"], with: "2")
        app.navigationBars.buttons["保存"].firstMatch.tap()
        XCTAssertTrue(app.staticTexts["Notify"].waitForExistence(timeout: 8))

        app.buttons["文本编辑"].tap()
        let save = app.buttons["editor-save-top"]
        XCTAssertTrue(save.waitForExistence(timeout: 8))
        save.tap()
        app.buttons["editor-back"].tap()
        XCTAssertTrue(app.buttons["home-new"].waitForExistence(timeout: 8))
    }

    private func startPlan() {
        let start = app.buttons.matching(identifier: "plan-start").firstMatch
        XCTAssertTrue(start.waitForExistence(timeout: 8))
        start.tap()
        let setupStart = app.buttons["setup-start"]
        XCTAssertTrue(setupStart.waitForExistence(timeout: 8))
        setupStart.tap()
    }

    private func allowNotificationsIfNeeded() {
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        let alert = springboard.alerts.firstMatch
        guard alert.waitForExistence(timeout: 4) else { return }
        for title in ["Allow", "允许", "好", "OK"] where alert.buttons[title].exists {
            alert.buttons[title].tap()
            return
        }
        if alert.buttons.count > 1 {
            alert.buttons.element(boundBy: 1).tap()
        }
    }

    private func replaceText(in field: XCUIElement, with text: String) {
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        field.tap()
        let current = (field.value as? String) ?? ""
        if current == text { return }
        if !current.isEmpty {
            field.typeText(
                String(repeating: XCUIKeyboardKey.delete.rawValue, count: current.count)
            )
        }
        field.typeText(text)
    }
}
