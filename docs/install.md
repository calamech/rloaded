# Installing Rloaded

Each release on the [releases page](https://github.com/calamech/rloaded/releases) has the launcher for players, the server jar for admins, and `SHA256SUMS`. The installers are not signed yet, so macOS and Windows warn before they open them the first time.

## Players: the launcher

| System | File |
| --- | --- |
| macOS on Apple silicon | `Rloaded-<version>-macos-arm64.dmg` |
| macOS on Intel | `Rloaded-<version>-macos-x64.dmg` |
| Windows x64 | `Rloaded-<version>-windows-x64.msi` |
| Linux x64 (Debian, Ubuntu) | `Rloaded-<version>-linux-x64.deb` |

To check a download, compare its SHA-256 with its line in `SHA256SUMS`: `shasum -a 256 <file>` on macOS, `sha256sum <file>` on Linux, `Get-FileHash <file>` in PowerShell.

### macOS

1. Open the `.dmg` and drag Rloaded to Applications. Start it from Applications: started from the disk image, it cannot update itself.
2. The first time, macOS refuses to open it because it cannot check the developer. Open System Settings, then Privacy & Security, scroll to Security, and click Open Anyway next to Rloaded, then confirm with your password. macOS then opens it like any other app.

### Windows

1. Run the `.msi`. It installs for your account only, without administrator rights.
2. If Windows shows "Windows protected your PC", click More info, then Run anyway. On Windows 11 with Smart App Control turned on, Windows blocks unsigned installers without that choice; Rloaded cannot be installed there until it is signed.

### Linux

Install the package with `sudo apt install ./Rloaded-<version>-linux-x64.deb`. The launcher goes to `/opt/rloaded`; start it with `/opt/rloaded/bin/Rloaded`.

### Updates

When a newer release exists, the launcher asks when it starts. Install downloads it, checks it against `SHA256SUMS`, closes the launcher and opens the new one. On Linux the system asks for your password, since the package goes to `/opt`.

## Admins: the server

`rloaded-server.jar` needs Java 21 or newer to start; it then installs the Java runtime, the game server and Fabric it needs. Put it in an empty folder and run `java -jar rloaded-server.jar`. The first start writes `rloaded-server.toml` and `eula.txt`, then stops: read the [Minecraft EULA](https://aka.ms/MinecraftEULA), set `eula=true` in `eula.txt` if you accept it, and start the server again.
