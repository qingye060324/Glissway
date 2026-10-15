# Changelog

## 1.1.1 — 2026-10-15

- Add the supplied Glissway artwork as regular, adaptive and monochrome launcher icons.
- Apply Android safe-area insets once through the native container; align portrait top spacing with the bottom dock.
- Publish the current Android/WebView project under the Glissway repository name.

## 1.1 — 2026-10-15

- Adopt Glissway branding and integrate the existing HTML interface into Android.
- Center Edit / Live and replace the status dot with a true connection halo.
- Add standard 14-bit Pitch Bend over the existing BLE and RTP-MIDI send paths.
- Add per-mapping output type, axis, sensitivity, channel conflict checks and center reset.
- Add local named presets, explicit save/load, duplication, rename, confirmed deletion and unsaved draft recovery.
- Import existing native/web configuration while preserving the app identity for upgrades.
- Preserve Monet and the existing bubble geometry solver.


## 1.0 — 2026-10-07

- Correct AppleMIDI invitation fields and session-name termination; validate the response token at the correct offset.
- Complete invitations on both control and data ports before reporting a connection.
- Handle clock synchronization and remote disconnects.
- Move DNS, socket operations, MIDI transmission and session shutdown to a background thread.
- Keep session state isolated during disconnect and reconnect, and stop the previous transport when switching between BLE and Wi-Fi.
- Document Windows rtpMIDI and Cubase CC reception.

Validation: the patched Java transport delivered CC messages through the real Windows rtpMIDI service to the Windows MIDI input, including periodic synchronization and reconnection. Android sources compiled and the APK signature verified. The user subsequently confirmed successful testing on their phone with this computer.
