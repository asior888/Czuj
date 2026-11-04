import AVFoundation
import CoreBluetooth
import CoreLocation
import Foundation
import UserNotifications

// Strażnik iOS. Na pierwszym planie skanuje wszystko (szansa na AirTagi),
// w tle tylko z filtrem usług (Tile, SmartTag), bo iOS inaczej nie skanuje w tle.
// Miejsce = kratka ok. 500 m z lokalizacji (iOS nie daje listy sieci Wi-Fi).
final class Guard: NSObject, ObservableObject, CBCentralManagerDelegate, CLLocationManagerDelegate {
    @Published var running = false
    @Published var nearby = 0
    @Published var problem = ""
    @Published var alerts: [String] = []
    @Published var placeKnown = false

    private var central: CBCentralManager?
    private let location = CLLocationManager()
    private let watch = TrackerCore.Watch()
    private let speech = AVSpeechSynthesizer()
    private var foreground = true
    private var lastPlace: Set<String> = []
    private let backgroundServices = ["FEED", "FEEC", "FD5A"].map { CBUUID(string: $0) }

    override init() {
        super.init()
        location.delegate = self
        alerts = UserDefaults.standard.stringArray(forKey: "alerts") ?? []
    }

    // ---------- sterowanie ----------

    func start() {
        running = true
        if central == nil {
            central = CBCentralManager(delegate: self, queue: nil, options: [CBCentralManagerOptionShowPowerAlertKey: true])
        } else {
            scan()
        }
        location.requestWhenInUseAuthorization()
        location.requestAlwaysAuthorization()
        location.startMonitoringSignificantLocationChanges()
        location.startUpdatingLocation()
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, _ in }
    }

    func stop() {
        running = false
        central?.stopScan()
        location.stopUpdatingLocation()
        location.stopMonitoringSignificantLocationChanges()
    }

    func setForeground(_ on: Bool) {
        foreground = on
        if running { scan() }
    }

    private func scan() {
        guard let c = central, c.state == .poweredOn, running else { return }
        c.stopScan()
        c.scanForPeripherals(withServices: foreground ? nil : backgroundServices,
                             options: [CBCentralManagerScanOptionAllowDuplicatesKey: false])
    }

    // ---------- Bluetooth ----------

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        switch central.state {
        case .poweredOn: problem = ""; scan()
        case .poweredOff: problem = "Bluetooth wyłączony"
        case .unauthorized: problem = "brak zgody na Bluetooth (Ustawienia → Czuj)"
        case .unsupported: problem = "ten iPhone nie ma Bluetooth LE"
        default: break
        }
    }

    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
                        advertisementData: [String: Any], rssi RSSI: NSNumber) {
        var mfr: [Int: [UInt8]] = [:]
        if let d = advertisementData[CBAdvertisementDataManufacturerDataKey] as? Data, d.count >= 2 {
            let bytes = [UInt8](d)
            mfr[Int(bytes[0]) | (Int(bytes[1]) << 8)] = Array(bytes.dropFirst(2))
        }
        var uuids: [String] = []
        if let s = advertisementData[CBAdvertisementDataServiceUUIDsKey] as? [CBUUID] { uuids += s.map { $0.uuidString } }
        if let s = advertisementData[CBAdvertisementDataServiceDataKey] as? [CBUUID: Data] { uuids += s.keys.map { $0.uuidString } }
        let tag = TrackerCore.classify(mfr: mfr, uuids: uuids)
        if tag.isEmpty { return }
        let now = Date().timeIntervalSince1970
        if let alert = watch.sighting(addr: peripheral.identifier.uuidString, tag: tag, top: lastPlace, now: now) {
            raise(alert)
        }
        nearby = watch.nearby(now: now)
    }

    // ---------- lokalizacja ----------

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let l = locations.last, l.horizontalAccuracy >= 0, l.horizontalAccuracy < 1000 else { return }
        // kratka ok. 500 m; sąsiednie kratki też w zbiorze, żeby granica kratki nie robiła "nowego miejsca"
        let step = 0.005
        let la = Int((l.coordinate.latitude / step).rounded()), lo = Int((l.coordinate.longitude / step).rounded())
        var cells: Set<String> = []
        for dy in -1...1 { for dx in -1...1 { cells.insert("\(la + dy):\(lo + dx)") } }
        lastPlace = cells
        placeKnown = true
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        placeKnown = false
    }

    // ---------- alarm ----------

    private func raise(_ text: String) {
        let f = DateFormatter(); f.dateFormat = "yyyy-MM-dd HH:mm"
        alerts.append("\(f.string(from: Date())) \(text)")
        if alerts.count > 50 { alerts.removeFirst(alerts.count - 50) }
        UserDefaults.standard.set(alerts, forKey: "alerts")
        let n = UNMutableNotificationContent()
        n.title = "Czuj: możliwy tracker"
        n.body = text
        n.sound = .default
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: UUID().uuidString, content: n, trigger: nil))
        say(text)
    }

    func say(_ text: String) {
        let u = AVSpeechUtterance(string: text)
        u.voice = AVSpeechSynthesisVoice(language: "pl-PL")
        speech.speak(u)
    }

    func statusForModel() -> String {
        if !running { return "strażnik wyłączony" }
        var s = "strażnik włączony, trackerów w pobliżu: \(nearby)"
        if !placeKnown { s += ", miejsce nieznane (brak lokalizacji)" }
        if !problem.isEmpty { s += ", problem: \(problem)" }
        let last = alerts.suffix(3).joined(separator: "; ")
        return s + ", ostatnie alarmy: " + (last.isEmpty ? "brak" : last)
    }

    func localAnswer() -> String {
        if !running { return "Strażnik jest wyłączony. Włącz go przyciskiem na ekranie." }
        if let last = alerts.last { return "Ostatni alarm: \(last)" }
        return "Nie widzę trackera, który by z tobą jechał. Trackerów w pobliżu: \(nearby)."
    }
}
