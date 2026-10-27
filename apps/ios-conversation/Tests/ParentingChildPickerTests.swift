import Foundation
import UIKit
import XCTest
@testable import cat

/// Child switcher selection, child photo storage and purge, avatar crop geometry, and what removing a child deletes.
/// Everything here is synthetic and local: no network, no photo library, no ledger store.
final class ParentingChildPickerTests: XCTestCase {
    private let day = Date(timeIntervalSince1970: 1_800_000_000)
    private let a = UUID(uuidString: "A0000000-0000-4000-8000-000000000001")!
    private let b = UUID(uuidString: "B0000000-0000-4000-8000-000000000002")!
    private let ghost = UUID(uuidString: "C0000000-0000-4000-8000-000000000003")!

    private func tempDirectory() -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("mimi-avatar-tests-" + UUID().uuidString, isDirectory: true)
        addTeardownBlock { try? FileManager.default.removeItem(at: url) }
        return url
    }

    // MARK: Switcher selection

    func testSelectedChipFollowsTheHostChoice() {
        XCTAssertEqual(ParentingChildSwitcherRules.resolve(selected: b, childIDs: [a, b], allowsAll: false), .child(b))
        XCTAssertEqual(ParentingChildSwitcherRules.resolve(selected: b, childIDs: [a, b], allowsAll: true), .child(b))
    }

    func testNoChoiceMeansAllWhereAllExistsAndTheFirstChildElsewhere() {
        XCTAssertEqual(ParentingChildSwitcherRules.resolve(selected: nil, childIDs: [a, b], allowsAll: true), .all)
        XCTAssertEqual(ParentingChildSwitcherRules.resolve(selected: nil, childIDs: [a, b], allowsAll: false), .child(a))
        XCTAssertEqual(ParentingChildSwitcherRules.resolve(selected: ghost, childIDs: [a, b], allowsAll: false), .child(a),
                       "a removed child never leaves the strip with nothing selected")
        XCTAssertEqual(ParentingChildSwitcherRules.resolve(selected: ghost, childIDs: [a, b], allowsAll: true), .all)
    }

    func testOneChildIsAlwaysShownAsSelectedAndHasNoAllChip() {
        XCTAssertFalse(ParentingChildSwitcherRules.showsAllChip(childCount: 1, allowsAll: true))
        XCTAssertTrue(ParentingChildSwitcherRules.showsAllChip(childCount: 2, allowsAll: true))
        XCTAssertFalse(ParentingChildSwitcherRules.showsAllChip(childCount: 2, allowsAll: false))
        XCTAssertEqual(ParentingChildSwitcherRules.resolve(selected: nil, childIDs: [a], allowsAll: true), .child(a),
                       "with a single child the page still says who it is about")
    }

    func testChipValueIsTheChildIDOrNilForAll() {
        XCTAssertEqual(ParentingChildSwitcherRules.value(for: .child(a)), a)
        XCTAssertNil(ParentingChildSwitcherRules.value(for: .all))
    }

    // MARK: Photo storage

    func testSavedPhotoComesBackAndRemoveRestoresTheDefault() throws {
        let store = ParentingChildAvatarStore(directory: tempDirectory())
        XCTAssertNil(store.load(childID: a))
        XCTAssertFalse(store.hasPhoto(childID: a))
        let bytes = Data([0xFF, 0xD8, 0xFF, 0xD9])
        try store.save(bytes, childID: a)
        XCTAssertEqual(store.load(childID: a), bytes)
        XCTAssertTrue(store.hasPhoto(childID: a))
        XCTAssertNil(store.load(childID: b), "a photo belongs to one child only")
        store.remove(childID: a)
        XCTAssertNil(store.load(childID: a))
        XCTAssertFalse(store.hasPhoto(childID: a))
    }

    func testStoreRefusesEmptyAndOversizedData() {
        let store = ParentingChildAvatarStore(directory: tempDirectory())
        XCTAssertThrowsError(try store.save(Data(), childID: a)) { XCTAssertEqual($0 as? ParentingChildAvatarStore.StoreError, .empty) }
        let tooBig = Data(count: ParentingChildAvatarStore.maxBytes + 1)
        XCTAssertThrowsError(try store.save(tooBig, childID: a)) { XCTAssertEqual($0 as? ParentingChildAvatarStore.StoreError, .tooLarge) }
        XCTAssertFalse(store.hasPhoto(childID: a))
    }

    func testPruneKeepsOnlyExistingChildren() throws {
        let store = ParentingChildAvatarStore(directory: tempDirectory())
        try store.save(Data([1]), childID: a)
        try store.save(Data([2]), childID: b)
        XCTAssertEqual(store.storedChildIDs(), [a, b])
        store.prune(keeping: [a])
        XCTAssertEqual(store.storedChildIDs(), [a], "the photo of a removed child goes with the child")
    }

    func testSignOutPurgeRemovesEveryPhotoAndNothingElse() throws {
        let directory = tempDirectory()
        let store = ParentingChildAvatarStore(directory: directory)
        try store.save(Data([1]), childID: a)
        try store.save(Data([2]), childID: b)
        let name = "mimi.avatar.tests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: name))
        defer { defaults.removePersistentDomain(forName: name) }
        defaults.set("keep", forKey: "mimi.avatar.tests.unrelated")
        let unrelated = directory.deletingLastPathComponent().appendingPathComponent("mimi-avatar-tests-unrelated-" + UUID().uuidString)
        try Data([9]).write(to: unrelated)
        addTeardownBlock { try? FileManager.default.removeItem(at: unrelated) }

        AccountLocalData.purge(defaults: defaults, avatarDirectory: directory)

        XCTAssertTrue(store.storedChildIDs().isEmpty, "photos must not survive sign-out")
        XCTAssertFalse(FileManager.default.fileExists(atPath: directory.path))
        XCTAssertEqual(defaults.string(forKey: "mimi.avatar.tests.unrelated"), "keep")
        XCTAssertTrue(FileManager.default.fileExists(atPath: unrelated.path))
    }

    @MainActor func testLibraryShowsSetPhotoThenDefaultAfterRemoveAndPurge() throws {
        let store = ParentingChildAvatarStore(directory: tempDirectory())
        let library = ParentingChildAvatarLibrary(store: store)
        XCTAssertNil(library.image(for: a))
        let data = try XCTUnwrap(UIGraphicsImageRenderer(size: CGSize(width: 40, height: 40)).jpegData(withCompressionQuality: 0.8) { context in
            UIColor.orange.setFill(); context.fill(CGRect(x: 0, y: 0, width: 40, height: 40))
        })
        let before = library.revision
        try library.setPhoto(data, for: a)
        XCTAssertGreaterThan(library.revision, before, "views observe the revision, so a new photo redraws them")
        XCTAssertNotNil(library.image(for: a))
        XCTAssertTrue(library.hasPhoto(for: a))
        library.removePhoto(for: a)
        XCTAssertNil(library.image(for: a))
        XCTAssertFalse(library.hasPhoto(for: a))
        try library.setPhoto(data, for: a); try library.setPhoto(data, for: b)
        library.prune(keeping: [b])
        XCTAssertNil(library.image(for: a)); XCTAssertNotNil(library.image(for: b))
        library.resetAfterSignOut()
        XCTAssertNil(library.image(for: b))
        XCTAssertTrue(store.storedChildIDs().isEmpty)
    }

    // MARK: Crop geometry

    func testCentredCropOfALandscapePictureIsTheMiddleSquare() {
        let rect = ParentingAvatarCrop.cropRect(imageWidth: 800, imageHeight: 400, viewport: 280, zoom: 1, offsetX: 0, offsetY: 0)
        XCTAssertEqual(rect.side, 400, accuracy: 0.001)
        XCTAssertEqual(rect.x, 200, accuracy: 0.001)
        XCTAssertEqual(rect.y, 0, accuracy: 0.001)
    }

    func testZoomShrinksTheCropAndDraggingMovesItTheOtherWay() {
        let zoomed = ParentingAvatarCrop.cropRect(imageWidth: 400, imageHeight: 400, viewport: 280, zoom: 2, offsetX: 0, offsetY: 0)
        XCTAssertEqual(zoomed.side, 200, accuracy: 0.001)
        XCTAssertEqual(zoomed.x, 100, accuracy: 0.001)
        // Dragging the picture right shows more of its left side.
        let dragged = ParentingAvatarCrop.cropRect(imageWidth: 400, imageHeight: 400, viewport: 280, zoom: 2, offsetX: 70, offsetY: 0)
        XCTAssertEqual(dragged.x, 50, accuracy: 0.001)
    }

    func testOffsetIsClampedSoTheCropStaysInsideThePicture() {
        let far = ParentingAvatarCrop.cropRect(imageWidth: 400, imageHeight: 400, viewport: 280, zoom: 2, offsetX: 5_000, offsetY: -5_000)
        XCTAssertGreaterThanOrEqual(far.x, 0); XCTAssertGreaterThanOrEqual(far.y, 0)
        XCTAssertLessThanOrEqual(far.x + far.side, 400.0001); XCTAssertLessThanOrEqual(far.y + far.side, 400.0001)
        let atOne = ParentingAvatarCrop.clampedOffset(x: 50, y: 50, imageWidth: 400, imageHeight: 400, viewport: 280, zoom: 1)
        XCTAssertEqual(atOne.x, 0); XCTAssertEqual(atOne.y, 0, "nothing to drag when the picture exactly fills the circle")
        XCTAssertEqual(ParentingAvatarCrop.clampedZoom(99), ParentingAvatarCrop.maxZoom)
        XCTAssertEqual(ParentingAvatarCrop.clampedZoom(0.2), 1)
        XCTAssertEqual(ParentingAvatarCrop.clampedZoom(.nan), 1)
    }

    func testEveryCropLiesInsideThePicture() {
        for (w, h) in [(300.0, 900.0), (900.0, 300.0), (512.0, 512.0)] {
            for zoom in [1.0, 1.5, 3.2, 4.0] {
                for offset in [-400.0, -37.0, 0, 55.0, 400.0] {
                    let r = ParentingAvatarCrop.cropRect(imageWidth: w, imageHeight: h, viewport: 280, zoom: zoom, offsetX: offset, offsetY: -offset)
                    XCTAssertGreaterThanOrEqual(r.x, -0.0001); XCTAssertGreaterThanOrEqual(r.y, -0.0001)
                    XCTAssertLessThanOrEqual(r.x + r.side, w + 0.0001); XCTAssertLessThanOrEqual(r.y + r.side, h + 0.0001)
                }
            }
        }
    }

    // MARK: Image processing

    private func picture(width: Int, height: Int) -> UIImage {
        let format = UIGraphicsImageRendererFormat(); format.scale = 1; format.opaque = true
        return UIGraphicsImageRenderer(size: CGSize(width: width, height: height), format: format).image { context in
            UIColor.systemTeal.setFill(); context.fill(CGRect(x: 0, y: 0, width: width, height: height))
            UIColor.systemPink.setFill(); context.fill(CGRect(x: 0, y: 0, width: width / 2, height: height))
        }
    }

    func testAvatarJPEGIsASmallSquare() throws {
        let source = ParentingAvatarImageProcessor.normalized(picture(width: 3000, height: 2000))
        XCTAssertLessThanOrEqual(max(source.size.width, source.size.height), 2048)
        let rect = ParentingAvatarCrop.cropRect(imageWidth: Double(source.size.width), imageHeight: Double(source.size.height),
                                                viewport: 280, zoom: 1, offsetX: 0, offsetY: 0)
        let data = try XCTUnwrap(ParentingAvatarImageProcessor.avatarJPEG(from: source, rect: rect))
        XCTAssertLessThanOrEqual(data.count, ParentingChildAvatarStore.maxBytes)
        let decoded = try XCTUnwrap(UIImage(data: data))
        XCTAssertEqual(decoded.size.width * decoded.scale, decoded.size.height * decoded.scale, accuracy: 0.5, "square")
        XCTAssertLessThanOrEqual(decoded.size.width * decoded.scale, CGFloat(ParentingAvatarCrop.maxOutputSide) + 0.5)
        XCTAssertNoThrow(try ParentingChildAvatarStore(directory: tempDirectory()).save(data, childID: a))
    }

    func testSmallPicturesAreNotUpscaled() throws {
        let source = ParentingAvatarImageProcessor.normalized(picture(width: 120, height: 90))
        let rect = ParentingAvatarCrop.cropRect(imageWidth: 120, imageHeight: 90, viewport: 280, zoom: 1, offsetX: 0, offsetY: 0)
        let data = try XCTUnwrap(ParentingAvatarImageProcessor.avatarJPEG(from: source, rect: rect))
        let decoded = try XCTUnwrap(UIImage(data: data))
        XCTAssertEqual(decoded.size.width * decoded.scale, 90, accuracy: 0.5)
    }

    // MARK: Removing a child

    func testRemovalSummaryCountsWhatDeleteChildWouldRemove() throws {
        let kid = ParentingChild(id: a, name: "Synthetic A", createdAt: day)
        let other = ParentingChild(id: b, name: "Synthetic B", createdAt: day)
        let goal = ParentingGoal(id: UUID(), childID: a, title: "Goal", createdAt: day)
        func arrangement(_ child: UUID, _ state: ParentingArrangementState) -> ParentingArrangement {
            ParentingArrangement(id: UUID(), childID: child, title: "Plan", dueAt: day, goalID: nil, state: state, sourceText: "", createdAt: day, updatedAt: day)
        }
        func record(_ child: UUID, _ kind: ParentingRecordKind) -> ParentingRecord {
            ParentingRecord(id: UUID(), childID: child, goalID: nil, arrangementID: nil, kind: kind, text: "t", sourceText: "", createdAt: day, updatedAt: day)
        }
        let workspace = ParentingWorkspace(
            children: [kid, other], goals: [goal],
            arrangements: [arrangement(a, .draft), arrangement(a, .completed), arrangement(a, .cancelled), arrangement(b, .planned)],
            records: [record(a, .observation), record(a, .observation), record(a, .completion), record(b, .observation)])
        let summary = try XCTUnwrap(ParentingChildRemovalSummary.summary(of: a, in: workspace))
        XCTAssertEqual(summary, ParentingChildRemovalSummary(name: "Synthetic A", goalCount: 1, arrangementCount: 3, observationCount: 2, completionCount: 1))
        XCTAssertEqual(summary.recordCount, 3)
        XCTAssertFalse(summary.isEmpty)
        // The summary must match the real command: afterwards nothing of this child is left.
        let after = try workspace.applying(.deleteChild(id: a))
        XCTAssertEqual(after.arrangements.count, 1); XCTAssertEqual(after.records.count, 1); XCTAssertTrue(after.goals.isEmpty)
        XCTAssertNil(ParentingChildRemovalSummary.summary(of: a, in: after))
        let empty = try XCTUnwrap(ParentingChildRemovalSummary.summary(of: b, in: ParentingWorkspace(children: [other])))
        XCTAssertTrue(empty.isEmpty)
    }
}
