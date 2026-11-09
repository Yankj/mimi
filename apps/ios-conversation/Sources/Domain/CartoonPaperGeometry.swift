import CoreGraphics
import Foundation

/// Normalized paper corners. Origin is the top-left of the photo, matching the crop UI.
struct PaperCorner: Equatable, Sendable {
    var x: CGFloat
    var y: CGFloat

    func clamped() -> PaperCorner {
        PaperCorner(x: min(1, max(0, x)), y: min(1, max(0, y)))
    }
}

/// The quadrilateral that is the drawing paper. Pixels outside it are the table, fingers and clutter.
struct PaperQuad: Equatable, Sendable {
    var topLeft: PaperCorner
    var topRight: PaperCorner
    var bottomRight: PaperCorner
    var bottomLeft: PaperCorner

    static let insetDefault = PaperQuad(
        topLeft: PaperCorner(x: 0.08, y: 0.08),
        topRight: PaperCorner(x: 0.92, y: 0.08),
        bottomRight: PaperCorner(x: 0.92, y: 0.92),
        bottomLeft: PaperCorner(x: 0.08, y: 0.92))

    func clamped() -> PaperQuad {
        PaperQuad(topLeft: topLeft.clamped(), topRight: topRight.clamped(), bottomRight: bottomRight.clamped(), bottomLeft: bottomLeft.clamped())
    }

    /// Vision reports a bottom-left origin. The crop UI uses a top-left origin.
    static func fromVision(topLeft: CGPoint, topRight: CGPoint, bottomRight: CGPoint, bottomLeft: CGPoint) -> PaperQuad {
        func flip(_ point: CGPoint) -> PaperCorner { PaperCorner(x: point.x, y: 1 - point.y) }
        return PaperQuad(topLeft: flip(topLeft), topRight: flip(topRight), bottomRight: flip(bottomRight), bottomLeft: flip(bottomLeft)).clamped()
    }

    func contains(_ point: PaperCorner) -> Bool {
        let polygon = [topLeft, topRight, bottomRight, bottomLeft]
        var inside = false
        var previous = polygon.count - 1
        for index in polygon.indices {
            let current = polygon[index]
            let earlier = polygon[previous]
            let crosses = (current.y > point.y) != (earlier.y > point.y)
            let xAtY = (earlier.x - current.x) * (point.y - current.y) / ((earlier.y - current.y) == 0 ? 0.0000001 : (earlier.y - current.y)) + current.x
            if crosses && point.x < xAtY { inside.toggle() }
            previous = index
        }
        return inside
    }
}

enum CartoonWhiteBalance {
    struct Gains: Equatable, Sendable {
        var red: Double
        var green: Double
        var blue: Double
    }

    /// Gray-world gains. Channels already near the average stay close to 1.
    static func grayWorld(_ samples: [(r: UInt8, g: UInt8, b: UInt8)]) -> Gains {
        guard !samples.isEmpty else { return Gains(red: 1, green: 1, blue: 1) }
        let count = Double(samples.count)
        let red = samples.reduce(0.0) { $0 + Double($1.r) } / count
        let green = samples.reduce(0.0) { $0 + Double($1.g) } / count
        let blue = samples.reduce(0.0) { $0 + Double($1.b) } / count
        let gray = (red + green + blue) / 3
        func gain(_ channel: Double) -> Double {
            guard channel > 1 else { return 1 }
            return min(2.5, max(0.4, gray / channel))
        }
        return Gains(red: gain(red), green: gain(green), blue: gain(blue))
    }
}

enum PaperQuadMapping {
    struct Letterbox: Equatable, Sendable {
        var origin: CGPoint
        var size: CGSize
    }

    static func letterbox(image: CGSize, view: CGSize) -> Letterbox {
        guard image.width > 0, image.height > 0, view.width > 0, view.height > 0 else {
            return Letterbox(origin: .zero, size: .zero)
        }
        let scale = min(view.width / image.width, view.height / image.height)
        let size = CGSize(width: image.width * scale, height: image.height * scale)
        let origin = CGPoint(x: (view.width - size.width) / 2, y: (view.height - size.height) / 2)
        return Letterbox(origin: origin, size: size)
    }

    static func normalized(_ point: CGPoint, image: CGSize, view: CGSize) -> PaperCorner {
        let box = letterbox(image: image, view: view)
        guard box.size.width > 0, box.size.height > 0 else { return PaperCorner(x: 0, y: 0) }
        return PaperCorner(x: (point.x - box.origin.x) / box.size.width, y: (point.y - box.origin.y) / box.size.height).clamped()
    }
}
