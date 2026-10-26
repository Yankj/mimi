import XCTest

/// Shared UI-test support for the parenting workspace (今天｜成长｜我们家 and the bottom composer).
///
/// Why this exists: the iOS 26 system `TabView` (feat/native-liquid-glass) does not expose the app's `parenting.tab.*`
/// identifiers, so tests must find the tab buttons by their position in the system tab bar. The custom floating bar that
/// iOS 17-25 (and the current main) still use does expose them, so every helper here falls back to the identifiers when
/// there is no system tab bar. Tests never talk to either bar directly; they call `ParentingUI`.
///
/// Accessibility identifiers are only *referenced* here, never defined or renamed (they live in the app sources).
enum ParentingUI {
    // MARK: Tabs

    /// The three tabs in on-screen order. The raw value is the suffix of the identifier `parenting.tab.<raw>`.
    enum Tab: String, CaseIterable {
        case today, growth, family
    }

    /// Index-to-tab mapping for the system tab bar (`app.tabBars.firstMatch.buttons.element(boundBy:)`).
    /// Must match the case order of `ParentingTab` in Sources/Features/Parenting/ParentingHomeView.swift.
    static let tabOrder: [Tab] = [.today, .growth, .family]

    /// English titles (the UI tests launch with `-AppleLanguages (en)`): button labels and large navigation titles.
    static let tabTitles: [Tab: String] = [.today: "Today", .growth: "Growth", .family: "Our family"]

    static func position(of tab: Tab) -> Int { tabOrder.firstIndex(of: tab) ?? 0 }

    /// Identifier prefix of the custom floating bar's buttons.
    static let customTabIdentifierPrefix = "parenting.tab."

    /// True when the app shows the iOS 26 system tab bar rather than the custom floating bar.
    static func usesSystemTabBar(_ app: XCUIApplication) -> Bool { app.tabBars.firstMatch.exists }

    /// True when either kind of tab bar is on screen.
    static func tabBarExists(_ app: XCUIApplication) -> Bool {
        usesSystemTabBar(app) || app.buttons[customTabIdentifierPrefix + Tab.today.rawValue].exists
    }

    /// The tab button for `tab`: by position in the system tab bar, or by `parenting.tab.<id>` on the custom bar.
    /// A minimized system bar (it collapses on scroll) shows only the selected tab; tapping it expands the bar first.
    static func tabButton(_ app: XCUIApplication, _ tab: Tab) -> XCUIElement {
        let bar = app.tabBars.firstMatch
        guard bar.exists else { return app.buttons[customTabIdentifierPrefix + tab.rawValue] }
        if bar.buttons.count < tabOrder.count, bar.buttons.firstMatch.exists, bar.buttons.firstMatch.isHittable {
            bar.buttons.firstMatch.tap()
            waitUntil(timeout: 3) { bar.buttons.count >= tabOrder.count }
        }
        return bar.buttons.element(boundBy: position(of: tab))
    }

    /// Switches to `tab` and waits until it is selected. Fails with a message that names the tab, its position,
    /// the bar that was searched and the labels that were found.
    static func selectTab(_ app: XCUIApplication, _ tab: Tab, timeout: TimeInterval = 10,
                          file: StaticString = #filePath, line: UInt = #line) {
        let button = tabButton(app, tab)
        guard button.waitForExistence(timeout: timeout) else {
            XCTFail(tabFailure("tab button does not exist", tab, app), file: file, line: line)
            return
        }
        guard waitUntil(timeout: timeout, { tabButton(app, tab).isHittable }) else {
            XCTFail(tabFailure("tab button exists but is not hittable (covered by a sheet, the keyboard or an alert?)", tab, app),
                    file: file, line: line)
            return
        }
        if !tabButton(app, tab).isSelected { tabButton(app, tab).tap() }
        if !waitUntil(timeout: timeout, { tabButton(app, tab).isSelected }) {
            XCTFail(tabFailure("tab was tapped but never became selected", tab, app), file: file, line: line)
        }
    }

    /// One line that says which bar was searched and what it contained; used in failure messages.
    static func tabBarSummary(_ app: XCUIApplication) -> String {
        let bar = app.tabBars.firstMatch
        if bar.exists {
            let labels = bar.buttons.allElementsBoundByIndex.map { $0.label }
            return "system tab bar with \(labels.count) button(s) \(labels)"
        }
        let ids = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", customTabIdentifierPrefix))
            .allElementsBoundByIndex.map { $0.identifier }
        return ids.isEmpty ? "no tab bar (neither a system bar nor parenting.tab.* buttons)"
                           : "custom floating bar with identifiers \(ids)"
    }

    private static func tabFailure(_ reason: String, _ tab: Tab, _ app: XCUIApplication) -> String {
        "Parenting tab '\(tab.rawValue)' (position \(position(of: tab)), title '\(tabTitles[tab] ?? "?")'): \(reason). "
            + "Found: \(tabBarSummary(app)).\n" + app.debugDescription
    }

