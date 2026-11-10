import Foundation
import Security
import Shared

/// app.md §4 `SecretStore` on iOS: Keychain generic password,
/// `kSecAttrAccessibleWhenUnlockedThisDeviceOnly`, never synchronized to iCloud.
///
/// Contract (see Kotlin `SecretStore`): `get` returns nil only when the item does not
/// exist; every other Keychain failure throws, so an unreadable identity is never
/// mistaken for a missing one and overwritten.
final class KeychainSecretStore: NSObject, SecretStore {
    private let service = "org.ferabrasil.quests.secret-store.v1"

    func get(name: String) throws -> KotlinByteArray? {
        var query = baseQuery(name)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne

        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        switch status {
        case errSecSuccess:
            guard let data = item as? Data else { throw KeychainError(status: errSecDecode) }
            return data.toKotlinByteArray()
        case errSecItemNotFound:
            return nil
        default:
            throw KeychainError(status: status)
        }
    }

    func put(name: String, value: KotlinByteArray) throws {
        let data = value.toData()
        let update: [String: Any] = [kSecValueData as String: data]
        var status = SecItemUpdate(baseQuery(name) as CFDictionary, update as CFDictionary)
        if status == errSecItemNotFound {
            var add = baseQuery(name)
            add[kSecValueData as String] = data
            add[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
            status = SecItemAdd(add as CFDictionary, nil)
        }
        guard status == errSecSuccess else { throw KeychainError(status: status) }
    }

    func delete(name: String) throws {
        let status = SecItemDelete(baseQuery(name) as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw KeychainError(status: status)
        }
    }

    private func baseQuery(_ name: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: name,
            kSecAttrSynchronizable as String: false,
        ]
    }
}

struct KeychainError: LocalizedError {
    let status: OSStatus
    var errorDescription: String? { "Keychain error \(status)" }
}
