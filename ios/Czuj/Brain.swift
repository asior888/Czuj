import Foundation

// Asystent: Anthropic Messages API. Klucz zapisany tylko w tym iPhonie.
final class Brain {
    static let shared = Brain()
    private let model = "claude-sonnet-5-5"
    private let system =
        "Jesteś Czuj, osobisty strażnik w kieszeni. Pilnujesz, czy za użytkownikiem " +
        "nie jeździ nieznany tracker Bluetooth (AirTag, Tile, SmartTag). " +
        "Odpowiadaj po polsku, maksymalnie 2 krótkie zdania, bez list i formatowania. " +
        "Nie wymyślaj wykryć: o trackerach mów tylko na podstawie stanu skanera. " +
        "Jeśli ktoś czuje się zagrożony, poradź zadzwonić pod 112."
    private var history: [[String: String]] = []

    var key: String {
        get { UserDefaults.standard.string(forKey: "anthropic_key") ?? "" }
        set { UserDefaults.standard.set(newValue.trimmingCharacters(in: .whitespaces), forKey: "anthropic_key") }
    }

    struct ApiError: LocalizedError {
        let message: String
        var errorDescription: String? { message }
    }

    @MainActor
    func ask(_ text: String, status: String) async throws -> String {
        history.append(["role": "user", "content": text])
        while history.count > 20 { history.removeFirst(2) }
        let body: [String: Any] = [
            "model": model,
            "max_tokens": 300,
            "system": system + "\nAktualny stan skanera: " + status,
            "messages": history,
        ]
        var req = URLRequest(url: URL(string: "https://api.anthropic.com/v1/messages")!)
        req.httpMethod = "POST"
        req.timeoutInterval = 60
        req.setValue(key, forHTTPHeaderField: "x-api-key")
        req.setValue("2023-06-01", forHTTPHeaderField: "anthropic-version")
        req.setValue("application/json", forHTTPHeaderField: "content-type")
        req.httpBody = try JSONSerialization.data(withJSONObject: body)
        do {
            let (data, resp) = try await URLSession.shared.data(for: req)
            let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
            guard (200..<300).contains(code),
                  let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let content = json["content"] as? [[String: Any]],
                  let answer = (content.first?["text"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines)
            else {
                throw ApiError(message: "API \(code): " + String(decoding: data.prefix(160), as: UTF8.self))
            }
            history.append(["role": "assistant", "content": answer])
            return answer
        } catch {
            if history.last?["role"] == "user" { history.removeLast() }
            throw error
        }
    }
}
