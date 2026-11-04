import AVFoundation
import Foundation
import Speech

// Pytanie głosem: nagrywa do 6 s, rozpoznaje po polsku (Apple Speech).
final class Listener: ObservableObject {
    @Published var listening = false
    private let recognizer = SFSpeechRecognizer(locale: Locale(identifier: "pl-PL"))
    private let engine = AVAudioEngine()
    private var request: SFSpeechAudioBufferRecognitionRequest?
    private var task: SFSpeechRecognitionTask?
    private var best = ""

    var available: Bool { recognizer?.isAvailable ?? false }

    func listen(seconds: Double = 6, done: @escaping (String?) -> Void) {
        SFSpeechRecognizer.requestAuthorization { status in
            DispatchQueue.main.async {
                guard status == .authorized, self.available else { done(nil); return }
                do { try self.begin(seconds: seconds, done: done) } catch { self.finish(); done(nil) }
            }
        }
    }

    private func begin(seconds: Double, done: @escaping (String?) -> Void) throws {
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.playAndRecord, mode: .measurement, options: [.duckOthers, .defaultToSpeaker])
        try session.setActive(true, options: .notifyOthersOnDeactivation)
        let req = SFSpeechAudioBufferRecognitionRequest()
        req.shouldReportPartialResults = true
        request = req
        best = ""
        let input = engine.inputNode
        input.installTap(onBus: 0, bufferSize: 1024, format: input.outputFormat(forBus: 0)) { buf, _ in
            req.append(buf)
        }
        engine.prepare()
        try engine.start()
        listening = true
        task = recognizer?.recognitionTask(with: req) { result, _ in
            if let r = result { self.best = r.bestTranscription.formattedString }
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + seconds) {
            let text = self.best
            self.finish()
            done(text.isEmpty ? nil : text)
        }
    }

    private func finish() {
        engine.stop()
        engine.inputNode.removeTap(onBus: 0)
        request?.endAudio()
        task?.cancel()
        request = nil
        task = nil
        listening = false
        try? AVAudioSession.sharedInstance().setCategory(.playback)
    }
}
