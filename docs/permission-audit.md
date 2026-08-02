# Permission Audit — Forge OS

**Date:** 2026-08-02  
**Auditor:** Blackbox CLI  
**Scope:** All tool providers, services, and UI components

---

## Summary

| Status | Count |
|--------|-------|
| ✅ Declared & Requested | 18 |
| ⚠️ Declared but NOT in batch request | 3 |
| ❌ Used but NOT declared | 0 |
| 🔍 Sub-permissions / Special access | 4 |

---

## ✅ Fully Covered Permissions

These are declared in `AndroidManifest.xml` AND requested in `MainActivity.ensureDangerousPermissions()`:

| Permission | Used By | Notes |
|-----------|---------|-------|
| `READ_CONTACTS` | ContactsToolProvider | ✅ |
| `WRITE_CONTACTS` | ContactsToolProvider (future) | ✅ Declared, no write tools yet |
| `READ_CALENDAR` | CalendarToolProvider | ✅ |
| `WRITE_CALENDAR` | CalendarToolProvider | ✅ |
| `READ_SMS` | SmsToolProvider | ✅ |
| `SEND_SMS` | SmsToolProvider | ✅ |
| `RECEIVE_SMS` | (no receiver yet) | ✅ Declared, no SMS receiver implemented |
| `READ_CALL_LOG` | PhoneCallToolProvider | ✅ |
| `WRITE_CALL_LOG` | (no write tools) | ✅ Declared, no write tools implemented |
| `CALL_PHONE` | PhoneCallToolProvider | ✅ |
| `ACCESS_FINE_LOCATION` | LocationToolProvider, WifiToolProvider | ✅ |
| `ACCESS_COARSE_LOCATION` | LocationToolProvider, WifiToolProvider | ✅ |
| `BLUETOOTH_CONNECT` (API 31+) | BluetoothToolProvider | ✅ |
| `BLUETOOTH_SCAN` (API 31+) | BluetoothToolProvider | ✅ |
| `READ_MEDIA_IMAGES` (API 33+) | StorageToolProvider | ✅ |
| `READ_MEDIA_VIDEO` (API 33+) | StorageToolProvider | ✅ |
| `READ_MEDIA_AUDIO` (API 33+) | StorageToolProvider | ✅ |
| `READ_EXTERNAL_STORAGE` (≤API 32) | StorageToolProvider | ✅ |

---

## ⚠️ Declared but NOT in Batch Request

These are in the manifest but NOT included in `MainActivity.ensureDangerousPermissions()`:

| Permission | Used By | Risk |
|-----------|---------|------|
| `RECORD_AUDIO` | VoiceInputButton, VoiceModeOverlay, HotwordDetectionService | **LOW** — Requested separately in onboarding + voice UI |
| `POST_NOTIFICATIONS` | NotificationHelper, MainActivity | **LOW** — Requested separately on first launch |
| `CAMERA` | (no camera tools yet) | **NONE** — Declared for future use |

**Recommendation:** These are handled separately and don't need to be in the batch request. No action needed.

---

## ❌ Used but NOT Declared

**None found.** All permissions used by tool providers are properly declared.

---

## 🔍 Sub-Permissions / Special Access

These are NOT standard Android permissions but special access modes or app-ops that tools may need:

