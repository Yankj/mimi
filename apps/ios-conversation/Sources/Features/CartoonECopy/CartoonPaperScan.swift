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

    private static func balanced(_ image: CIImage) -> CIImage {
        let context = CIContext(options: nil)
        let sampleWidth = min(32, image.extent.width)
        let sampleHeight = min(32, image.extent.height)
        guard sampleWidth > 1, sampleHeight > 1 else { return image }
        let thumb = image.transformed(by: CGAffineTransform(scaleX: sampleWidth / image.extent.width, y: sampleHeight / image.extent.height))
        guard let cgImage = context.createCGImage(thumb, from: thumb.extent),
              let data = cgImage.dataProvider?.data,
              let bytes = CFDataGetBytePtr(data) else { return image }
        let bytesPerPixel = cgImage.bitsPerPixel / 8
        guard bytesPerPixel >= 4 else { return image }
        var samples: [(r: UInt8, g: UInt8, b: UInt8)] = []
        for y in stride(from: 0, to: cgImage.height, by: 2) {
            for x in stride(from: 0, to: cgImage.width, by: 2) {
                let offset = y * cgImage.bytesPerRow + x * bytesPerPixel
                samples.append((bytes[offset], bytes[offset + 1], bytes[offset + 2]))
            }
        }
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
}
