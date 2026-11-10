// 生成「谢姐K歌台」启动图标：深蓝圆角底 + 细金边 + 金黄「谢」+ 底部短金条。
// 配色取自 KaraokeActivity.java 的 BG / GOLD，纯矢量绘制，不依赖图片素材。
// 用法: swift make_icon.swift res /tmp/xiejie_icon_preview.png
import Foundation
import AppKit
import CoreGraphics
import CoreText
import ImageIO
import UniformTypeIdentifiers

let bg = NSColor(calibratedRed: 11.0 / 255.0, green: 17.0 / 255.0,
                 blue: 32.0 / 255.0, alpha: 1)
let gold = NSColor(calibratedRed: 1, green: 201.0 / 255.0,
                   blue: 74.0 / 255.0, alpha: 1)

func drawIcon(_ size: Int) -> CGImage? {
    let s = CGFloat(size)
    guard let ctx = CGContext(data: nil, width: size, height: size,
                              bitsPerComponent: 8, bytesPerRow: 0,
                              space: CGColorSpaceCreateDeviceRGB(),
                              bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)
    else { return nil }
    ctx.setAllowsAntialiasing(true)
    ctx.interpolationQuality = .high

    // 底：深蓝圆角方块
    let corner = s * 0.22
    let outer = CGPath(roundedRect: CGRect(x: 0, y: 0, width: s, height: s),
                       cornerWidth: corner, cornerHeight: corner, transform: nil)
    ctx.saveGState()
    ctx.addPath(outer)
    ctx.clip()
    ctx.setFillColor(bg.cgColor)
    ctx.fill(CGRect(x: 0, y: 0, width: s, height: s))
    ctx.restoreGState()

    // 细金边
    let inset = s * 0.055
    let inner = CGPath(roundedRect: CGRect(x: inset, y: inset,
                                           width: s - 2 * inset, height: s - 2 * inset),
                       cornerWidth: corner * 0.78, cornerHeight: corner * 0.78,
                       transform: nil)
    ctx.setStrokeColor(gold.cgColor)
    ctx.setLineWidth(s * 0.016)
    ctx.addPath(inner)
    ctx.strokePath()

    // 居中大字「谢」
    let fontSize = s * 0.56
    let font = NSFont(name: "PingFangSC-Semibold", size: fontSize)
        ?? NSFont.boldSystemFont(ofSize: fontSize)
    var attrs: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: gold]
    // CoreText 认 CT 的属性名，NSAttributedString 认 NSColor，两个都给，避免字变黑
    attrs[NSAttributedString.Key(rawValue: "CTForegroundColor")] = gold.cgColor
    let line = CTLineCreateWithAttributedString(
        NSAttributedString(string: "谢", attributes: attrs))
    let b = CTLineGetBoundsWithOptions(line, .useGlyphPathBounds)
    ctx.textPosition = CGPoint(x: (s - b.width) / 2 - b.origin.x,
                               y: s * 0.545 - b.origin.y - b.height / 2)
    CTLineDraw(line, ctx)

    // 底部短金条，呼应 App 内的进度条
    let bw = s * 0.30, bh = s * 0.045
    let bar = CGPath(roundedRect: CGRect(x: (s - bw) / 2, y: s * 0.155,
                                         width: bw, height: bh),
                     cornerWidth: bh / 2, cornerHeight: bh / 2, transform: nil)
    ctx.setFillColor(gold.cgColor)
    ctx.addPath(bar)
    ctx.fillPath()

    return ctx.makeImage()
}

func writePNG(_ image: CGImage, _ path: String) {
    let url = URL(fileURLWithPath: path) as CFURL
    guard let dest = CGImageDestinationCreateWithURL(
        url, UTType.png.identifier as CFString, 1, nil) else {
        fputs("无法创建 \(path)\n", stderr); exit(1)
    }
    CGImageDestinationAddImage(dest, image, nil)
    if !CGImageDestinationFinalize(dest) {
        fputs("写入失败 \(path)\n", stderr); exit(1)
    }
}

let args = CommandLine.arguments
let root = args.count > 1 ? args[1] : "."
let specs: [(String, Int)] = [
    ("drawable-mdpi", 48), ("drawable-tvdpi", 64),
    ("drawable-hdpi", 72), ("drawable-xhdpi", 96)
]
for (dir, size) in specs {
    let d = (root as NSString).appendingPathComponent(dir)
    try? FileManager.default.createDirectory(atPath: d,
                                             withIntermediateDirectories: true)
    guard let img = drawIcon(size) else {
        fputs("绘制失败 \(size)\n", stderr); exit(1)
    }
    writePNG(img, (d as NSString).appendingPathComponent("ic_launcher.png"))
    print("  \(dir)/ic_launcher.png  \(size)x\(size)")
}
if args.count > 2, let img = drawIcon(512) {
    writePNG(img, args[2])
    print("  preview 512x512 -> \(args[2])")
}