    /// Top edge of whichever tab bar is on screen, or nil. Content below it is covered by the floating glass.
    static func tabBarTop(_ app: XCUIApplication) -> CGFloat? {
        let bar = app.tabBars.firstMatch
        if bar.exists, !bar.frame.isEmpty { return bar.frame.minY }
        let tops = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", customTabIdentifierPrefix))
            .allElementsBoundByIndex.filter { $0.isHittable }.map { $0.frame.minY }
        return tops.min()
    }

    // MARK: Composer (the one input card on 今天 and 成长)

    static let composerBarID = "parenting.natural.bar"
    static let composerFieldID = "parenting.natural.field"
    static let composerSendID = "parenting.natural.send"
    /// Voice-first idle layout (feat/native-liquid-glass #131). Absent on main until that branch lands.
    static let composerMicID = "parenting.composer.mic"
    static let composerKeyboardToggleID = "parenting.composer.keyboardToggle"
    static let plusMenuID = "parenting.plus.menu"
    static let plusManualArrangementID = "parenting.plus.manualArrangement"
    static let plusManualObservationID = "parenting.plus.manualObservation"
    /// Page-level manual entry that exists on main and on #131 (the + menu is an additional route to the same form).
    static let addArrangementButtonID = "parenting.addArrangement"

    static func composerBar(_ app: XCUIApplication) -> XCUIElement { app.otherElements[composerBarID] }

    /// The composer is voice-first on #131 (idle = + · hold-to-talk · keyboard) and always shows the text field on main.
    static func isVoiceFirstComposer(_ app: XCUIApplication) -> Bool { app.buttons[composerMicID].exists }

    /// Makes the composer's text field available and returns it. Switches to the keyboard layout when the voice-first
    /// idle layout is showing.
    @discardableResult
    static func showKeyboardComposer(_ app: XCUIApplication, file: StaticString = #filePath, line: UInt = #line) -> XCUIElement {
        let field = app.textFields[composerFieldID]
        if field.waitForExistence(timeout: 3) { return field }
        let toggle = app.buttons[composerKeyboardToggleID]
        guard toggle.waitForExistence(timeout: 10) else {
            XCTFail("The composer shows neither \(composerFieldID) nor \(composerKeyboardToggleID).\n" + app.debugDescription,
                    file: file, line: line)
            return field
        }
        toggle.tap()
        if !field.waitForExistence(timeout: 5) {
            XCTFail("Switching the composer to the keyboard layout did not show \(composerFieldID).\n" + app.debugDescription,
                    file: file, line: line)
        }
        return field
    }

    /// Opens the manual arrangement form from the composer's + menu when it exists, otherwise from the page button.
    /// Returns which route was used so a test can assert on it.
    @discardableResult
    static func openManualArrangement(_ app: XCUIApplication, file: StaticString = #filePath, line: UInt = #line) -> String {
        let plus = app.buttons[plusMenuID]
        if plus.exists {
            waitUntil(timeout: 10) { plus.isHittable && plus.isEnabled }
            plus.tap()
            let item = app.buttons[plusManualArrangementID]
            guard item.waitForExistence(timeout: 5) else {
                XCTFail("The + menu did not list \(plusManualArrangementID).\n" + app.debugDescription, file: file, line: line)
                return plusMenuID
            }
            item.tap()
            return plusManualArrangementID
        }
        let button = app.buttons[addArrangementButtonID]
        guard button.waitForExistence(timeout: 10) else {
            XCTFail("Neither \(plusMenuID) nor \(addArrangementButtonID) is on screen.\n" + app.debugDescription, file: file, line: line)
            return addArrangementButtonID
        }
        waitUntil(timeout: 10) { button.isHittable && button.isEnabled }
        button.tap()
        return addArrangementButtonID
    }

    // MARK: Waiting

    /// `parenting.saving` is the app's own "workspace is busy" indicator (ParentingWorkspaceModel.busy). While it shows, the
    /// editor forms are disabled (`.disabled(saving || model.busy)`), so typing during that window loses keystrokes.
    static let busyIndicatorID = "parenting.saving"

    /// Waits until the workspace is not busy. Returns false if it stayed busy for the whole timeout.
    @discardableResult
    static func waitUntilNotBusy(_ app: XCUIApplication, timeout: TimeInterval = 10) -> Bool {
        waitUntil(timeout: timeout) { !app.descendants(matching: .any)[busyIndicatorID].exists }
    }

    /// Polls `condition` on the main run loop. Returns the last evaluation.
    @discardableResult
    static func waitUntil(timeout: TimeInterval = 10, poll: TimeInterval = 0.1, _ condition: () -> Bool) -> Bool {
        let end = Date().addingTimeInterval(timeout)
        repeat {
            if condition() { return true }
            RunLoop.current.run(until: Date().addingTimeInterval(poll))
        } while Date() < end
        return condition()
    }
}
