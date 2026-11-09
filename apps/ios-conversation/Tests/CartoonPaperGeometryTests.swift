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
}
