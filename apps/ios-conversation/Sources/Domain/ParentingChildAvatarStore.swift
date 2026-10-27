import Foundation

/// A parent-chosen child photo, kept only on this device.
///
/// The ledger's `ParentingChild` has no avatar field (id, name, createdAt) and the server schema is not touched, so the photo is a small
/// JPEG file named after the child's id inside one directory. The directory is removed at sign-out together with the other
/// account-bound local data (`AccountLocalData.purge`), excluded from backups, and pruned when a child is removed.
struct ParentingChildAvatarStore: @unchecked Sendable {
    enum StoreError: Error, Equatable { case empty, tooLarge }

    static let directoryName = "mimi-child-avatars"
    /// A cropped avatar is at most 512 x 512 px; this is the hard ceiling for what is written.
    static let maxBytes = 512 * 1024
    static var defaultDirectory: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent(directoryName, isDirectory: true)
    }

    let directory: URL
    init(directory: URL = ParentingChildAvatarStore.defaultDirectory) { self.directory = directory }

    func fileURL(for childID: UUID) -> URL {
        directory.appendingPathComponent(childID.uuidString.lowercased() + ".jpg", isDirectory: false)
    }

    func save(_ data: Data, childID: UUID) throws {
        guard !data.isEmpty else { throw StoreError.empty }
        guard data.count <= Self.maxBytes else { throw StoreError.tooLarge }
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        var options: Data.WritingOptions = [.atomic]
        #if os(iOS)
        options.insert(.completeFileProtection)
        #endif
        try data.write(to: fileURL(for: childID), options: options)
        var folder = directory
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? folder.setResourceValues(values)
    }

    func load(childID: UUID) -> Data? {
        try? Data(contentsOf: fileURL(for: childID))
    }

    func hasPhoto(childID: UUID) -> Bool {
        FileManager.default.fileExists(atPath: fileURL(for: childID).path)
    }

    func remove(childID: UUID) {
        try? FileManager.default.removeItem(at: fileURL(for: childID))
    }

    /// Child ids that currently have a stored photo.
    func storedChildIDs() -> Set<UUID> {
        let names = (try? FileManager.default.contentsOfDirectory(atPath: directory.path)) ?? []
        return Set(names.compactMap { name in
            guard name.hasSuffix(".jpg") else { return nil }
            return UUID(uuidString: String(name.dropLast(4)))
        })
    }

    /// Drops photos whose child no longer exists (a removed child, or a file left by an earlier run).
    func prune(keeping childIDs: Set<UUID>) {
        for id in storedChildIDs().subtracting(childIDs) { remove(childID: id) }
    }

    func purgeAll() { Self.purgeAll(directory: directory) }
    static func purgeAll(directory: URL) { try? FileManager.default.removeItem(at: directory) }
}

/// Where the square crop of a picked photo lands, in image pixels.
struct ParentingAvatarCropRect: Equatable {
    var x: Double
    var y: Double
    var side: Double
}

/// Pure geometry of the avatar crop screen. The picture is shown with its shorter side filling a square viewport at zoom 1;
/// `offset` is how far the picture's centre sits from the viewport's centre, in viewport points. The result is always a square
/// that lies inside the picture, so the saved file never has empty edges.
enum ParentingAvatarCrop {
    static let maxZoom: Double = 4
    /// Longest edge of the stored avatar. It is shown at most ~96 pt wide, so 512 px is sharp on 3x screens.
    static let maxOutputSide = 512

    static func clampedZoom(_ zoom: Double) -> Double {
        guard zoom.isFinite else { return 1 }
        return min(max(zoom, 1), maxZoom)
    }

    static func displayedSize(imageWidth: Double, imageHeight: Double, viewport: Double, zoom: Double) -> (width: Double, height: Double) {
        let shorter = max(1, min(imageWidth, imageHeight))
        let scale = viewport / shorter * clampedZoom(zoom)
        return (imageWidth * scale, imageHeight * scale)
    }

    /// Keeps the viewport fully covered by the picture.
    static func clampedOffset(x: Double, y: Double, imageWidth: Double, imageHeight: Double, viewport: Double, zoom: Double) -> (x: Double, y: Double) {
        let shown = displayedSize(imageWidth: imageWidth, imageHeight: imageHeight, viewport: viewport, zoom: zoom)
        let limitX = max(0, (shown.width - viewport) / 2)
        let limitY = max(0, (shown.height - viewport) / 2)
        let safeX = x.isFinite ? x : 0
        let safeY = y.isFinite ? y : 0
        return (min(max(safeX, -limitX), limitX), min(max(safeY, -limitY), limitY))
    }

    static func cropRect(imageWidth: Double, imageHeight: Double, viewport: Double, zoom: Double, offsetX: Double, offsetY: Double) -> ParentingAvatarCropRect {
        let shorter = max(1, min(imageWidth, imageHeight))
        let scale = viewport / shorter * clampedZoom(zoom)
        let offset = clampedOffset(x: offsetX, y: offsetY, imageWidth: imageWidth, imageHeight: imageHeight, viewport: viewport, zoom: zoom)
        let side = viewport / scale
        let x = (imageWidth * scale / 2 - viewport / 2 - offset.x) / scale
        let y = (imageHeight * scale / 2 - viewport / 2 - offset.y) / scale
        return ParentingAvatarCropRect(x: min(max(x, 0), max(0, imageWidth - side)),
                                       y: min(max(y, 0), max(0, imageHeight - side)),
                                       side: side)
    }

    static func outputSide(forCropSide side: Double) -> Int {
        guard side.isFinite, side >= 1 else { return 1 }
        return min(maxOutputSide, Int(side.rounded()))
    }
}
