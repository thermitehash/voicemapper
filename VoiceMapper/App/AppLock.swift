import SwiftUI
import LocalAuthentication

/// Optional Face ID lock (PLAN §6). When on, the app locks at launch and
/// whenever it goes to the background; Face ID (or the device passcode)
/// unlocks it.
@MainActor
@Observable
final class AppLock {
    static let enabledKey = "privacy.faceIDLock"

    private(set) var isLocked: Bool
    private(set) var isAuthenticating = false
    private(set) var lastError: String?

    var isEnabled: Bool { UserDefaults.standard.bool(forKey: Self.enabledKey) }

    init() {
        isLocked = UserDefaults.standard.bool(forKey: Self.enabledKey)
    }

    /// Name of the device's biometry, for labels ("Face ID", "Touch ID" or "Passcode").
    static var biometryName: String {
        let context = LAContext()
        _ = context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: nil)
        switch context.biometryType {
        case .faceID: return "Face ID"
        case .touchID: return "Touch ID"
        case .opticID: return "Optic ID"
        default: return "Passcode"
        }
    }

    /// True if the device can authenticate at all (biometry or passcode).
    static var isAvailable: Bool {
        LAContext().canEvaluatePolicy(.deviceOwnerAuthentication, error: nil)
    }

    func lockIfEnabled() {
        if isEnabled { isLocked = true }
    }

    func unlock() async {
        guard isLocked, !isAuthenticating else { return }
        if await authenticate(reason: "Unlock VoiceMapper") { isLocked = false }
    }

    /// Asks once when turning the lock on, so it can't be enabled on a device
    /// that can't unlock it.
    func confirmEnable() async -> Bool {
        await authenticate(reason: "Turn on the app lock")
    }

    /// Settings ▸ Delete all data.
    func reset() { isLocked = false; lastError = nil }

    private func authenticate(reason: String) async -> Bool {
        isAuthenticating = true
        defer { isAuthenticating = false }
        let context = LAContext()
        var error: NSError?
        guard context.canEvaluatePolicy(.deviceOwnerAuthentication, error: &error) else {
            lastError = "Set a passcode in iOS Settings to use the lock."
            return false
        }
        do {
            let ok = try await context.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: reason)
            lastError = nil
            return ok
        } catch let e as LAError where e.code == .userCancel || e.code == .appCancel || e.code == .systemCancel {
            lastError = nil
            return false
        } catch {
            lastError = error.localizedDescription
            return false
        }
    }
}

/// Covers the app while locked. Also hides content in the app switcher.
struct LockScreen: View {
    @Environment(AppLock.self) private var lock

    var body: some View {
        VStack(spacing: 20) {
            Image(systemName: "lock.fill")
                .font(.system(size: 48))
                .foregroundStyle(.secondary)
            Text("VoiceMapper is locked").font(.title2.weight(.semibold))
            Button("Unlock with \(AppLock.biometryName)") {
                Task { await lock.unlock() }
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .disabled(lock.isAuthenticating)
            if let error = lock.lastError {
                Text(error).font(.footnote).foregroundStyle(.secondary).multilineTextAlignment(.center)
            }
        }
        .padding()
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(.background)
    }
}
