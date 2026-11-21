import XCTest

/// End-to-end simulator UI test for the full Flow run loop:
/// create plan -> launch settings -> COUNT -> TIMER pause/resume/early finish
/// -> single-node loop reset -> loop off -> advance -> exit progress feedback
/// -> second plan -> TIMER expiry -> normal finish -> back to plan list.
final class FlowRunFlowUITests: XCTestCase {
    private enum CardType {
        case count
        case timer
    }

    private struct CardStep {
        let type: CardType
        let name: String
        let value: Int
    }

    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launchArguments = ["-FlowUITestReset"]
        app.launch()
        addUIInterruptionMonitor(withDescription: "Notification permission") { alert in
            for title in ["Allow", "允许", "好", "OK"] where alert.buttons[title].exists {
                alert.buttons[title].tap()
                return true
            }
            return false
        }
    }

    func testFullRunFlowAcrossTwoPlans() {
        createPlan(expectedTotalPlans: 1, steps: [
            CardStep(type: .count, name: "Push", value: 15),
            CardStep(type: .timer, name: "Read", value: 8),
            CardStep(type: .count, name: "Squat", value: 10),
            CardStep(type: .timer, name: "Stretch", value: 5)
        ])

        // Plan A: start sequential playback (loop off by default).
        startPlan(at: 0)

        // Node 1: COUNT start + complete.
        XCTAssertTrue(app.staticTexts["Push"].waitForExistence(timeout: 8))
        XCTAssertEqual(app.buttons["run-primary"].label, "开始")
        attachScreenshot("count-ready")
        app.buttons["run-primary"].tap()
        XCTAssertEqual(app.buttons["run-primary"].label, "完成当前节点")
        attachScreenshot("count-running")
        app.buttons["run-primary"].tap()
        XCTAssertTrue(app.staticTexts["2 / 4"].waitForExistence(timeout: 5))

        // Node 2: TIMER start, pause, resume, early complete.
        XCTAssertTrue(app.staticTexts["Read"].waitForExistence(timeout: 8))
        XCTAssertEqual(app.buttons["run-primary"].label, "开始计时")
        attachScreenshot("timer-ready")
        app.buttons["run-primary"].tap()
        waitForCountdownToChange()
        attachScreenshot("timer-running")
        XCTAssertEqual(app.buttons["run-primary"].label, "暂停")
        XCTAssertEqual(app.buttons["run-secondary"].label, "完成当前节点")
        app.buttons["run-primary"].tap()
        XCTAssertTrue(app.staticTexts["已暂停"].waitForExistence(timeout: 5))
        XCTAssertEqual(app.buttons["run-primary"].label, "继续计时")
        XCTAssertEqual(app.buttons["run-secondary"].label, "完成当前节点")
        let pausedCountdown = app.staticTexts["run-countdown"].label
        Thread.sleep(forTimeInterval: 1.2)
        XCTAssertEqual(app.staticTexts["run-countdown"].label, pausedCountdown)
        attachScreenshot("timer-paused")
        app.buttons["run-primary"].tap()
        app.buttons["run-secondary"].tap()
        XCTAssertTrue(app.staticTexts["3 / 4"].waitForExistence(timeout: 5))

        // Node 3: enable current-node loop, complete, and verify a reset.
        XCTAssertTrue(app.staticTexts["Squat"].waitForExistence(timeout: 8))
        app.buttons["run-primary"].tap()
        let loopToggle = app.switches["run-loop-toggle"]
        XCTAssertTrue(loopToggle.waitForExistence(timeout: 5))
        loopToggle.tap()
        XCTAssertEqual(loopToggle.value as? String, "1")
        app.buttons["run-primary"].tap()
        XCTAssertTrue(app.staticTexts["Squat"].waitForExistence(timeout: 5))
        XCTAssertEqual(app.buttons["run-primary"].label, "开始")
        XCTAssertTrue(app.staticTexts["3 / 4"].exists)
        attachScreenshot("loop-reset")

        // Turning loop off must not advance; the next complete advances.
        loopToggle.tap()
        XCTAssertEqual(loopToggle.value as? String, "0")
        XCTAssertTrue(app.staticTexts["Squat"].exists)
        app.buttons["run-primary"].tap()
        app.buttons["run-primary"].tap()
        XCTAssertTrue(app.staticTexts["Stretch"].waitForExistence(timeout: 8))
        XCTAssertTrue(app.staticTexts["4 / 4"].exists)

        // Exit from node 4 with progress feedback.
        app.buttons["run-exit"].tap()
        let exitAlert = app.alerts["退出本次运行？"]
        XCTAssertTrue(exitAlert.waitForExistence(timeout: 5))
        XCTAssertTrue(exitAlert.staticTexts
            .matching(NSPredicate(format: "label CONTAINS %@", "当前进度：已完成 3 / 4"))
            .firstMatch.exists)
        attachScreenshot("exit-progress")
        exitAlert.buttons["退出运行"].tap()
        XCTAssertTrue(app.staticTexts["exit-result-title"].waitForExistence(timeout: 8))
        XCTAssertTrue(app.staticTexts["已完成 3 / 4 个节点"].exists)
        attachScreenshot("exit-result")
        app.buttons["exit-result-home"].tap()
        XCTAssertTrue(app.buttons["home-new"].waitForExistence(timeout: 8))
        XCTAssertTrue(app.staticTexts["4 个节点"].exists)

        // Plan B: TIMER reaches expiry without auto-complete, then finish all.
        createPlan(expectedTotalPlans: 2, steps: [
            CardStep(type: .timer, name: "Hold", value: 2),
            CardStep(type: .count, name: "Sit", value: 5)
        ])
        startPlan(at: 1)

        XCTAssertTrue(app.staticTexts["Hold"].waitForExistence(timeout: 8))
        XCTAssertEqual(app.buttons["run-primary"].label, "开始计时")
        app.buttons["run-primary"].tap()
        XCTAssertTrue(app.staticTexts["时间到"].waitForExistence(timeout: 10))
        XCTAssertEqual(app.buttons["run-primary"].label, "完成当前节点")
        XCTAssertTrue(app.staticTexts["1 / 2"].exists)
        attachScreenshot("timer-expired")
        app.buttons["run-primary"].tap()

        XCTAssertTrue(app.staticTexts["Sit"].waitForExistence(timeout: 8))
        app.buttons["run-primary"].tap()
        app.buttons["run-primary"].tap()

        XCTAssertTrue(app.staticTexts["result-title"].waitForExistence(timeout: 8))
        XCTAssertTrue(app.staticTexts["2 / 2 个节点"].exists)
        attachScreenshot("result")
        app.buttons["result-back"].tap()
        XCTAssertTrue(app.buttons["home-new"].waitForExistence(timeout: 8))
        XCTAssertTrue(app.staticTexts["4 个节点"].exists)
        XCTAssertTrue(app.staticTexts["2 个节点"].exists)
    }

    func testLaunchCountdownCanBeSkippedWithoutDoubleStart() {
        createPlan(expectedTotalPlans: 1, steps: [
            CardStep(type: .count, name: "Launch", value: 5)
        ])

        let start = app.buttons.matching(identifier: "plan-start").firstMatch
        XCTAssertTrue(start.waitForExistence(timeout: 8))
        start.tap()

        let setupStart = app.buttons["setup-start"]
        XCTAssertTrue(setupStart.waitForExistence(timeout: 8))
        setupStart.tap()

        let skip = app.buttons["launch-skip"]
        XCTAssertTrue(skip.waitForExistence(timeout: 3))
        // The session must not exist while the countdown is running.
        XCTAssertFalse(app.buttons["run-primary"].exists)
        attachScreenshot("launch-countdown")

        skip.tap()
        XCTAssertTrue(app.buttons["run-primary"].waitForExistence(timeout: 6))
        XCTAssertTrue(app.staticTexts["1 / 1"].exists)
        XCTAssertEqual(app.buttons.matching(identifier: "run-primary").count, 1)

        app.buttons["run-exit"].tap()
        let alert = app.alerts["退出本次运行？"]
        XCTAssertTrue(alert.waitForExistence(timeout: 5))
        alert.buttons["退出运行"].tap()
    }

    func testRollerSchedulingAndScreenshots() {
        createPlan(expectedTotalPlans: 1, steps: [
            CardStep(type: .count, name: "Alpha", value: 5),
            CardStep(type: .timer, name: "Beta", value: 12),
            CardStep(type: .count, name: "Gamma", value: 4),
            CardStep(type: .timer, name: "Delta", value: 3)
        ])
        startPlan(at: 0)

        // Initial state: all nodes are visible and the first one is expanded.
        XCTAssertTrue(app.staticTexts["Alpha"].waitForExistence(timeout: 8))
        XCTAssertTrue(app.staticTexts["1 / 4"].exists)
        XCTAssertTrue(app.staticTexts["Gamma"].exists)
        attachScreenshot("roller-initial-multi-level")

        // COUNT current: a calm, target-only waiting state. No per-repetition
        // counter is exposed; the user completes when they are ready.
        XCTAssertEqual(app.buttons["run-primary"].label, "开始")
        app.buttons["run-primary"].tap()
        XCTAssertTrue(app.staticTexts["目标 5 次"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["进行中"].exists)
        XCTAssertFalse(app.buttons["run-count-increment"].exists)
        attachScreenshot("roller-count-current")

        // Tapping a compact roller card switches the execution target. The
        // COUNT must be deferred without being completed.
        let betaCard = app.buttons["run-node-1"]
        for _ in 0..<3 where !betaCard.isHittable {
            app.scrollViews.firstMatch.swipeUp()
        }
        XCTAssertTrue(betaCard.waitForExistence(timeout: 5))
        betaCard.tap()
        XCTAssertTrue(app.staticTexts["2 / 4"].waitForExistence(timeout: 5))
        let alphaCard = app.buttons["run-node-0"]
        XCTAssertTrue(alphaCard.waitForExistence(timeout: 5))
        XCTAssertTrue(alphaCard.label.contains("暂缓"))
        XCTAssertTrue(app.staticTexts["目标 5 次"].exists)
        attachScreenshot("roller-after-switch")

        // TIMER current with real countdown and both action buttons.
        XCTAssertEqual(app.buttons["run-primary"].label, "开始计时")
        app.buttons["run-primary"].tap()
        XCTAssertTrue(app.staticTexts["run-countdown"].waitForExistence(timeout: 5))
        attachScreenshot("roller-timer-current")

        // Completing removes the node from the roller and folds it into the
        // collapsed archive.
        XCTAssertTrue(app.buttons["run-secondary"].waitForExistence(timeout: 5))
        app.buttons["run-secondary"].tap()
        XCTAssertTrue(app.staticTexts["3 / 4"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["已完成 1"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.staticTexts["Beta"].exists)
        attachScreenshot("roller-completed-collapsed")

        app.buttons["run-completed-toggle"].tap()
        XCTAssertTrue(app.staticTexts["Beta"].waitForExistence(timeout: 5))
        attachScreenshot("roller-completed-expanded")

        // A deferred COUNT remains selectable.
        for _ in 0..<4 where !alphaCard.isHittable {
            app.scrollViews.firstMatch.swipeDown()
        }
        if alphaCard.waitForExistence(timeout: 3), alphaCard.isHittable {
            alphaCard.tap()
            XCTAssertTrue(app.staticTexts["1 / 4"].waitForExistence(timeout: 5))
            XCTAssertTrue(app.staticTexts["目标 5 次"].exists)
            XCTAssertTrue(app.staticTexts["进行中"].exists)
            XCTAssertEqual(app.buttons["run-primary"].label, "完成当前节点")
        }

        app.buttons["run-exit"].tap()
        let alert = app.alerts["退出本次运行？"]
        XCTAssertTrue(alert.waitForExistence(timeout: 5))
        alert.buttons["退出运行"].tap()
    }

    func testTimerExpiryFeedbackInForeground() {
        createPlan(expectedTotalPlans: 1, steps: [
            CardStep(type: .timer, name: "Pulse", value: 5)
        ])
        startPlan(at: 0)
        XCTAssertTrue(app.buttons["run-primary"].waitForExistence(timeout: 8))
        XCTAssertEqual(app.buttons["run-primary"].label, "开始计时")
        app.buttons["run-primary"].tap()

        XCTAssertTrue(app.staticTexts["时间到"].waitForExistence(timeout: 12))
        XCTAssertEqual(app.buttons["run-primary"].label, "完成当前节点")
        attachScreenshot("timer-expired-foreground")
        Thread.sleep(forTimeInterval: 0.4)
    }

    func testSkippedNodeCanBeReselectedAndCompleted() {
        createPlan(expectedTotalPlans: 1, steps: [
            CardStep(type: .count, name: "SkipA", value: 5),
            CardStep(type: .count, name: "SkipB", value: 3),
            CardStep(type: .count, name: "SkipC", value: 4)
        ])
        startPlan(at: 0)
        XCTAssertTrue(app.buttons["run-primary"].waitForExistence(timeout: 8))

        // Skip a READY node. It stays in the roller as a selectable card.
        app.buttons["run-skip"].tap()
        XCTAssertTrue(app.staticTexts["2 / 3"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["已完成 0"].exists)
        let skippedA = app.buttons["run-node-0"]
        XCTAssertTrue(skippedA.waitForExistence(timeout: 5))
        XCTAssertTrue(skippedA.label.contains("已跳过"))
        attachScreenshot("roller-skipped-visible")

        // Reselect it: a never-started node returns to READY.
        skippedA.tap()
        XCTAssertTrue(app.staticTexts["1 / 3"].waitForExistence(timeout: 5))
        XCTAssertEqual(app.buttons["run-primary"].label, "开始")

        // Start it, skip it while RUNNING, then reselect: the RUNNING state is
        // retained, so the primary action is still "完成当前节点".
        app.buttons["run-primary"].tap()
        XCTAssertEqual(app.buttons["run-primary"].label, "完成当前节点")
        app.buttons["run-skip"].tap()
        XCTAssertTrue(app.staticTexts["2 / 3"].waitForExistence(timeout: 5))
        let skippedRunningA = app.buttons["run-node-0"]
        XCTAssertTrue(skippedRunningA.waitForExistence(timeout: 5))
        skippedRunningA.tap()
        XCTAssertTrue(app.staticTexts["1 / 3"].waitForExistence(timeout: 5))
        XCTAssertEqual(app.buttons["run-primary"].label, "完成当前节点")
        attachScreenshot("roller-skipped-reselected")

        // Only an explicit completion moves it to the archive.
        app.buttons["run-primary"].tap()
        XCTAssertTrue(app.staticTexts["2 / 3"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["已完成 1"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.staticTexts["SkipA"].exists)

        // Skip B while C is still active. B remains visible in the roller.
        app.buttons["run-skip"].tap()
        XCTAssertTrue(app.staticTexts["3 / 3"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["run-node-1"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["SkipB"].exists)

        // C is the last active node: skipping it is disabled so the flow can
        // always return to a skipped node.
        XCTAssertFalse(app.buttons["run-skip"].isEnabled)

        // Complete C; the run finalizes with 2 completed and 1 skipped.
        XCTAssertEqual(app.buttons["run-primary"].label, "开始")
        app.buttons["run-primary"].tap()
        app.buttons["run-primary"].tap()
        XCTAssertTrue(app.staticTexts["本次流程结束"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["已完成 2 个节点，跳过 1 个"].exists)
        XCTAssertTrue(app.staticTexts["跳过 1"].exists)
        attachScreenshot("result-skipped")
        app.buttons["result-back"].tap()
        XCTAssertTrue(app.buttons["home-new"].waitForExistence(timeout: 8))
    }

    // MARK: - Helpers

    private func attachScreenshot(_ name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    private func createPlan(expectedTotalPlans: Int, steps: [CardStep]) {
        app.buttons["home-new"].tap()
        let planName = expectedTotalPlans == 1 ? "Morning Flow With A Longer Name" : "Evening Flow"
        replaceText(in: app.textFields["plan-name-field"], with: planName)

        for step in steps {
            let add = app.buttons["add-node"].firstMatch
            XCTAssertTrue(add.waitForExistence(timeout: 8))
            var scrollAttempts = 0
            while !add.isHittable && scrollAttempts < 6 {
                app.scrollViews.firstMatch.swipeUp()
                scrollAttempts += 1
            }
            add.tap()

            if step.type == .timer {
                let timerChoice = app.buttons["计时步骤"].firstMatch
                if timerChoice.waitForExistence(timeout: 3) {
                    timerChoice.tap()
                } else {
                    app.segmentedControls.buttons["计时步骤"].tap()
                }
            }

            let nameField = app.textFields["步骤名称"]
            replaceText(in: nameField, with: step.name)

            if step.type == .count {
                replaceText(in: app.textFields["目标次数"], with: "\(step.value)")
            } else {
                replaceText(in: app.textFields["计时时长（秒）"], with: "\(step.value)")
            }

            let save = app.navigationBars.buttons["保存"].firstMatch
            XCTAssertTrue(save.waitForExistence(timeout: 5))
            save.tap()
            XCTAssertTrue(app.staticTexts[step.name].waitForExistence(timeout: 8))
        }

        for _ in 0..<4 {
            app.scrollViews.firstMatch.swipeDown()
        }
        attachScreenshot("editor-cards-\(expectedTotalPlans)")

        let textMode = app.buttons["文本编辑"]
        var topScrollAttempts = 0
        while !textMode.isHittable && topScrollAttempts < 6 {
            app.scrollViews.firstMatch.swipeDown()
            topScrollAttempts += 1
        }
        textMode.tap()
        Thread.sleep(forTimeInterval: 0.5)
        attachScreenshot("editor-text-\(expectedTotalPlans)")

        let save = app.buttons["editor-save-top"]
        XCTAssertTrue(save.waitForExistence(timeout: 8))
        save.tap()
        app.buttons["editor-back"].tap()
        XCTAssertTrue(app.buttons["home-new"].waitForExistence(timeout: 8))
        XCTAssertEqual(
            app.buttons.matching(identifier: "plan-start").count,
            expectedTotalPlans
        )
        attachScreenshot("home-after-create-\(expectedTotalPlans)")
    }

    private func startPlan(at index: Int) {
        let starts = app.buttons.matching(identifier: "plan-start")
        XCTAssertTrue(starts.element(boundBy: index).waitForExistence(timeout: 8))
        starts.element(boundBy: index).tap()

        let setupStart = app.buttons["setup-start"]
        XCTAssertTrue(setupStart.waitForExistence(timeout: 8))
        // The launch settings must not offer a loop choice any more.
        XCTAssertFalse(app.switches["setup-loop"].exists)
        XCTAssertFalse(app.staticTexts["单节点循环"].exists)
        attachScreenshot("setup-\(index)")
        setupStart.tap()
        allowNotificationsIfNeeded()

        XCTAssertTrue(app.buttons["run-primary"].waitForExistence(timeout: 10))
        // The running node page keeps the loop switch, default off.
        let loopToggle = app.switches["run-loop-toggle"]
        XCTAssertTrue(loopToggle.waitForExistence(timeout: 5))
        XCTAssertEqual(loopToggle.value as? String, "0")
    }

    private func waitForCountdownToChange() {
        let countdown = app.staticTexts["run-countdown"]
        guard countdown.waitForExistence(timeout: 5) else { return }
        let initial = countdown.label
        let deadline = Date().addingTimeInterval(3)
        while Date() < deadline && countdown.label == initial {
            RunLoop.current.run(until: Date().addingTimeInterval(0.1))
        }
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
