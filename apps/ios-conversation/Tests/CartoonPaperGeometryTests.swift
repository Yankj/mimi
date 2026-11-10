import CoreGraphics
import XCTest
@testable import cat

final class CartoonPaperGeometryTests: XCTestCase {
    func testVisionBottomLeftOriginFlipsIntoTheCropUI() {
        let quad = PaperQuad.fromVision(
            topLeft: CGPoint(x: 0.1, y: 0.8),
            topRight: CGPoint(x: 0.9, y: 0.8),
            bottomRight: CGPoint(x: 0.9, y: 0.2),
            bottomLeft: CGPoint(x: 0.1, y: 0.2))
        XCTAssertEqual(quad.topLeft, PaperCorner(x: 0.1, y: 0.2))
        XCTAssertEqual(quad.bottomLeft, PaperCorner(x: 0.1, y: 0.8))
    }

    func testFingersOutsideThePaperAreNotInsideTheQuad() {
        let paper = PaperQuad.insetDefault
        XCTAssertTrue(paper.contains(PaperCorner(x: 0.5, y: 0.5)))
        XCTAssertFalse(paper.contains(PaperCorner(x: 0.01, y: 0.99)))
        XCTAssertFalse(paper.contains(PaperCorner(x: 0.99, y: 0.01)))
    }

    func testCornersClampToThePhoto() {
        let quad = PaperQuad(
            topLeft: PaperCorner(x: -0.2, y: 0.1),
            topRight: PaperCorner(x: 1.4, y: 0.1),
            bottomRight: PaperCorner(x: 1.1, y: 1.2),
            bottomLeft: PaperCorner(x: 0.1, y: 0.9)).clamped()
        XCTAssertEqual(quad.topLeft.x, 0)
        XCTAssertEqual(quad.topRight.x, 1)
        XCTAssertEqual(quad.bottomRight.y, 1)
    }

    func testLetterboxMapsADragBackOntoThePhoto() {
        let view = CGSize(width: 200, height: 100)
        let image = CGSize(width: 100, height: 100)
        let corner = PaperQuadMapping.normalized(CGPoint(x: 50, y: 0), image: image, view: view)
        XCTAssertEqual(corner, PaperCorner(x: 0, y: 0))
        let outside = PaperQuadMapping.normalized(CGPoint(x: 0, y: 50), image: image, view: view)
        XCTAssertEqual(outside.x, 0)
    }

    func testWarmSamplesRaiseTheBlueGain() {
        let gains = CartoonWhiteBalance.grayWorld([(r: 200, g: 100, b: 50), (r: 180, g: 90, b: 40)])
        XCTAssertGreaterThan(gains.blue, gains.red)
        XCTAssertLessThan(gains.red, 1)
    }

    func testBGRABytesAreNotReadAsRGB() {
        let bgra = CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue)
        XCTAssertEqual(CartoonPixelOrder.layout(bitmapInfo: bgra), .bgra)
        let rgba = CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedLast.rawValue | CGBitmapInfo.byteOrder32Big.rawValue)
        XCTAssertEqual(CartoonPixelOrder.layout(bitmapInfo: rgba), .rgba)
        let pixel = CartoonPixelOrder.rgb((40, 80, 200, 255), layout: .bgra)
        XCTAssertEqual(pixel.r, 200)
        XCTAssertEqual(pixel.g, 80)
        XCTAssertEqual(pixel.b, 40)
        let gains = CartoonWhiteBalance.grayWorld([pixel, pixel])
        XCTAssertGreaterThan(gains.blue, gains.red)
    }

    func testShadowFlattenLiftsABroadShadowAndKeepsTheLine() {
        let width = 12
        let height = 12
        var pixels = [Double](repeating: 0.9, count: width * height)
        for y in 0..<height {
            for x in 0..<5 { pixels[y * width + x] = 0.3 }
        }
        let line = 6 * width + 9
        pixels[line] = 0.02
        let flat = CartoonIllumination.flatten(pixels: pixels, width: width, height: height, blurRadius: 1, creaseRadius: 1, creaseLift: 0.6)
        var shadow = 0.0
        var shadowCount = 0
        var paper = 0.0
        var paperCount = 0
        for y in 0..<height {
            for x in 0..<width {
                let value = flat[y * width + x]
                if x <= 2 { shadow += value; shadowCount += 1 }
                if x >= 7 && !(y == 6 && x == 9) { paper += value; paperCount += 1 }
            }
        }
        shadow /= Double(shadowCount)
        paper /= Double(paperCount)
        XCTAssertGreaterThan(shadow, 0.55)
        XCTAssertLessThan(flat[line], 0.2)
        XCTAssertLessThan(abs(paper - shadow), 0.15)
    }
}

final class CartoonECopyRequestTests: XCTestCase {
    func testGenerateBodyUsesSpecDefaultsAndHasNoIdentityFields() throws {
        let body = CartoonECopyGenerateBody(imageBase64: "abc")
        XCTAssertEqual(body.preset, "storybook")
        XCTAssertEqual(body.stylePreset, "storybook_handdrawn")
        XCTAssertEqual(body.decorationLevel, "off")
        let object = try JSONSerialization.jsonObject(with: JSONEncoder().encode(body)) as? [String: Any]
        XCTAssertEqual(Set(object?.keys.map { $0 } ?? []), ["image_base64", "mime_type", "preset", "style_preset", "decoration_level"])
        XCTAssertEqual(object?["preset"] as? String, "storybook")
        XCTAssertEqual(object?["decoration_level"] as? String, "off")
    }

    func testEndpointPrefersTheOverrideAndRejectsPlainHTTP() {
        let override = CartoonECopyEndpoint.baseURL(info: [
            "CatpawCartoonECopyBaseURL": "https://edge.example.invalid/cartoon-ecopy",
            "CatpawSupabaseProjectURL": "https://abcdefghijklmnopqrst.supabase.co",
        ])
        XCTAssertEqual(override?.absoluteString, "https://edge.example.invalid/cartoon-ecopy")
        let project = CartoonECopyEndpoint.baseURL(info: ["CatpawSupabaseProjectURL": "https://abcdefghijklmnopqrst.supabase.co"])
        XCTAssertEqual(project?.absoluteString, "https://abcdefghijklmnopqrst.supabase.co/functions/v1/cartoon-ecopy")
        XCTAssertNil(CartoonECopyEndpoint.baseURL(info: ["CatpawSupabaseProjectURL": "http://abcdefghijklmnopqrst.supabase.co"]))
    }

    func testGenerateWaitsLongEnoughForTheLightRetry() {
        XCTAssertGreaterThanOrEqual(CartoonECopyTimeouts.generate, 120)
        XCTAssertLessThan(CartoonECopyTimeouts.generate, 150)
        XCTAssertGreaterThanOrEqual(CartoonECopyTimeouts.signedURLRefresh, 8 * 60)
        XCTAssertLessThan(CartoonECopyTimeouts.signedURLRefresh, 10 * 60)
    }
}
