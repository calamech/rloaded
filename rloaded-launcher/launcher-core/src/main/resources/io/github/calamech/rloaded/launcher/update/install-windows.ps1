# Installs a launcher update on Windows once the launcher has quit, then starts the launcher again.
# The installer is per user, so it needs no administrator rights.
param(
    [Parameter(Mandatory)] [int] $LauncherPid,
    [Parameter(Mandatory)] [string] $Msi,
    [Parameter(Mandatory)] [string] $App,
    [string] $Installer = "msiexec.exe"
)
Wait-Process -Id $LauncherPid -ErrorAction SilentlyContinue
$install = Start-Process -FilePath $Installer -ArgumentList @("/i", "`"$Msi`"", "/passive", "/norestart") -Wait -PassThru
if ($install.ExitCode -ne 0) {
    Write-Error "install-windows: installing $Msi failed with code $($install.ExitCode)"
}
Start-Process -FilePath $App
exit $install.ExitCode
