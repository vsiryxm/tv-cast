import Foundation
import Vision
import AppKit
let path = CommandLine.arguments[1]
guard let img = NSImage(contentsOfFile: path),
      let cg = img.cgImage(forProposedRect: nil, context: nil, hints: nil) else {
    print("ERR: cannot load image"); exit(1)
}
let req = VNRecognizeTextRequest()
req.recognitionLevel = .accurate
req.recognitionLanguages = ["zh-Hans", "zh-Hant", "en-US"]
req.usesLanguageCorrection = true
let handler = VNImageRequestHandler(cgImage: cg, options: [:])
do { try handler.perform([req]) } catch { print("ERR: \(error)"); exit(1) }
for obs in (req.results ?? []) {
    guard let c = obs.topCandidates(1).first else { continue }
    let b = obs.boundingBox
    let s = String(format: "%.3f\t%.3f\t%.3f\t%.3f\t%@", b.origin.x, b.origin.y, b.width, b.height, c.string)
    print(s)
}
