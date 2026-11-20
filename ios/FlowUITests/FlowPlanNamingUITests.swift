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
        app.buttons["editor-save-top"].tap()
        XCTAssertEqual(nameField.value as? String, "Morning Flow")
        attachScreenshot("plan-name-saved")

        app.buttons["editor-back"].tap()
        XCTAssertTrue(app.staticTexts["Morning Flow"].waitForExistence(timeout: 8))

        // Reopen and rename the existing plan.
        let planButton = app.buttons
            .matching(NSPredicate(format: "label CONTAINS %@", "Morning Flow"))
            .firstMatch
        XCTAssertTrue(planButton.waitForExistence(timeout: 8))
        planButton.tap()

        XCTAssertTrue(nameField.waitForExistence(timeout: 8))
        XCTAssertEqual(nameField.value as? String, "Morning Flow")
        replaceText(in: nameField, with: "Evening Flow")
        app.buttons["editor-save-top"].tap()
        app.buttons["editor-back"].tap()
        XCTAssertTrue(app.staticTexts["Evening Flow"].waitForExistence(timeout: 8))
        XCTAssertFalse(app.staticTexts["Morning Flow"].exists)

        // Empty names are rejected and must not overwrite the persisted name.
        let eveningButton = app.buttons
            .matching(NSPredicate(format: "label CONTAINS %@", "Evening Flow"))
            .firstMatch
        XCTAssertTrue(eveningButton.waitForExistence(timeout: 8))
        eveningButton.tap()

        let reopenedField = app.textFields["plan-name-field"]
        XCTAssertTrue(reopenedField.waitForExistence(timeout: 8))
        replaceText(in: reopenedField, with: "")
        app.buttons["editor-save-top"].tap()
        XCTAssertTrue(app.staticTexts["流程名称不能为空。"].waitForExistence(timeout: 8))
        attachScreenshot("plan-name-empty-error")
        app.buttons["editor-back"].tap()
        XCTAssertTrue(app.staticTexts["Evening Flow"].waitForExistence(timeout: 8))

        // Overlong names are rejected as well.
        let finalButton = app.buttons
            .matching(NSPredicate(format: "label CONTAINS %@", "Evening Flow"))
            .firstMatch
        XCTAssertTrue(finalButton.waitForExistence(timeout: 8))
        finalButton.tap()
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
        app.buttons["editor-save-top"].tap()
        app.buttons["editor-back"].tap()

        let planTitle = app.staticTexts["Delete Me"]
        XCTAssertTrue(planTitle.waitForExistence(timeout: 8))

        // Cancel must keep the plan.
        app.buttons["plan-more"].tap()
        let menuDelete = app.buttons["删除"].firstMatch
        if menuDelete.waitForExistence(timeout: 2) {
            menuDelete.tap()
        } else {
            app.menuItems["删除"].tap()
        }
        let alert = app.alerts["删除这个流程？"]
        XCTAssertTrue(alert.waitForExistence(timeout: 5))
        XCTAssertTrue(alert.staticTexts["删除后，这个流程及其节点将无法恢复。"].exists)
        attachScreenshot("delete-confirm")
        alert.buttons["取消"].tap()
        XCTAssertTrue(planTitle.waitForExistence(timeout: 5))

        // Confirm must delete and refresh the list.
        app.buttons["plan-more"].tap()
        let menuDeleteAgain = app.buttons["删除"].firstMatch
        if menuDeleteAgain.waitForExistence(timeout: 2) {
            menuDeleteAgain.tap()
        } else {
            app.menuItems["删除"].tap()
        }
        let confirmAlert = app.alerts["删除这个流程？"]
        XCTAssertTrue(confirmAlert.waitForExistence(timeout: 5))
        confirmAlert.buttons["删除"].tap()
        XCTAssertTrue(app.staticTexts["还没有流程"].waitForExistence(timeout: 8))
        XCTAssertFalse(app.staticTexts["Delete Me"].exists)
        attachScreenshot("delete-complete")
    }

    private func attachScreenshot(_ name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
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
