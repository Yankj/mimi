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

/// Byte order of a 32-bit device-RGB pixel. iOS photo buffers are usually BGRA.
enum CartoonPixelOrder {
    enum Layout: Equatable { case rgba, bgra, argb, abgr }

    static func layout(bitmapInfo: CGBitmapInfo) -> Layout {
        let alphaRaw = bitmapInfo.rawValue & CGBitmapInfo.alphaInfoMask.rawValue
        let alpha = CGImageAlphaInfo(rawValue: alphaRaw) ?? .none
        let little = bitmapInfo.contains(.byteOrder32Little)
        let alphaFirst: Bool
        switch alpha {
        case .premultipliedFirst, .first, .noneSkipFirst: alphaFirst = true
        default: alphaFirst = false
        }
        if little { return alphaFirst ? .bgra : .abgr }
        return alphaFirst ? .argb : .rgba
    }

    static func rgb(_ bytes: UnsafePointer<UInt8>, layout: Layout) -> (r: UInt8, g: UInt8, b: UInt8) {
        switch layout {
        case .rgba: return (bytes[0], bytes[1], bytes[2])
        case .bgra: return (bytes[2], bytes[1], bytes[0])
        case .argb: return (bytes[1], bytes[2], bytes[3])
        case .abgr: return (bytes[3], bytes[2], bytes[1])
        }
    }

    static func rgb(_ pixel: (UInt8, UInt8, UInt8, UInt8), layout: Layout) -> (r: UInt8, g: UInt8, b: UInt8) {
        let bytes = [pixel.0, pixel.1, pixel.2, pixel.3]
        return bytes.withUnsafeBufferPointer { buffer in rgb(buffer.baseAddress!, layout: layout) }
    }
}

/// Divide by a wide blur to lift lamp shadows, then lift shallow creases.
/// A pixel much darker than its neighborhood is a drawn line and stays dark.
enum CartoonIllumination {
    static func flatten(pixels: [Double], width: Int, height: Int, blurRadius: Int, creaseRadius: Int, creaseLift: Double) -> [Double] {
        guard pixels.count == width * height, width > 0, height > 0 else { return pixels }
        let wide = boxBlur(pixels, width: width, height: height, radius: max(0, blurRadius))
        let paper = wide.reduce(0, +) / Double(pixels.count)
        var flat = zip(pixels, wide).map { value, background in
            min(1, max(0, value / max(background, 0.12) * paper))
        }
        let medium = boxBlur(flat, width: width, height: height, radius: max(0, creaseRadius))
        for index in flat.indices {
            let local = medium[index]
            let value = flat[index]
            let deficit = local - value
            let isLine = value < local * 0.55
            if !isLine && deficit > 0 {
                flat[index] = min(1, value + creaseLift * deficit)
            }
        }
        return flat
    }

    static func boxBlur(_ pixels: [Double], width: Int, height: Int, radius: Int) -> [Double] {
        if radius == 0 { return pixels }
        var horizontal = Array(repeating: 0.0, count: pixels.count)
        let window = Double(radius * 2 + 1)
        for y in 0..<height {
            var sum = 0.0
            for x in -radius...radius {
                sum += pixels[y * width + min(width - 1, max(0, x))]
            }
            for x in 0..<width {
                horizontal[y * width + x] = sum / window
                let leave = x - radius
                let enter = x + radius + 1
                sum -= pixels[y * width + min(width - 1, max(0, leave))]
                sum += pixels[y * width + min(width - 1, max(0, enter))]
            }
        }
        var vertical = Array(repeating: 0.0, count: pixels.count)
        for x in 0..<width {
            var sum = 0.0
            for y in -radius...radius {
                sum += horizontal[min(height - 1, max(0, y)) * width + x]
            }
            for y in 0..<height {
                vertical[y * width + x] = sum / window
                let leave = y - radius
                let enter = y + radius + 1
                sum -= horizontal[min(height - 1, max(0, leave)) * width + x]
                sum += horizontal[min(height - 1, max(0, enter)) * width + x]
            }
        }
        return vertical
    }
}
