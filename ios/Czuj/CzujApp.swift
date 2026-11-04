import SwiftUI

@main
struct CzujApp: App {
    @StateObject private var guardian = Guard()
    @StateObject private var listener = Listener()
    @Environment(\.scenePhase) private var phase

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(guardian)
                .environmentObject(listener)
                .preferredColorScheme(.dark)
        }
        .onChange(of: phase) { p in guardian.setForeground(p == .active) }
    }
}

struct ContentView: View {
    @EnvironmentObject var guardian: Guard
    @EnvironmentObject var listener: Listener
    @State private var question = ""
    @State private var answer = "Zapytaj na przykład: czy ktoś mnie śledzi?"
    @State private var keyField = ""

    private let bg = Color(red: 0x14 / 255, green: 0x17 / 255, blue: 0x22 / 255)
    private let card = Color(red: 0x1E / 255, green: 0x23 / 255, blue: 0x33 / 255)
    private let accent = Color(red: 0x6F / 255, green: 0xA8 / 255, blue: 0xDC / 255)

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Czuj").font(.largeTitle.bold())
                    Text("Strażnik w kieszeni").foregroundColor(.secondary)
                }
                section {
                    Text(guardian.running ? "Pilnuję. Trackerów w pobliżu: \(guardian.nearby)" : "Strażnik wyłączony")
                        .font(.headline).foregroundColor(guardian.running ? accent : .primary)
                    if !guardian.problem.isEmpty { Text("⚠️ " + guardian.problem).font(.subheadline) }
                    action(guardian.running ? "Wyłącz strażnika" : "Włącz strażnika") {
                        guardian.running ? guardian.stop() : guardian.start()
                    }
                }
                section {
                    label("Rozmowa")
                    Text(answer)
                    action(listener.listening ? "Słucham…" : "Zapytaj głosem") {
                        listener.listen { heard in
                            if let h = heard { send(h) } else { answer = "Nie usłyszałem. Napisz pytanie." }
                        }
                    }
                    TextField("Napisz pytanie", text: $question).textFieldStyle(.roundedBorder)
                    action("Wyślij") { send(question) }
                }
                section {
                    label("Ostatnie alarmy")
                    Text(guardian.alerts.isEmpty ? "Brak alarmów." : guardian.alerts.suffix(5).reversed().joined(separator: "\n\n"))
                        .font(.subheadline)
                }
                section {
                    label("Co działa na tym iPhonie")
                    capsView
                    SecureField("Klucz Anthropic (opcjonalny)", text: $keyField).textFieldStyle(.roundedBorder)
                    action("Zapisz klucz") { Brain.shared.key = keyField; keyField = "" }
                }
            }
            .padding(16)
        }
        .background(bg.ignoresSafeArea())
    }

    private var capsView: some View {
        VStack(alignment: .leading, spacing: 4) {
            row(guardian.problem.isEmpty, "Bluetooth", guardian.problem)
            row(guardian.placeKnown, "Lokalizacja (odcisk miejsca)", "zezwól na lokalizację, bez niej alarmy nie zadziałają")
            row(listener.available, "Rozpoznawanie mowy", "pisz pytania")
            row(!Brain.shared.key.isEmpty, "Asystent AI", "bez klucza odpowiadam tylko o stanie skanera")
            Text("ℹ️ W tle iOS pozwala szukać tylko Tile i SmartTag. AirTagi wykrywa sam iOS (ostrzeżenia systemowe).")
                .font(.footnote).foregroundColor(.secondary)
        }.font(.subheadline)
    }

    private func row(_ ok: Bool, _ name: String, _ why: String) -> some View {
        Text((ok ? "✅ " : "❌ ") + name + (!ok && !why.isEmpty ? ": " + why : ""))
    }

    private func send(_ raw: String) {
        let text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        question = ""
        answer = "Ty: \(text)\n…"
        Task { @MainActor in
            var reply: String
            if Brain.shared.key.isEmpty {
                reply = guardian.localAnswer()
            } else {
                do { reply = try await Brain.shared.ask(text, status: guardian.statusForModel()) }
                catch { reply = guardian.localAnswer() + " (asystent offline: \(error.localizedDescription))" }
            }
            answer = "Ty: \(text)\nCzuj: \(reply)"
            guardian.say(reply.components(separatedBy: " (asystent offline").first ?? reply)
        }
    }

    private func section<V: View>(@ViewBuilder _ content: () -> V) -> some View {
        VStack(alignment: .leading, spacing: 10, content: content)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(16)
            .background(card)
            .cornerRadius(16)
    }

    private func label(_ s: String) -> some View {
        Text(s.uppercased()).font(.caption.bold()).foregroundColor(.secondary)
    }

    private func action(_ title: String, _ run: @escaping () -> Void) -> some View {
        Button(action: run) {
            Text(title).frame(maxWidth: .infinity, minHeight: 44)
        }
        .buttonStyle(.borderedProminent)
        .tint(accent)
    }
}
