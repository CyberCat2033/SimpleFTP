# AGENTS.md

## Purpose

These instructions define the required working rules for Codex in this repository. Follow them when reading, planning, editing, testing, reviewing, and committing changes.

## Product scope

- This project is **Simple FTP**: a minimal Android FTP server for Android-based e-readers.
- Keep the app focused on the eBookSender use case: start a local FTP server, show a large QR code, let the user choose the served folder, and expose only the FTP behavior needed for reliable local file transfer.
- The UI is adapted for PocketBook-style e-ink devices: monochrome, high-contrast, large text, square controls, no decorative motion, and no visual density that depends on fast refresh.
- Do not turn this project into a full file manager, OPDS client, cloud sync app, media library, or general-purpose server suite without explicit product direction.
- Product-facing naming should stay aligned with `Simple FTP`. Refer to `eBookSender` only when describing the Android client integration. Refer to `PocketBook` only for e-reader compatibility, e-ink UI behavior, or related projects.

## Engineering principles

- Follow Clean Code, KISS, DRY, and SOLID.
- Prefer simple, explicit, maintainable solutions over clever or over-engineered ones.
- Preserve the current single-module Android project structure unless there is a clear technical reason to change it.
- Keep dependencies minimal. Add a dependency only when it removes real complexity or risk.
- Do not duplicate logic. Extract a local helper when similar behavior appears in more than one place.
- Keep public behavior stable for eBookSender compatibility unless the task explicitly requires a breaking change.

## Android and UI standards

- Use Kotlin and Jetpack Compose patterns already present in the project.
- Keep the e-ink UI monochrome, static, readable, and resilient to long Russian and English text.
- Avoid animations, gradients, decorative cards, dynamic color, haptics, and nonessential Material styling. This app is a utility for slow-refresh screens.
- Use `MaterialTheme` through the existing `EInkTheme`; do not hard-code a second visual system.
- Keep user-facing strings in Android resources and update both `app/src/main/res/values/strings.xml` and `app/src/main/res/values-ru/strings.xml` for every visible text change.
- Prefer large touch targets and simple screen flows over compact phone-first controls.

## FTP and security standards

- Treat the FTP server as a local-network, anonymous server by design.
- Do not add credential storage, internet exposure, port forwarding assumptions, or remote access features without explicit product direction.
- Keep file access constrained to the selected FTP root. Defend against `..`, absolute-path escape, symlink/canonical-path escape, invalid rename targets, and unsafe deletion behavior.
- When changing FTP commands, verify the impact on eBookSender and common passive-mode FTP clients.
- Preserve passive FTP support. Active FTP is out of scope unless explicitly requested.
- Do not log secrets, full user file contents, or unnecessary filesystem details.
- Handle errors explicitly and surface useful messages through the existing status/UI pattern.

## Documentation and changelog

- `README.md` is user-facing documentation. Keep internal Codex instructions, implementation plans, and maintainer reminders out of it.
- Keep `README.md` honest about the minimal scope and limitations of the app.
- Keep `CHANGELOG.md` updated for user-facing changes, release notes, packaging changes, and compatibility changes.
- At task completion, decide whether `AGENTS.md` needs updates. Update it yourself when workflow, architecture, important paths, shared patterns, or verification commands change.
- At the same checkpoint, decide whether `CHANGELOG.md` needs updates and state the result in the final response.

## Working process

- Start by reading relevant files and existing analogues before editing.
- Use `rg` and `rg --files` for search.
- Keep edits tightly scoped to the requested behavior.
- For large or ambiguous tasks, create a concise professional plan first and wait for approval before implementation.
- For small, obvious fixes, proceed directly on a task-appropriate branch while keeping scope tight.
- Do not leave temporary, dead, debug-only, or commented-out code.

## Git workflow

- Check `git status --short --branch` before making changes.
- Do all work on a task-appropriate branch, not directly on `main`, unless the user explicitly asks otherwise.
- For new features, create a dedicated branch named `feature/<short-name>`.
- For bug fixes, create a dedicated branch named `bugfix/<short-name>`.
- For refactoring and small maintenance tasks, use `refactoring` or `refactor/<short-name>` when a separate branch is useful.
- Never overwrite, reset, or revert user changes unless explicitly requested.
- Commit every completed code change that modifies more than five lines, unless the user explicitly asks not to commit.
- Use professional commit messages: write a concise imperative subject that names the actual change, keep it specific enough to stand alone in history, and avoid vague subjects such as `fix`, `update`, or `changes`.
- Keep commits logically grouped. Stage and commit only the files that belong to the current task.

## Verification

- Run the smallest relevant verification first, then broaden when the blast radius is larger.
- For Kotlin-only changes, prefer:

  ```sh
  GRADLE_USER_HOME=/tmp/gradle-home ./gradlew :app:compileDebugKotlin
  ```

- For broader Android changes, run:

  ```sh
  GRADLE_USER_HOME=/tmp/gradle-home ./gradlew :app:assembleDebug
  ```

- For release workflow or packaging changes, inspect `.github/workflows/ci-cd.yml` and verify that tag builds produce the expected APK name.
- If verification cannot be run, state the exact reason and residual risk.

## Project map

| Path | Responsibility |
| --- | --- |
| `app/src/main/java/com/cybercat/simpleftp/MainActivity.kt` | Android entry point and e-ink Compose UI |
| `app/src/main/java/com/cybercat/simpleftp/MinimalFtpServer.kt` | Minimal passive FTP server implementation |
| `app/src/main/java/com/cybercat/simpleftp/NetworkAddress.kt` | Local IPv4 discovery for the FTP URL |
| `app/src/main/java/com/cybercat/simpleftp/PathRepository.kt` | Persisted served-folder selection |
| `app/src/main/java/com/cybercat/simpleftp/QrCodeGenerator.kt` | QR code generation |
| `.github/workflows/ci-cd.yml` | Debug verification and tagged release publication |
