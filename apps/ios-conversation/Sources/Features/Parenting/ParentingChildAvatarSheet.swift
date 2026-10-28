import SwiftUI
import PhotosUI
import UIKit

/// 头像 sheet: the default plush cat, or a photo the parent picks. `PhotosPicker` runs out of process, so the app never asks for
/// access to the whole photo library. The picked photo is cropped to a square here and stored locally (`ParentingChildAvatarStore`).
@MainActor struct ParentingChildAvatarSheet: View {
    let child: ParentingChild
    let accent: Color

    @Environment(\.dismiss) private var dismiss
    @Environment(\.locale) private var locale
    private let library = ParentingChildAvatarLibrary.shared
    @State private var pickerItem: PhotosPickerItem?
    @State private var crop: ParentingAvatarCropInput?
    @State private var message: String?

    private func ui(_ key: String) -> String { AppLanguage(locale: locale).ui(key) }

    var body: some View {
        NavigationStack {
            VStack(spacing: 20) {
                ParentingChildAvatar(accent: accent, size: 112, childID: child.id)
                    .accessibilityHidden(true)
                Text(verbatim: child.name).font(.title3.weight(.semibold))
                VStack(spacing: 12) {
                    PhotosPicker(selection: $pickerItem, matching: .images) {
                        Label(ui("从相册选择"), systemImage: "photo.on.rectangle")
                            .font(.body.weight(.semibold)).foregroundStyle(catColors.ink)
                            .frame(maxWidth: .infinity, minHeight: 28).padding(.vertical, 10)
                            .modifier(ParentingTintedGlass())
                            .contentShape(Capsule())
                    }
                    .accessibilityIdentifier("parenting.avatar.pick")
                    if library.hasPhoto(for: child.id) {
                        Button { library.removePhoto(for: child.id) } label: {
                            Label(ui("恢复默认猫头像"), systemImage: "arrow.uturn.backward")
                                .font(.body.weight(.medium)).frame(maxWidth: .infinity, minHeight: 28)
                        }
                        .parentingSecondaryButton()
                        .accessibilityIdentifier("parenting.avatar.reset")
                    }
                }
                if let message {
                    Text(verbatim: message).font(.subheadline).foregroundStyle(catColors.failure)
                        .accessibilityIdentifier("parenting.avatar.error")
                }
                Text(ui("照片只保存在这台设备上，退出登录时会一起清除。"))
                    .font(catTypography.Role.caption.font).foregroundStyle(catColors.calmSecondary)
                    .multilineTextAlignment(.center)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 24).padding(.top, 24)
            .frame(maxWidth: 520).frame(maxWidth: .infinity)
            .background(catColors.background.ignoresSafeArea())
            .foregroundStyle(catColors.ink)
            .navigationTitle(ui("孩子头像"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(ui("完成")) { dismiss() }.accessibilityIdentifier("parenting.avatar.done")
                }
            }
            .navigationDestination(item: $crop) { input in
                ParentingAvatarCropView(image: input.image, onSave: { data in save(data) })
            }
        }
        .onChange(of: pickerItem) { _, item in
            guard let item else { return }
            Task { await load(item) }
        }
        .presentationDetents([.medium, .large])
        .accessibilityIdentifier("parenting.avatar.sheet")
    }

    private func load(_ item: PhotosPickerItem) async {
        let data = try? await item.loadTransferable(type: Data.self)
        pickerItem = nil
        guard let data, let image = UIImage(data: data) else {
            message = ui("这张照片打不开，请换一张。")
            return
        }
        message = nil
        crop = ParentingAvatarCropInput(image: ParentingAvatarImageProcessor.normalized(image))
    }

    private func save(_ data: Data) {
        do {
            try library.setPhoto(data, for: child.id)
            message = nil
            crop = nil
        } catch {
            message = ui("头像没有保存成功，请换一张试试。")
            crop = nil
        }
    }
}

/// Hashable wrapper so the crop screen can be a `navigationDestination(item:)`.
struct ParentingAvatarCropInput: Identifiable, Hashable {
    let id = UUID()
    let image: UIImage
    static func == (lhs: ParentingAvatarCropInput, rhs: ParentingAvatarCropInput) -> Bool { lhs.id == rhs.id }
    func hash(into hasher: inout Hasher) { hasher.combine(id) }
}

/// Square crop: drag and pinch (or the slider, for VoiceOver and Switch Control) to place the child inside the circle that
/// every avatar is shown in. The saved file is the square behind that circle.
struct ParentingAvatarCropView: View {
    let image: UIImage
    let onSave: (Data) -> Void