| Special Access | Used By | Status | Notes |
|---------------|---------|--------|-------|
| **Notification Listener** | AutoPhoneToolProvider (`phone_notification_*`) | ⚠️ **EXTERNAL** | Requires AutoPhone app to have `BIND_NOTIFICATION_LISTENER_SERVICE` — handled by AutoPhone, not Forge OS |
| **Accessibility Service** | AutoPhoneToolProvider (`autophone_*`) | ⚠️ **EXTERNAL** | Requires AutoPhone app to have `BIND_ACCESSIBILITY_SERVICE` — handled by AutoPhone |
| **Media Projection** | AutoPhoneToolProvider (`autophone_screenshot`) | ⚠️ **EXTERNAL** | Requires AutoPhone to request `MediaProjection` — handled by AutoPhone |
| **Usage Stats** | (not used) | ❌ Not needed | Would require `PACKAGE_USAGE_STATS` |
| **System Alert Window** | (not used) | ❌ Not needed | Would require `SYSTEM_ALERT_WINDOW` |
| **Write Settings** | AndroidController (brightness read-only) | ❌ Not needed | Only reads brightness; writing would need `WRITE_SETTINGS` |
| **Device Admin** | (not used) | ❌ Not needed | Would require `BIND_DEVICE_ADMIN` |
| **Biometric** | (not used) | ❌ Not needed | Would require `USE_BIOMETRIC` |
| **Body Sensors** | (not used) | ❌ Not needed | Would require `BODY_SENSORS` |
| **Activity Recognition** | (not used) | ❌ Not needed | Would require `ACTIVITY_RECOGNITION` |
| **NFC** | (not used) | ❌ Not needed | Would require `NFC` |
| **VPN** | (not used) | ❌ Not needed | Would require `BIND_VPN_SERVICE` |

---

## 📋 Permission Request Flow

### Onboarding (PermissionManager)
- `RECORD_AUDIO` — required
- `POST_NOTIFICATIONS` — required
- `READ_EXTERNAL_STORAGE` — optional
- `CAMERA` — optional
- `ACCESS_FINE_LOCATION` — optional

### Batch Request (MainActivity.ensureDangerousPermissions)
- Contacts: `READ_CONTACTS`, `WRITE_CONTACTS`
- Calendar: `READ_CALENDAR`, `WRITE_CALENDAR`
- SMS: `READ_SMS`, `SEND_SMS`, `RECEIVE_SMS`
- Call Log: `READ_CALL_LOG`, `WRITE_CALL_LOG`, `CALL_PHONE`
- Location: `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`
- Bluetooth (API 31+): `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN`
- Media (API 33+): `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, `READ_MEDIA_AUDIO`
- Storage (≤API 32): `READ_EXTERNAL_STORAGE`

### Separate Requests
- `POST_NOTIFICATIONS` — requested on first launch (API 33+)
- `RECORD_AUDIO` — requested when voice input is used
- `com.forge.autophone.permission.CONTROL` — requested when AutoPhone is connected

---

## 🎯 Recommendations

### 1. No Missing Permissions
All permissions used by tool providers are properly declared in the manifest and requested at runtime.

### 2. Future-Proofing
If you plan to add these features, you'll need to declare + request:

| Feature | Permission Needed |
|---------|-----------------|
| Camera tools (OCR, scanning) | `CAMERA` ✅ already declared |
| SMS receiver (auto-reply) | `RECEIVE_SMS` ✅ already declared |
| Write call log | `WRITE_CALL_LOG` ✅ already declared |
| Write contacts | `WRITE_CONTACTS` ✅ already declared |
| Usage stats | `PACKAGE_USAGE_STATS` (special access) |
| System overlay | `SYSTEM_ALERT_WINDOW` (special access) |
| Write system settings | `WRITE_SETTINGS` (special access) |
| Biometric auth | `USE_BIOMETRIC` |
| Body sensors | `BODY_SENSORS` |
| Activity recognition | `ACTIVITY_RECOGNITION` |
| NFC | `NFC` |

### 3. AutoPhone Dependencies
The following are handled by the AutoPhone companion app, not Forge OS:
- Notification Listener access
- Accessibility Service
- Media Projection (screenshots)

These are **not** Forge OS permissions and don't need to be declared in Forge OS's manifest.

---

## ✅ Conclusion

**All permissions are properly declared and requested.** No missing permissions found.

The only "sub-permissions" are special access modes (Notification Listener, Accessibility, Media Projection) which are handled by the AutoPhone companion app, not Forge OS itself.
