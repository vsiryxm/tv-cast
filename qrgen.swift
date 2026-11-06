// 用 macOS 自带 CoreImage 生成二维码 PNG，零第三方依赖。
// 用法: qrgen <文本> <输出.png>
import Foundation
import CoreImage
import AppKit

let args = CommandLine.arguments
guard args.count >= 3, let data = args[1].data(using: .utf8) else {
    print("usage: qrgen <text> <out.png>"); exit(1)
}
guard let filter = CIFilter(name: "CIQRCodeGenerator") else { exit(1) }
filter.setValue(data, forKey: "inputMessage")
filter.setValue("M", forKey: "inputCorrectionLevel")
guard let ci = filter.outputImage else { exit(1) }
let scaled = ci.transformed(by: CGAffineTransform(scaleX: 10, y: 10))
let rep = NSCIImageRep(ciImage: scaled)
let image = NSImage(size: rep.size)
image.addRepresentation(rep)
guard let tiff = image.tiffRepresentation,
      let bitmap = NSBitmapImageRep(data: tiff),
      let png = bitmap.representation(using: .png, properties: [:]) else { exit(1) }
do {
    try png.write(to: URL(fileURLWithPath: args[2]))
} catch {
    print("ERR: \(error)"); exit(1)
}