    private let viewport: Double = 280
    @Environment(\.locale) private var locale
    @State private var zoom = 1.0
    @State private var zoomBase = 1.0
    @State private var offset = CGSize.zero
    @State private var offsetBase = CGSize.zero
    @State private var failed = false

    private func ui(_ key: String) -> String { AppLanguage(locale: locale).ui(key) }
    private var imageWidth: Double { Double(image.size.width) }
    private var imageHeight: Double { Double(image.size.height) }

    private func reclamp() {
        let clamped = ParentingAvatarCrop.clampedOffset(x: Double(offset.width), y: Double(offset.height),
                                                        imageWidth: imageWidth, imageHeight: imageHeight, viewport: viewport, zoom: zoom)
        offset = CGSize(width: clamped.x, height: clamped.y)
    }

    private func setZoom(_ value: Double) {
        zoom = ParentingAvatarCrop.clampedZoom(value)
        reclamp()
        offsetBase = offset
    }

    private var drag: some Gesture {
        DragGesture()
            .onChanged { value in
                offset = CGSize(width: offsetBase.width + value.translation.width, height: offsetBase.height + value.translation.height)
                reclamp()
            }
            .onEnded { _ in offsetBase = offset }
    }

    private var pinch: some Gesture {
        MagnifyGesture()
            .onChanged { value in
                zoom = ParentingAvatarCrop.clampedZoom(zoomBase * Double(value.magnification))
                reclamp()
            }
            .onEnded { _ in
                zoomBase = zoom
                offsetBase = offset
            }
    }

    private func save() {
        let rect = ParentingAvatarCrop.cropRect(imageWidth: imageWidth, imageHeight: imageHeight, viewport: viewport,
                                                zoom: zoom, offsetX: Double(offset.width), offsetY: Double(offset.height))
        guard let data = ParentingAvatarImageProcessor.avatarJPEG(from: image, rect: rect) else { failed = true; return }
        onSave(data)
    }

    var body: some View {
        let shown = ParentingAvatarCrop.displayedSize(imageWidth: imageWidth, imageHeight: imageHeight, viewport: viewport, zoom: zoom)
        VStack(spacing: 18) {
            ZStack {
                Image(uiImage: image)
                    .resizable()
                    .frame(width: shown.width, height: shown.height)
                    .offset(offset)
                Path { path in
                    path.addRect(CGRect(x: 0, y: 0, width: viewport, height: viewport))
                    path.addEllipse(in: CGRect(x: 0, y: 0, width: viewport, height: viewport))
                }
                .fill(Color.black.opacity(0.5), style: FillStyle(eoFill: true))
                .frame(width: viewport, height: viewport)
                .allowsHitTesting(false)
                Circle().strokeBorder(Color.white.opacity(0.9), lineWidth: 2)
                    .frame(width: viewport, height: viewport)
                    .allowsHitTesting(false)
            }
            .frame(width: viewport, height: viewport)
            .background(Color.black)
            .clipped()
            .contentShape(Rectangle())
            .gesture(drag)
            .simultaneousGesture(pinch)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(Text(verbatim: ui("头像预览")))
            .accessibilityIdentifier("parenting.avatar.crop.area")

            Text(ui("拖动和缩放，让孩子刚好在圆圈里。"))
                .font(.subheadline).foregroundStyle(catColors.calmSecondary)
            HStack(spacing: 12) {
                Image(systemName: "minus.magnifyingglass").accessibilityHidden(true)
                Slider(value: Binding(get: { zoom }, set: { setZoom($0) }), in: 1...ParentingAvatarCrop.maxZoom)
                    .accessibilityLabel(Text(verbatim: ui("缩放")))
                    .accessibilityIdentifier("parenting.avatar.crop.zoom")
                Image(systemName: "plus.magnifyingglass").accessibilityHidden(true)
            }
            .frame(maxWidth: viewport)
            if failed {
                Text(ui("头像没有保存成功，请换一张试试。")).font(.subheadline).foregroundStyle(catColors.failure)
            }
            Spacer(minLength: 0)
        }
        .padding(.top, 20)
        .frame(maxWidth: .infinity)
        .background(catColors.background.ignoresSafeArea())
        .foregroundStyle(catColors.ink)
        .navigationTitle(ui("调整头像"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button(ui("使用这张"), action: save).accessibilityIdentifier("parenting.avatar.crop.save")
            }
        }
    }
}
