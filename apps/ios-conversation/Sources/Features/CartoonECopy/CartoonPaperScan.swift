import CoreImage
import UIKit
import Vision

/// Detects the paper, warps the photo to that quadrilateral, and white-balances the result.
/// The returned JPEG is only the paper. Fingers and the table outside the quad are not in it.
enum CartoonPaperScanner {
    static func detect(in image: UIImage) -> PaperQuad? {
        guard let cgImage = normalized(image)?.cgImage else { return nil }
        let request = VNDetectRectanglesRequest()
        request.minimumAspectRatio = 0.3
        request.maximumAspectRatio = 1
        request.minimumSize = 0.2
        request.maximumObservations = 1
        request.quadratureTolerance = 20
        let handler = VNImageRequestHandler(cgImage: cgImage, options: [:])
        guard (try? handler.perform([request])) != nil,
              let observed = (request.results as? [VNRectangleObservation])?.first else { return nil }
        return PaperQuad.fromVision(topLeft: observed.topLeft, topRight: observed.topRight, bottomRight: observed.bottomRight, bottomLeft: observed.bottomLeft)
    }

    /// Long edge is capped at 1536, matching the diffusion adapter's output size note.
    static func renderJPEG(image: UIImage, quad: PaperQuad, quality: CGFloat = 0.85) -> Data? {
        guard let upright = normalized(image), let source = CIImage(image: upright) else { return nil }
        let width = source.extent.width
        let height = source.extent.height
        let paper = quad.clamped()
        guard let correction = CIFilter(name: "CIPerspectiveCorrection") else { return nil }
        correction.setValue(source, forKey: kCIInputImageKey)
        correction.setValue(vector(paper.topLeft, width: width, height: height), forKey: "inputTopLeft")
        correction.setValue(vector(paper.topRight, width: width, height: height), forKey: "inputTopRight")
        correction.setValue(vector(paper.bottomLeft, width: width, height: height), forKey: "inputBottomLeft")
        correction.setValue(vector(paper.bottomRight, width: width, height: height), forKey: "inputBottomRight")
        guard var output = correction.outputImage else { return nil }
        output = flattened(output)
        output = balanced(output)
        let longEdge = max(output.extent.width, output.extent.height)
        if longEdge > 1536 {
            let scale = 1536 / longEdge
            output = output.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        }
        let context = CIContext(options: nil)
        guard let cgImage = context.createCGImage(output, from: output.extent) else { return nil }
        return UIImage(cgImage: cgImage).jpegData(compressionQuality: quality)
    }

    private static func vector(_ corner: PaperCorner, width: CGFloat, height: CGFloat) -> CIVector {
        CIVector(x: corner.x * width, y: (1 - corner.y) * height)
    }

    private static func normalized(_ image: UIImage) -> UIImage? {
        guard image.imageOrientation != .up else { return image }
        let format = UIGraphicsImageRendererFormat()
        format.scale = image.scale
        return UIGraphicsImageRenderer(size: image.size, format: format).image { _ in
            image.draw(in: CGRect(origin: .zero, size: image.size))
        }
    }

    /// Large-blur division lifts a lamp shadow. Highlight-shadow then lifts broad creases.
    /// Thin ink stays darker than the blur, so the lines are not painted out.
    private static func flattened(_ image: CIImage) -> CIImage {
        let extent = image.extent
        let longEdge = max(extent.width, extent.height)
        guard longEdge > 1, let blur = CIFilter(name: "CIGaussianBlur") else { return image }
        blur.setValue(image.clampedToExtent(), forKey: kCIInputImageKey)
        blur.setValue(max(18, longEdge / 18), forKey: kCIInputRadiusKey)
        guard let background = blur.outputImage?.cropped(to: extent) else { return image }
        guard let floor = CIFilter(name: "CIColorClamp") else { return image }
        floor.setValue(background, forKey: kCIInputImageKey)
        floor.setValue(CIVector(x: 0.12, y: 0.12, z: 0.12, w: 0), forKey: "inputMinComponents")
        floor.setValue(CIVector(x: 1, y: 1, z: 1, w: 1), forKey: "inputMaxComponents")
        guard let safeBackground = floor.outputImage else { return image }
        // CIDivideBlendMode computes background / source. Swap the inputs so the photo is divided by the blur.
        guard let divide = CIFilter(name: "CIDivideBlendMode") else { return image }
        divide.setValue(safeBackground, forKey: kCIInputImageKey)
        divide.setValue(image, forKey: kCIInputBackgroundImageKey)
        guard let flat = divide.outputImage?.cropped(to: extent) else { return image }
        guard let shadow = CIFilter(name: "CIHighlightShadowAdjust") else { return flat }
        shadow.setValue(flat, forKey: kCIInputImageKey)
        shadow.setValue(0.3, forKey: "inputShadowAmount")
        shadow.setValue(1, forKey: "inputHighlightAmount")
        shadow.setValue(max(8, longEdge / 48), forKey: kCIInputRadiusKey)
        return shadow.outputImage?.cropped(to: extent) ?? flat
    }

