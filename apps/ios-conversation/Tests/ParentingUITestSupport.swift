import XCTest

/// Shared UI-test support for the parenting workspace (今天｜成长｜我们家 and the bottom composer).
///
/// Why this exists: the iOS 26 system `TabView` (feat/native-liquid-glass) does not expose the app's `parenting.tab.*`
/// identifiers, so tests must find the tab buttons by their position in the system tab bar. The custom floating bar that
/// iOS 17-25 (and the current main) still use does expose them, so every helper here falls back to the identifiers when
/// there is no system tab bar. Tests never talk to either bar directly; they call `ParentingUI`.
///
/// Single-row dual-state chrome (一行双态, the leaf chip on the composer) keeps `parenting.tab.*` in the accessibility
/// tree but not hittable while the composer occupies that slot. `selectTab` taps `parenting.chrome.typeChip` to open
/// pickTab in the same slot, then taps the tab. It does not assume a second row of always-visible tabs.
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
    /// Leaf chip on the composer row. Present only while that row is the input slot (a child exists, 今天 or 成长).
    static let typeChipID = "parenting.chrome.typeChip"
    /// Closes pickTab and returns the same slot to the composer. Not a second tab row.
    static let dismissPickTabID = "parenting.chrome.dismissPickTab"
    /// Container around `parenting.tab.*` in either the always-visible bar or the pickTab slot.
    static let tabBarContainerID = "parenting.tabBar"

    /// True when the app shows the iOS 26 system tab bar rather than the custom floating bar.
    static func usesSystemTabBar(_ app: XCUIApplication) -> Bool { app.tabBars.firstMatch.exists }

    /// True when either kind of tab bar is on screen.
    static func tabBarExists(_ app: XCUIApplication) -> Bool {
        usesSystemTabBar(app) || app.buttons[customTabIdentifierPrefix + Tab.today.rawValue].exists
    }

    /// The tab button for `tab`: by position in the system tab bar, or by `parenting.tab.<id>` on the custom bar.
    /// A minimized system bar (it collapses on scroll) shows only the selected tab; tapping it expands the bar first.
    /// When the same identifier is mounted twice during a slot crossfade, prefer the hittable one.
    static func tabButton(_ app: XCUIApplication, _ tab: Tab) -> XCUIElement {
        let bar = app.tabBars.firstMatch
        guard bar.exists else { return customTabButton(app, tab) }
        if bar.buttons.count < tabOrder.count, bar.buttons.firstMatch.exists, bar.buttons.firstMatch.isHittable {
            bar.buttons.firstMatch.tap()
            waitUntil(timeout: 3) { bar.buttons.count >= tabOrder.count }
        }
        return bar.buttons.element(boundBy: position(of: tab))
    }

    private static func customTabButton(_ app: XCUIApplication, _ tab: Tab) -> XCUIElement {
        let query = app.buttons.matching(NSPredicate(format: "identifier == %@", customTabIdentifierPrefix + tab.rawValue))
        if let hittable = query.allElementsBoundByIndex.first(where: \.isHittable) { return hittable }
        return query.firstMatch
    }

    /// Switches to `tab` and waits until it is selected. Fails with a message that names the tab, its position,
    /// the bar that was searched and the labels that were found.
    ///
    /// On the single-row composer the leaf chip is the visible tab control. The `parenting.tab.*` button for the
    /// current page stays selected but is not hittable until the chip opens pickTab; requiring a hit in that state
    /// fails every journey that merely confirms it is already on 今天.
    static func selectTab(_ app: XCUIApplication, _ tab: Tab, timeout: TimeInterval = 10,
                          file: StaticString = #filePath, line: UInt = #line) {
        let button = tabButton(app, tab)
        guard button.waitForExistence(timeout: timeout) else {
            XCTFail(tabFailure("tab button does not exist", tab, app), file: file, line: line)
            return
        }
        let current = tabButton(app, tab)
        // Already on this tab. A non-hittable button plus the leaf chip means the composer owns the row
        // and the page is already showing — do not require a tap. A covered button with no chip is still a failure.
        if current.isSelected && (current.isHittable || app.buttons[typeChipID].exists) {
            return
        }
        if !tabButton(app, tab).isHittable {
            revealPickTab(app, tab)
        }
        guard waitUntil(timeout: timeout, { tabButton(app, tab).isHittable }) else {
            XCTFail(tabFailure("tab button exists but is not hittable (open pickTab with \(typeChipID) when the composer occupies the row; otherwise a sheet, the keyboard or an alert is covering it)", tab, app),
                    file: file, line: line)
            return
        }
        if !tabButton(app, tab).isSelected { tabButton(app, tab).tap() }
        if !waitUntil(timeout: timeout, { tabButton(app, tab).isSelected }) {
            XCTFail(tabFailure("tab was tapped but never became selected", tab, app), file: file, line: line)
        }
    }

    /// Opens the same-slot tab picker when the composer is covering `parenting.tab.*`. No-op on the always-visible bar.
    private static func revealPickTab(_ app: XCUIApplication, _ tab: Tab) {
        if tabButton(app, tab).isHittable { return }
        let chip = app.buttons[typeChipID]
        guard chip.exists else { return }
        _ = waitUntil(timeout: 3) { chip.isHittable || tabButton(app, tab).isHittable }
        if tabButton(app, tab).isHittable || !chip.isHittable { return }
        chip.tap()
    }

    /// One line that says which bar was searched and what it contained; used in failure messages.
    static func tabBarSummary(_ app: XCUIApplication) -> String {
        let bar = app.tabBars.firstMatch
        if bar.exists {
            let labels = bar.buttons.allElementsBoundByIndex.map { $0.label }
            return "system tab bar with \(labels.count) button(s) \(labels)"
        }
        let tabs = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", customTabIdentifierPrefix))
            .allElementsBoundByIndex
        let ids = tabs.map { "\($0.identifier)\($0.isSelected ? " selected" : "")\($0.isHittable ? "" : " not-hittable")" }
        let chip = app.buttons[typeChipID]
        let chipNote = chip.exists ? "; \(typeChipID) '\(chip.label)' hittable=\(chip.isHittable)" : "; no \(typeChipID)"
        return ids.isEmpty && !chip.exists
            ? "no tab bar (neither a system bar nor parenting.tab.* buttons)"
            : "custom chrome [\(ids.joined(separator: ", "))]\(chipNote)"
    }

    private static func tabFailure(_ reason: String, _ tab: Tab, _ app: XCUIApplication) -> String {
        "Parenting tab '\(tab.rawValue)' (position \(position(of: tab)), title '\(tabTitles[tab] ?? "?")'): \(reason). "
            + "Found: \(tabBarSummary(app)).\n" + app.debugDescription
    }

    /// Top edge of whichever tab bar is on screen, or nil. Content below it is covered by the floating glass.
    /// When the single-row composer is showing, the tab buttons are not hittable; the slot frame is the clip edge.
    static func tabBarTop(_ app: XCUIApplication) -> CGFloat? {
        let bar = app.tabBars.firstMatch
        if bar.exists, !bar.frame.isEmpty { return bar.frame.minY }
        let tops = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", customTabIdentifierPrefix))
            .allElementsBoundByIndex.filter { $0.isHittable && !$0.frame.isEmpty }.map(\.frame.minY)
        if let top = tops.min() { return top }
        for id in [tabBarContainerID, composerBarID] {
            if let top = floatingSlotTop(app.otherElements[id]) { return top }
        }
        return nil
    }

    /// A bar-sized frame. Ignores a full-screen container and the tiny pickTab dismiss glyph.
    private static func floatingSlotTop(_ element: XCUIElement) -> CGFloat? {
        guard element.exists else { return nil }
        let frame = element.frame
        guard frame.height > 20, frame.height < 220, frame.minY > 1 else { return nil }
        return frame.minY
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

    /// The composer text control, whether XCTest surfaces the vertical field as a text field or a text view.
    static func composerTextInput(_ app: XCUIApplication) -> XCUIElement {
        let field = app.textFields[composerFieldID]
        if field.exists { return field }
        let view = app.textViews[composerFieldID]
        if view.exists { return view }
        return field
    }

    /// True when the composer row is the interactive slot (voice capsule or keyboard), so typing does not have to
    /// dismiss pickTab first.
    private static func composerSlotIsInteractive(_ app: XCUIApplication) -> Bool {
        [app.textFields[composerFieldID], app.textViews[composerFieldID],
         app.buttons[composerKeyboardToggleID], app.buttons[composerMicID]]
            .contains { $0.exists && $0.isHittable }
    }

    /// pickTab and the composer share one row. Typing is only possible after that row is the input slot again.
    private static func leavePickTabIfComposerCovered(_ app: XCUIApplication) {
        if composerSlotIsInteractive(app) { return }
        let tabs = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", customTabIdentifierPrefix))
            .allElementsBoundByIndex
        guard tabs.contains(where: \.isHittable) else { return }
        let dismiss = app.buttons[dismissPickTabID]
        if dismiss.exists, dismiss.isHittable {
            dismiss.tap()
        } else if let selected = tabs.first(where: { $0.isSelected && $0.isHittable }),
                  selected.identifier != customTabIdentifierPrefix + Tab.family.rawValue {
            // Tapping the already selected 今天/成长 chip reverses pickTab → input.
            selected.tap()
        }
        _ = waitUntil(timeout: 4) { composerSlotIsInteractive(app) }
    }

    /// Makes the composer's text field available and returns it. Switches to the keyboard layout when the voice-first
    /// idle layout is showing, and leaves pickTab first when that picker is covering the composer.
    @discardableResult
    static func showKeyboardComposer(_ app: XCUIApplication, file: StaticString = #filePath, line: UInt = #line) -> XCUIElement {
        leavePickTabIfComposerCovered(app)
        if waitUntil(timeout: 3, { Self.composerTextInput(app).exists }) {
            return composerTextInput(app)
        }
        var field = composerTextInput(app)
        let toggle = app.buttons[composerKeyboardToggleID]
        guard waitUntil(timeout: 8, { toggle.exists && toggle.isHittable }) else {
            XCTFail("The composer shows neither a hittable \(composerFieldID) nor \(composerKeyboardToggleID). Voice idle and pickTab hide the text field until the row is in keyboard mode.\n" + app.debugDescription,
                    file: file, line: line)
            return field
        }
        toggle.tap()
        field = composerTextInput(app)
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
