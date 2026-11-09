import PhotosUI
import SwiftUI
import UIKit

/// 拍照或从相册选一张孩子的画，把纸边裁正后再生成电子稿。上传的只有裁正后的 JPEG。
@MainActor struct CartoonECopyFlowView: View {
    @Environment(\.locale) private var locale
    @State private var step: Step = .capture
    @State private var source: UIImage?
    @State private var quad = PaperQuad.insetDefault
    @State private var cropped = Data()
    @State private var preview: UIImage?
    @State private var preset = "storybook"
    @State private var decoration = "off"
    @State private var artworks: [CartoonArtwork] = []
    @State private var message: String?
    @State private var busy = false
    @State private var showCamera = false
    @State private var libraryItem: PhotosPickerItem?

    private enum Step { case capture, crop, options }

    private func ui(_ key: String) -> String { AppLanguage(locale: locale).ui(key) }

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            if let message {
                Text(verbatim: message).font(.subheadline).foregroundStyle(catColors.actionText)
                    .accessibilityIdentifier("cartoon.error")
            }
            switch step {
            case .capture: capture
            case .crop: crop
            case .options: options
            }
        }
        .navigationTitle(ui("画作电子稿"))
        .navigationBarTitleDisplayMode(.inline)
        .task { await reload() }
        .sheet(isPresented: $showCamera) {
            NativeMediaPicker(camera: true) { data in
                showCamera = false
                if let data, let image = UIImage(data: data) { beginCrop(image) }
            }
        }
        .onChange(of: libraryItem) { _, item in
            guard let item else { return }
            Task { await loadLibrary(item) }
        }
    }

    private var capture: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(ui("拍下孩子的画。接下来把四个角拖到纸边上，手指和桌面不会进电子稿。"))
                .font(.subheadline).foregroundStyle(catColors.calmSecondary).fixedSize(horizontal: false, vertical: true)
            Button { showCamera = true } label: { Label(ui("拍照"), systemImage: "camera").frame(maxWidth: .infinity) }
                .parentingGrowthPrimaryButton()
                .disabled(!UIImagePickerController.isSourceTypeAvailable(.camera))
                .accessibilityIdentifier("cartoon.capture.camera")
            PhotosPicker(selection: $libraryItem, matching: .images) {
                Label(ui("从相册选择"), systemImage: "photo").frame(maxWidth: .infinity)
            }
            .parentingSecondaryButton()
            .accessibilityIdentifier("cartoon.capture.library")
            if !artworks.isEmpty {
                Text(ui("已保存的成对画稿")).font(.subheadline.weight(.semibold))
                ForEach(artworks) { artwork in pair(artwork) }
            }
        }
    }

    private var crop: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(ui("拖动四角，让纸边正好包住画。纸外的手指、蜡笔和桌面会被裁掉。"))
                .font(.subheadline).foregroundStyle(catColors.calmSecondary).fixedSize(horizontal: false, vertical: true)
            if let source {
                CartoonCornerEditor(image: source, quad: $quad)
                    .frame(minHeight: 280)
                    .accessibilityIdentifier("cartoon.crop.editor")
            }
            Button { confirmCrop() } label: { Text(verbatim: ui("用这张裁正图")).frame(maxWidth: .infinity) }
                .parentingGrowthPrimaryButton()
                .disabled(busy)
                .accessibilityIdentifier("cartoon.crop.confirm")
            Button(ui("重选")) { step = .capture; self.source = nil }
                .frame(minHeight: 44)
        }
    }

    private var options: some View {
        VStack(alignment: .leading, spacing: 14) {
            if let preview {
                Image(uiImage: preview).resizable().scaledToFit().frame(maxHeight: 280)
                    .accessibilityLabel(ui("裁正后的画"))
            }
            Text(ui("强度")).font(.subheadline.weight(.semibold))
            Picker(ui("强度"), selection: $preset) {
                Text(ui("绘本精修")).tag("storybook")
                Text(ui("轻度润色")).tag("light")
            }
            .pickerStyle(.segmented)
            .accessibilityIdentifier("cartoon.option.preset")
            Text(preset == "storybook" ? ui("默认是绘本精修。轻度润色要手动选。") : ui("这一次用轻度润色。"))
                .font(.caption).foregroundStyle(catColors.calmSecondary)
            Text(ui("装饰")).font(.subheadline.weight(.semibold))
            Picker(ui("装饰"), selection: $decoration) {
                Text(ui("关")).tag("off")
                Text(ui("轻")).tag("light")
                Text(ui("闪")).tag("sparkle")
            }
            .pickerStyle(.segmented)
            .accessibilityIdentifier("cartoon.option.decoration")
            Text(ui("装饰默认关。关的时候不加腮红、高光和闪光。"))
                .font(.caption).foregroundStyle(catColors.calmSecondary)
            Button { Task { await generate() } } label: { Text(verbatim: busy ? ui("正在生成") : ui("生成电子稿")).frame(maxWidth: .infinity) }
                .parentingGrowthPrimaryButton()
                .disabled(busy || cropped.isEmpty)
                .accessibilityIdentifier("cartoon.generate")
            Button(ui("返回调纸边")) { step = .crop }.frame(minHeight: 44)
        }
    }

    private func pair(_ artwork: CartoonArtwork) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .top, spacing: 8) {
                artworkImage(artwork.originalURL, label: ui("裁正原图"))
                artworkImage(artwork.outputURL, label: ui("电子稿"))
            }
            Text(verbatim: pairCaption(artwork)).font(.caption).foregroundStyle(catColors.calmSecondary)
            Button(ui("删除这一对")) { Task { await remove(artwork) } }
                .frame(minHeight: 44)
                .accessibilityIdentifier("cartoon.pair.delete." + artwork.id)
        }
        .padding(12)
        .background(catColors.card, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
    }

    private func artworkImage(_ url: URL?, label: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(verbatim: label).font(.caption2).foregroundStyle(catColors.calmSecondary)
            AsyncImage(url: url) { image in image.resizable().scaledToFit() } placeholder: { Color.clear.frame(height: 80) }
                .frame(maxWidth: .infinity, minHeight: 80, maxHeight: 140)
        }
    }

    private func pairCaption(_ artwork: CartoonArtwork) -> String {
        let strength = artwork.preset == "light" ? ui("轻度润色") : ui("绘本精修")
        let state = artwork.status == "fallback_crop" ? ui("模型没通过，保留裁正原图") : ui("已生成")
        return strength + " · " + state
    }

    private func beginCrop(_ image: UIImage) {
        source = image
        quad = CartoonPaperScanner.detect(in: image) ?? .insetDefault
        message = nil
        step = .crop
    }

    private func loadLibrary(_ item: PhotosPickerItem) async {
        defer { libraryItem = nil }
        guard let data = try? await item.loadTransferable(type: Data.self), let image = UIImage(data: data) else {
            message = ui("这张图片打不开。")
            return
        }
        beginCrop(image)
    }

    private func confirmCrop() {
        guard let source else { return }
        guard let jpeg = CartoonPaperScanner.renderJPEG(image: source, quad: quad), let image = UIImage(data: jpeg) else {
            message = ui("裁正没有成功，请再拖一次纸边。")
            return
        }
        cropped = jpeg
        preview = image
        message = nil
        step = .options
    }

    private func generate() async {
        busy = true
        defer { busy = false }
        var request = CartoonECopyGenerateBody(imageBase64: cropped.base64EncodedString())
        request.preset = preset
        request.decorationLevel = decoration
        do {
            _ = try await CartoonECopyClient.shared.generate(request)
            message = nil
            step = .capture
            source = nil
            preview = nil
            cropped = Data()
            await reload()
        } catch CartoonECopyClientError.notConfigured(let text) {
            message = text
        } catch CartoonECopyClientError.unauthorized {
            message = ui("需要登录后再生成。")
        } catch CartoonECopyClientError.http(_, let text) {
            message = text
        } catch {
            message = ui("生成没有完成。")
        }
    }

    private func reload() async {
        artworks = (try? await CartoonECopyClient.shared.list()) ?? artworks
    }

    private func remove(_ artwork: CartoonArtwork) async {
        do {
            try await CartoonECopyClient.shared.delete(id: artwork.id)
            artworks.removeAll { $0.id == artwork.id }
        } catch CartoonECopyClientError.http(_, let text) {
            message = text
        } catch {
            message = ui("删除没有完成。原图和电子稿需要一起删掉。")
        }
    }
}

