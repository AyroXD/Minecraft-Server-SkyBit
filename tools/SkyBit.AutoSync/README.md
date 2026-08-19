# SkyBit AutoSync 2.0

Windows utility for safe automatic Git synchronization of the SkyBit repository.

## Features

- watches the selected Git repository for local changes
- waits for an idle delay before syncing
- validates the active branch before every commit
- optional build/test gate before commit
- runs `git add -A`, creates a timestamped commit and pushes to `origin`
- never uses force-push
- keeps a failed push as a local commit so it can be retried
- saves settings under `%APPDATA%\SkyBit AutoSync`
- startup and runtime error logs
- optional Start with Windows
- GitHub Actions build validation and downloadable EXE artifact

## First start

1. Open `tools\SkyBit.AutoSync`.
2. Double-click `START_HERE.bat`.
3. If the EXE is missing, the script runs `BUILD.bat` automatically.
4. Select the local SkyBit repository folder.
5. Click **Check setup**.
6. Set the expected branch (normally `main`).
7. Click **Start AutoSync**.

Recommended idle delay: `15-30` seconds.

## Optional build gate

For SkyBit Management you can use:

`powershell.exe -ExecutionPolicy Bypass -File .\management\Build.ps1`

When the build gate is enabled and the command fails, the current file changes are not committed.

## Build

Run:

`BUILD.bat`

CI/non-interactive build:

`BUILD.bat CI`

Output:

`dist\SkyBit AutoSync.exe`

The build uses the Windows .NET Framework C# compiler already available with .NET Framework 4.x. If the compiler is missing, run `DIAGNOSTICS.bat` and enable/install .NET Framework 4.8.

## Diagnostics

Run `DIAGNOSTICS.bat`.

Runtime log:

`%APPDATA%\SkyBit AutoSync\autosync.log`

Startup errors:

`%APPDATA%\SkyBit AutoSync\startup-error.log`

## Safety

AutoSync does not use `git push --force`. If the remote branch moved and Git rejects the push, resolve the branch normally and run Sync again. This prevents the app from silently overwriting remote history.
