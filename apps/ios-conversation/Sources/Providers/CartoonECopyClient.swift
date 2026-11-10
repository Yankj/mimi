import Foundation

/// Defaults match `docs/design/prompts/cartoon-ecopy/prompt-pack/spec.json` `defaults`.
/// The body has no field for a child name, age, location or family name.
struct CartoonECopyGenerateBody: Encodable, Equatable {
    var imageBase64: String
    var mimeType = "image/jpeg"
    var preset = "storybook"
    var stylePreset = "storybook_handdrawn"
    var decorationLevel = "off"

    enum CodingKeys: String, CodingKey {
        case imageBase64 = "image_base64"
        case mimeType = "mime_type"
        case preset
        case stylePreset = "style_preset"
        case decorationLevel = "decoration_level"
    }
}

struct CartoonArtwork: Decodable, Equatable, Identifiable, Sendable {
    var id: String
    var preset: String
    var stylePreset: String
    var decorationLevel: String
    var model: String?
    var specVersion: String
    var status: String
    var originalURL: URL
    var outputURL: URL?

    enum CodingKeys: String, CodingKey {
        case id, preset, model, status
        case stylePreset = "style_preset"
        case decorationLevel = "decoration_level"
        case specVersion = "spec_version"
        case originalURL = "original_url"
        case outputURL = "output_url"
    }
}

enum CartoonECopyEndpoint {
    static func baseURL(info: [String: Any]) -> URL? {
        func https(_ key: String) -> URL? {
            guard let raw = info[key] as? String, !raw.isEmpty, let url = URL(string: raw), url.scheme == "https" else { return nil }
            return url
        }
        if let override = https("CatpawCartoonECopyBaseURL") { return override }
        return https("CatpawSupabaseProjectURL")?
            .appendingPathComponent("functions")
            .appendingPathComponent("v1")
            .appendingPathComponent("cartoon-ecopy")
    }
}

enum CartoonECopyClientError: Error, Equatable {
    case notConfigured(String)
    case unauthorized
    case privacy
    case http(Int, String)
}

enum CartoonECopyTimeouts {
    /// Server chain is 70s: 40s for the first attempt and 25s for the light retry. The extra 10s covers upload and network.
    static let generate: TimeInterval = 80
    /// Signed URLs last 10 minutes. Refresh while they are still valid.
    static let signedURLRefresh: TimeInterval = 8 * 60
}

actor CartoonECopyClient {
    static let shared = CartoonECopyClient()
    private let session: URLSession
    private let base: URL?
    private let authorization: (@Sendable () async throws -> String)?

    init(session: URLSession = .shared, info: [String: Any]? = Bundle.main.infoDictionary, authorization: (@Sendable () async throws -> String)? = nil) {
        self.session = session
        self.base = CartoonECopyEndpoint.baseURL(info: info ?? [:])
        self.authorization = authorization
    }

    func list() async throws -> [CartoonArtwork] {
        struct Response: Decodable { var artworks: [CartoonArtwork] }
        return try await send("GET", "artworks", body: nil, as: Response.self).artworks
    }

    func generate(_ body: CartoonECopyGenerateBody) async throws -> CartoonArtwork {
        struct Response: Decodable { var artwork: CartoonArtwork }
        let data = try JSONEncoder().encode(body)
        return try await send("POST", "artworks", body: data, timeout: CartoonECopyTimeouts.generate, as: Response.self).artwork
    }

    func delete(id: String) async throws {
        struct Response: Decodable { var deleted: Bool }
        _ = try await send("DELETE", "artworks/" + id, body: nil, as: Response.self)
    }

    private func send<T: Decodable>(_ method: String, _ path: String, body: Data?, timeout: TimeInterval = 20, as: T.Type) async throws -> T {
        guard let base else { throw CartoonECopyClientError.notConfigured("卡通电子稿服务地址未配置。") }
        let bearer: String
        do {
            let token: String
            if let authorization {
                token = try await authorization()
            } else {
                token = try await catPushTokenUploader.shared.bearer()
            }
            bearer = token
        } catch {
            throw CartoonECopyClientError.unauthorized
        }
        let url = path.split(separator: "/").reduce(base) { $0.appendingPathComponent(String($1)) }
        var request = URLRequest(url: url, timeoutInterval: timeout)
        request.httpMethod = method
        request.setValue(bearer, forHTTPHeaderField: "Authorization")
        if let body {
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = body
        }
        let (data, response) = try await session.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        if (200..<300).contains(status) {
            return try JSONDecoder().decode(T.self, from: data)
        }
        let payload = (try? JSONDecoder().decode(ErrorBody.self, from: data))?.error
        let message = payload?.message ?? "卡通电子稿没有完成。"
        if payload?.code == "not_configured" || status == 503 && payload?.code == "not_configured" {
            throw CartoonECopyClientError.notConfigured(message)
        }
        if payload?.code == "privacy_field" { throw CartoonECopyClientError.privacy }
        if status == 401 || status == 403 { throw CartoonECopyClientError.unauthorized }
        throw CartoonECopyClientError.http(status, message)
    }

    private struct ErrorBody: Decodable {
        struct Item: Decodable { var code: String; var message: String }
        var error: Item
    }
}
