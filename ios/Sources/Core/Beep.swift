import Foundation
import AVFoundation

/// The alert beep. A system sound (AudioServicesPlaySystemSound) is silenced by the phone's ring/silent switch,
/// which would make alerts silently disappear, so the beep is played through the audio session like the speech.
enum Beep {
    private static var player: AVAudioPlayer?

    /// 0.25 s, 1 kHz, 16-bit mono WAV generated in memory (10 ms fade in/out so it doesn't click).
    static let wavData: Data = {
        let rate = 44100, n = rate / 4
        var pcm = Data(capacity: n * 2)
        for i in 0..<n {
            let t = Double(i) / Double(rate)
            let fade = min(1.0, Double(min(i, n - i)) / 441.0)
            let v = Int16(sin(2 * Double.pi * 1000 * t) * 0.6 * fade * Double(Int16.max))
            withUnsafeBytes(of: v.littleEndian) { pcm.append(contentsOf: $0) }
        }
        var d = Data()
        func u32(_ x: UInt32) { withUnsafeBytes(of: x.littleEndian) { d.append(contentsOf: $0) } }
        func u16(_ x: UInt16) { withUnsafeBytes(of: x.littleEndian) { d.append(contentsOf: $0) } }
        d.append(contentsOf: Array("RIFF".utf8)); u32(UInt32(36 + pcm.count)); d.append(contentsOf: Array("WAVE".utf8))
        d.append(contentsOf: Array("fmt ".utf8)); u32(16); u16(1); u16(1); u32(UInt32(rate)); u32(UInt32(rate * 2)); u16(2); u16(16)
        d.append(contentsOf: Array("data".utf8)); u32(UInt32(pcm.count)); d.append(pcm)
        return d
    }()

    static func play() {
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.playback, mode: .voicePrompt, options: [.duckOthers])
        try? session.setActive(true)
        player = try? AVAudioPlayer(data: wavData)
        player?.prepareToPlay()
        player?.play()
    }
}