    private static func balanced(_ image: CIImage) -> CIImage {
        let context = CIContext(options: nil)
        let sampleWidth = min(32, image.extent.width)
        let sampleHeight = min(32, image.extent.height)
        guard sampleWidth > 1, sampleHeight > 1 else { return image }
        let thumb = image.transformed(by: CGAffineTransform(scaleX: sampleWidth / image.extent.width, y: sampleHeight / image.extent.height))
        guard let cgImage = context.createCGImage(thumb, from: thumb.extent) else { return image }
        guard let samples = rgbaSamples(cgImage), !samples.isEmpty else { return image }
        let gains = CartoonWhiteBalance.grayWorld(samples)
        guard let matrix = CIFilter(name: "CIColorMatrix") else { return image }
        matrix.setValue(image, forKey: kCIInputImageKey)
        matrix.setValue(CIVector(x: gains.red, y: 0, z: 0, w: 0), forKey: "inputRVector")
        matrix.setValue(CIVector(x: 0, y: gains.green, z: 0, w: 0), forKey: "inputGVector")
        matrix.setValue(CIVector(x: 0, y: 0, z: gains.blue, w: 0), forKey: "inputBVector")
        matrix.setValue(CIVector(x: 0, y: 0, z: 0, w: 1), forKey: "inputAVector")
        matrix.setValue(CIVector(x: 0, y: 0, z: 0, w: 0), forKey: "inputBiasVector")
        return matrix.outputImage ?? image
    }

    /// Draw into RGBA8 (byteOrder32Big + premultipliedLast) and read with CartoonPixelOrder,
    /// so a BGRA photo buffer does not swap red and blue.
    private static func rgbaSamples(_ image: CGImage) -> [(r: UInt8, g: UInt8, b: UInt8)]? {
        let width = image.width
        let height = image.height
        guard width > 0, height > 0 else { return nil }
        let rowBytes = width * 4
        var pixels = [UInt8](repeating: 0, count: rowBytes * height)
        let bitmapInfo = CGImageAlphaInfo.premultipliedLast.rawValue | CGBitmapInfo.byteOrder32Big.rawValue
        let layout = CartoonPixelOrder.layout(bitmapInfo: CGBitmapInfo(rawValue: bitmapInfo))
        let drawn: [(r: UInt8, g: UInt8, b: UInt8)]? = pixels.withUnsafeMutableBytes { raw in
            guard let base = raw.baseAddress,
                  let context = CGContext(
                    data: base, width: width, height: height, bitsPerComponent: 8, bytesPerRow: rowBytes,
                    space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: bitmapInfo) else { return nil }
            context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
            let bytes = base.assumingMemoryBound(to: UInt8.self)
            var samples: [(r: UInt8, g: UInt8, b: UInt8)] = []
            for y in stride(from: 0, to: height, by: 2) {
                for x in stride(from: 0, to: width, by: 2) {
                    samples.append(CartoonPixelOrder.rgb(bytes + y * rowBytes + x * 4, layout: layout))
                }
            }
            return samples
        }
        if let drawn, !drawn.isEmpty { return drawn }
        return samplesFromProvider(image)
    }

    private static func samplesFromProvider(_ image: CGImage) -> [(r: UInt8, g: UInt8, b: UInt8)]? {
        guard let data = image.dataProvider?.data, let bytes = CFDataGetBytePtr(data) else { return nil }
        let bytesPerPixel = image.bitsPerPixel / 8
        guard bytesPerPixel >= 4 else { return nil }
        let info = CGBitmapInfo(rawValue: image.bitmapInfo.rawValue | image.alphaInfo.rawValue)
        let layout = CartoonPixelOrder.layout(bitmapInfo: info)
        var samples: [(r: UInt8, g: UInt8, b: UInt8)] = []
        for y in stride(from: 0, to: image.height, by: 2) {
            for x in stride(from: 0, to: image.width, by: 2) {
                let offset = y * image.bytesPerRow + x * bytesPerPixel
                samples.append(CartoonPixelOrder.rgb(bytes + offset, layout: layout))
            }
        }
        return samples
    }
}
