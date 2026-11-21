import XCTest

/// UI coverage for creating a named plan, renaming it, trimming input,
/// rejecting an empty name, and confirming the persisted name after reopening.
final class FlowPlanNamingUITests: XCTestCase {
    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launchArguments = ["-FlowUITestReset"]
        app.launch()
    }

    func testCreateRenameAndValidatePlanName() {
        attachScreenshot("home-empty")
        app.buttons["home-new"].tap()

        let nameField = app.textFields["plan-name-field"]
        XCTAssertTrue(nameField.waitForExistence(timeout: 8))
        XCTAssertEqual(nameField.value as? String, "未命名计划")
        attachScreenshot("plan-name-field")

        // Leading/trailing whitespace is trimmed on save.
        replaceText(in: nameField, with: "  Morning Flow  ")
        addSingleCountStep()
        let saveTop = app.buttons["editor-save-top"]
        XCTAssertTrue(saveTop.isEnabled)
        saveTop.tap()
        // Saving a new plan returns to the home list automatically.
        XCTAssertTrue(app.buttons["home-new"].waitForExistence(timeout: 8))
        XCTAssertTrue(app.staticTexts["Morning Flow"].waitForExistence(timeout: 8))
        attachScreenshot("plan-name-saved")

        // Reopen and rename the existing plan through the prepare menu.
        openEditorFromFirstPlan()
        XCTAssertTrue(nameField.waitForExistence(timeout: 8))
        XCTAssertEqual(nameField.value as? String, "Morning Flow")
        replaceText(in: nameField, with: "Evening Flow")
        app.buttons["editor-save-top"].tap()
        app.buttons["editor-back"].tap()
        XCTAssertTrue(app.staticTexts["Evening Flow"].waitForExistence(timeout: 8))
        XCTAssertFalse(app.staticTexts["Morning Flow"].exists)

        // Empty names are rejected and must not overwrite the persisted name.
        openEditorFromFirstPlan()

        let reopenedField = app.textFields["plan-name-field"]
        XCTAssertTrue(reopenedField.waitForExistence(timeout: 8))
        replaceText(in: reopenedField, with: "")
        app.buttons["editor-save-top"].tap()
        XCTAssertTrue(app.staticTexts["流程名称不能为空。"].waitForExistence(timeout: 8))
        attachScreenshot("plan-name-empty-error")
        app.buttons["editor-back"].tap()
        XCTAssertTrue(app.staticTexts["Evening Flow"].waitForExistence(timeout: 8))

        // Overlong names are rejected as well.
        openEditorFromFirstPlan()
        let finalField = app.textFields["plan-name-field"]
        XCTAssertTrue(finalField.waitForExistence(timeout: 8))
        replaceText(in: finalField, with: String(repeating: "a", count: 61))
        app.buttons["editor-save-top"].tap()
        XCTAssertTrue(
            app.staticTexts["流程名称最多 60 个字符。"].waitForExistence(timeout: 8)
        )
        app.buttons["editor-back"].tap()
        XCTAssertTrue(app.staticTexts["Evening Flow"].waitForExistence(timeout: 8))
    }

    func testPlanDeleteRequiresConfirmation() {
        app.buttons["home-new"].tap()
        let nameField = app.textFields["plan-name-field"]
        XCTAssertTrue(nameField.waitForExistence(timeout: 8))
        replaceText(in: nameField, with: "Delete Me")
        addSingleCountStep()
        app.buttons["editor-save-top"].tap()
        XCTAssertTrue(app.buttons["home-new"].waitForExistence(timeout: 8))

        let planTitle = app.staticTexts["Delete Me"]
        XCTAssertTrue(planTitle.waitForExistence(timeout: 8))

        // Delete lives in the prepare page menu.
        let planCard = app.buttons.matching(identifier: "plan-start").firstMatch
        XCTAssertTrue(planCard.waitForExistence(timeout: 8))
        planCard.tap()
        let more = app.buttons["plan-more"]
        XCTAssertTrue(more.waitForExistence(timeout: 8))

        // Cancel must keep the plan.
        more.tap()
        tapMenuDelete()
        let alert = app.alerts["删除这个流程？"]
        XCTAssertTrue(alert.waitForExistence(timeout: 5))
        XCTAssertTrue(alert.staticTexts["删除后，这个流程及其节点将无法恢复。"].exists)
        attachScreenshot("delete-confirm")
        alert.buttons["取消"].tap()
        XCTAssertTrue(planTitle.waitForExistence(timeout: 5))

        // Confirm must delete and refresh the list.
        more.tap()
        tapMenuDelete()
        let confirmAlert = app.alerts["删除这个流程？"]
        XCTAssertTrue(confirmAlert.waitForExistence(timeout: 5))
        confirmAlert.buttons["删除"].tap()
        XCTAssertTrue(app.staticTexts["还没有流程"].waitForExistence(timeout: 8))
        XCTAssertFalse(app.staticTexts["Delete Me"].exists)
        attachScreenshot("delete-complete")
    }

    private func addSingleCountStep(name: String = "Step") {
        let add = app.buttons["add-node"].firstMatch
        XCTAssertTrue(add.waitForExistence(timeout: 8))
        var attempts = 0
        while !add.isHittable && attempts < 6 {
            app.scrollViews.firstMatch.swipeUp()
            attempts += 1
        }
        add.tap()
        replaceText(in: app.textFields["步骤名称"], with: name)
        replaceText(in: app.textFields["目标次数"], with: "1")
        let save = app.navigationBars.buttons["保存"].firstMatch
        XCTAssertTrue(save.waitForExistence(timeout: 5))
        save.tap()
        XCTAssertTrue(app.staticTexts[name].waitForExistence(timeout: 8))
    }

    func testEmptyDraftDoesNotCreatePlan() {
        app.buttons["home-new"].tap()
        XCTAssertTrue(app.textFields["plan-name-field"].waitForExistence(timeout: 8))
        let save = app.buttons["editor-save-top"]
        XCTAssertTrue(save.waitForExistence(timeout: 5))
        XCTAssertFalse(save.isEnabled)
        XCTAssertTrue(app.staticTexts["至少添加一个节点后才能保存流程。"].exists)
        attachScreenshot("empty-draft-save-disabled")

        app.buttons["editor-back"].tap()
        XCTAssertTrue(app.buttons["home-new"].waitForExistence(timeout: 8))
        XCTAssertEqual(app.buttons.matching(identifier: "plan-start").count, 0)
        XCTAssertTrue(app.staticTexts["还没有流程"].exists)
    }

    func testEditingExistingPlanCannotBeSavedEmpty() {
        app.buttons["home-new"].tap()
        let nameField = app.textFields["plan-name-field"]
        XCTAssertTrue(nameField.waitForExistence(timeout: 8))
        replaceText(in: nameField, with: "Keep Me")
        addSingleCountStep(name: "Only Step")
        app.buttons["editor-save-top"].tap()
        XCTAssertTrue(app.staticTexts["Keep Me"].waitForExistence(timeout: 8))

        openEditorFromFirstPlan()
        app.buttons["文本编辑"].tap()
        let text = app.textViews["editor-text"]
        XCTAssertTrue(text.waitForExistence(timeout: 8))
        clearText(in: text)
        XCTAssertEqual(text.value as? String, "")
        let save = app.buttons["editor-save-top"]
        let disabled = NSPredicate(format: "isEnabled == false")
        expectation(for: disabled, evaluatedWith: save)
        waitForExpectations(timeout: 3)
        attachScreenshot("existing-plan-empty-save-disabled")

        app.buttons["editor-back"].tap()
        XCTAssertTrue(app.staticTexts["Keep Me"].waitForExistence(timeout: 8))
        app.buttons.matching(identifier: "plan-start").firstMatch.tap()
        XCTAssertTrue(app.buttons["setup-start"].waitForExistence(timeout: 8))
        XCTAssertTrue(app.staticTexts["Keep Me"].exists)
    }

    private func openEditorFromFirstPlan() {
        let card = app.buttons.matching(identifier: "plan-start").firstMatch
        XCTAssertTrue(card.waitForExistence(timeout: 8))
        card.tap()
        let more = app.buttons["plan-more"]
        XCTAssertTrue(more.waitForExistence(timeout: 8))
        more.tap()
        let edit = app.buttons["编辑流程"].firstMatch
        if edit.waitForExistence(timeout: 2) {
            edit.tap()
        } else {
            app.menuItems["编辑流程"].tap()
        }
        XCTAssertTrue(app.textFields["plan-name-field"].waitForExistence(timeout: 8))
    }

    private func tapMenuDelete() {
        let delete = app.buttons["删除流程"].firstMatch
        if delete.waitForExistence(timeout: 2) {
            delete.tap()
        } else {
            app.menuItems["删除流程"].tap()
        }
    }

    private func attachScreenshot(_ name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    private func clearText(in field: XCUIElement) {
        field.coordinate(withNormalizedOffset: CGVector(dx: 0.95, dy: 0.9)).tap()
        field.typeText(
            String(repeating: XCUIKeyboardKey.delete.rawValue, count: 200)
        )
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
        if !text.isEmpty {
            field.typeText(text)
        }
    }
}
