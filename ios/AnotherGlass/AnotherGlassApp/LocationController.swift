import CoreLocation
import Foundation

final class LocationController: NSObject, CLLocationManagerDelegate {
    var isEnabled = true

    private let manager = CLLocationManager()
    private let onLocation: (CLLocation) -> Void
    private var lastSentAt = Date.distantPast

    init(onLocation: @escaping (CLLocation) -> Void) {
        self.onLocation = onLocation
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBest
        manager.distanceFilter = kCLDistanceFilterNone
        manager.allowsBackgroundLocationUpdates = true
        manager.pausesLocationUpdatesAutomatically = false
    }

    func start() {
        guard isEnabled else { return }

        switch manager.authorizationStatus {
        case .notDetermined:
            manager.requestAlwaysAuthorization()
        case .authorizedAlways, .authorizedWhenInUse:
            manager.startUpdatingLocation()
        case .denied, .restricted:
            break
        @unknown default:
            break
        }
    }

    func stop() {
        manager.stopUpdatingLocation()
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        start()
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard isEnabled, let location = locations.last else { return }
        guard Date().timeIntervalSince(lastSentAt) >= ServiceID.gpsMinimumInterval else { return }
        lastSentAt = Date()
        onLocation(location)
    }
}
