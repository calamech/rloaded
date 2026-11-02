# ADR 0066: The launcher offers a newer release and installs it when the player accepts

- Status: Accepted
- Date: 2026-11-02

## Context

Releases are published on GitHub with an installer per platform and a `SHA256SUMS` file (ADR 0065). A player who installed the launcher once would otherwise keep that version forever, and a server may need a newer runtime mod than the player's launcher carries.

## Decision

- When it starts, the launcher reads the latest release of `calamech/rloaded` from the GitHub API. If its tag is a newer version than the launcher's own, and the release has the installer for this platform, the launcher tells the player and asks whether to download and install it. On yes, it downloads the installer, checks it against `SHA256SUMS`, installs it and starts again. On "Not now", it asks again at the next start: there is no "skip this version".
- `UpdateCheck` (launcher-core) holds the rules: tags are `v<major>.<minor>.<patch>` and compared as numbers; pre-release tags are not versions; the installer is `Rloaded-<version>-<platform>.<format>`, for the four platforms the release builds; a platform without an installer is never offered an update and never asks GitHub; the installer and the sums must come from under `https://github.com/calamech/rloaded/releases/download/`.
- The launcher reads releases through `UrlPolicy.UPDATES`: `api.github.com`, `github.com` and `release-assets.githubusercontent.com`, where GitHub's download links redirect (seen 2026-11-02), over HTTPS. `UrlPolicy.PRODUCTION`, used for game and mod files, does not get GitHub.
- A failed check is logged and the launcher works as before: a check the player did not ask for never shows an error. A failed download or install, which the player asked for, is shown under the question with what to do, and can be tried again.
- The installer is downloaded into `updates/installers` of the data folder and checked like every other download. The launcher then starts an install script as a process of its own and quits; the script waits for the launcher to end, installs, and starts the launcher again, the old one if the install failed. Install is disabled while the game runs, since the launcher quits.
  - **macOS**: `install-macos.sh` mounts the disk image, copies `Rloaded.app` next to the running bundle, swaps the two, and opens the new one. The bundle comes from `jpackage.app-path`, which the packaged launcher sets (read on 2026-11-02: `<bundle>/Contents/MacOS/Rloaded`). A launcher run from its disk image, or from a folder it cannot write to, says to move it to Applications first.
  - **Windows**: the `.msi` is per user and keeps one upgrade code across versions, so `msiexec /i … /passive` replaces the installed launcher without administrator rights.
  - **Linux**: `pkexec dpkg -i` installs the `.deb`, which goes to `/opt` and asks for the administrator's password.

## Alternatives

- **Only telling the player and linking to the download page.** Most players would never update by hand; the project owner chose the installation on yes (2026-11-02).
- **Installing without asking.** An update restarts the launcher; the player decides when.
- **A "skip this version" choice.** Nothing needs it yet; asking at each start until the player updates keeps a single rule.

## Consequences

- The sums come from the same release as the installer: they catch a damaged download, not a release published from a compromised account. Signing the sums with a key held outside GitHub would cover that.
- On Windows and Linux the install scripts are tested with stand-ins for `msiexec` and `dpkg`; a real installer replacing a real install is checked by hand.
- GitHub's API allows 60 unauthenticated requests per hour per address; one check per start stays far below.

## Enforcement

`UpdateCheckTest`, `VersionTest`, `UrlPolicyTest`, `UpdateStateTest`, and `UpdateInstallationTest`, which runs each install script on the CI system it is for: on macOS it swaps a bundle from a real disk image.