private struct CartoonCornerEditor: View {
    let image: UIImage
    @Binding var quad: PaperQuad

    var body: some View {
        GeometryReader { proxy in
            let box = PaperQuadMapping.letterbox(image: image.size, view: proxy.size)
            ZStack {
                Image(uiImage: image).resizable().scaledToFit().frame(width: proxy.size.width, height: proxy.size.height)
                Path { path in
                    path.move(to: viewPoint(quad.topLeft, box: box))
                    path.addLine(to: viewPoint(quad.topRight, box: box))
                    path.addLine(to: viewPoint(quad.bottomRight, box: box))
                    path.addLine(to: viewPoint(quad.bottomLeft, box: box))
                    path.closeSubpath()
                }
                .stroke(catColors.actionText, lineWidth: 2)
                handle(\.topLeft, box: box, proxy: proxy.size)
                handle(\.topRight, box: box, proxy: proxy.size)
                handle(\.bottomRight, box: box, proxy: proxy.size)
                handle(\.bottomLeft, box: box, proxy: proxy.size)
            }
            .coordinateSpace(name: "cartoon.crop")
        }
    }

    private func viewPoint(_ corner: PaperCorner, box: PaperQuadMapping.Letterbox) -> CGPoint {
        CGPoint(x: box.origin.x + corner.x * box.size.width, y: box.origin.y + corner.y * box.size.height)
    }

    private func handle(_ keyPath: WritableKeyPath<PaperQuad, PaperCorner>, box: PaperQuadMapping.Letterbox, proxy: CGSize) -> some View {
        let point = viewPoint(quad[keyPath: keyPath], box: box)
        return Circle().fill(catColors.card).overlay(Circle().stroke(catColors.ink, lineWidth: 2))
            .frame(width: 28, height: 28)
            .position(point)
            .gesture(DragGesture(coordinateSpace: .named("cartoon.crop")).onChanged { value in
                quad[keyPath: keyPath] = PaperQuadMapping.normalized(value.location, image: image.size, view: proxy)
            })
            .accessibilityLabel(Text("纸边"))
    }
}
