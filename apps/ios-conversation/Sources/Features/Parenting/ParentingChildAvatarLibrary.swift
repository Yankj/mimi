import SwiftUI
import UIKit
import Observation

/// Decoded child photos for the views, backed by `ParentingChildAvatarStore` (local files; nothing leaves the device).
/// `@Observable` so every `ParentingChildAvatar` redraws when a photo is set, removed or purged.
@MainActor @Observable final class ParentingChildAvatarLibrary {
    static let shared = ParentingChildAvatarLibrary()

    private(set) var revision = 0
    @ObservationIgnored private var cache: [UUID: UIImage] = [:]
    @ObservationIgnored private let store: ParentingChildAvatarStore

    init(store: ParentingChildAvatarStore = ParentingChildAvatarStore()) { self.store = store }

    /// The parent's photo for this child, or nil when the child uses the default plush cat.
    func image(for childID: UUID) -> UIImage? {
        _ = revision   // registers the dependency for the calling view
        if let cached = cache[childID] { return cached }
        guard let data = store.load(childID: childID), let image = UIImage(data: data) else { return nil }
        cache[childID] = image
        return image
    }

    func hasPhoto(for childID: UUID) -> Bool {
        _ = revision
        return cache[childID] != nil || store.hasPhoto(childID: childID)
    }

    func setPhoto(_ data: Data, for childID: UUID) throws {
        try store.save(data, childID: childID)
        cache[childID] = nil
        revision += 1
    }

    /// Back to the default plush cat.
    func removePhoto(for childID: UUID) {
        store.remove(childID: childID)
        cache[childID] = nil
        revision += 1
    }

    /// Photos of children that were removed from the ledger go with them.
    func prune(keeping childIDs: Set<UUID>) {
        let stale = store.storedChildIDs().subtracting(childIDs)
        guard !stale.isEmpty else { return }
        store.prune(keeping: childIDs)
        for id in stale { cache[id] = nil }
        revision += 1
    }

    /// Sign-out: the files were purged by `AccountLocalData.purge`; this drops what is decoded in memory.
    func resetAfterSignOut() {
        store.purgeAll()
        cache = [:]
        revision += 1
    }
}

/// Turns a picked photo into the stored avatar: upright, bounded, square-cropped, JPEG.
enum ParentingAvatarImageProcessor {
    /// Re-renders the picture upright at 1x with its longest edge at most `maxSide` px, so crop maths works in pixels.
    static func normalized(_ image: UIImage, maxSide: CGFloat = 2048) -> UIImage {
        let size = image.size
        guard size.width > 0, size.height > 0 else { return image }
        let factor = min(1, maxSide / max(size.width, size.height))
        let target = CGSize(width: max(1, (size.width * factor).rounded()), height: max(1, (size.height * factor).rounded()))
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        return UIGraphicsImageRenderer(size: target, format: format).image { _ in
            image.draw(in: CGRect(origin: .zero, size: target))
        }
    }

    /// Square JPEG of `rect` (image pixels) from an image produced by `normalized`.
    static func avatarJPEG(from image: UIImage, rect: ParentingAvatarCropRect) -> Data? {
        let side = ParentingAvatarCrop.outputSide(forCropSide: rect.side)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        let factor = CGFloat(side) / CGFloat(max(rect.side, 1))
        let rendered = UIGraphicsImageRenderer(size: CGSize(width: side, height: side), format: format).image { context in
            UIColor.white.setFill()
            context.fill(CGRect(x: 0, y: 0, width: side, height: side))
            image.draw(in: CGRect(x: -CGFloat(rect.x) * factor, y: -CGFloat(rect.y) * factor,
                                  width: image.size.width * factor, height: image.size.height * factor))
        }
        var quality: CGFloat = 0.82
        var data = rendered.jpegData(compressionQuality: quality)
        while let current = data, current.count > ParentingChildAvatarStore.maxBytes, quality > 0.3 {
            quality -= 0.1
            data = rendered.jpegData(compressionQuality: quality)
        }
        guard let result = data, result.count <= ParentingChildAvatarStore.maxBytes else { return nil }
        return result
    }
}
