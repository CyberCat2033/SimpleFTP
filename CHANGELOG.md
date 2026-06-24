# Changelog

All notable user-facing changes to Simple FTP are documented in this file.

## [Unreleased]

### Added
- Added a startup Wi-Fi warning with a shortcut to Android Wi-Fi settings.
- Added client connection limits (max 5) to prevent server resource exhaustion.
- Added 5-minute control socket timeout to prevent thread leakage on inactive sessions.
- Added explicit protection preventing deletion of the FTP root directory.
- Checked both read and write permissions on older Android versions (API < 30).

### Changed
- Optimized QR code generation: reduced default size to 256x256 and moved bitmap creation off the main thread.
- Optimized folder listing in PathScreen to run asynchronously on a background I/O thread.
- Thread-safed directory path updates with @Volatile keyword.

## [0.1.0] - 2026-06-22

### Added

- Added user-facing README documentation for installing, using, diagnosing, building, and releasing Simple FTP.
- Added repository guidance for Codex agents focused on the minimal eBookSender/PocketBook FTP-server scope.
- Added GitHub Actions CI/CD documentation and release workflow support.
