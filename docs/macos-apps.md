# Mechana macOS apps

Mechana provides four native macOS application bundles for local development:

- **Mechana Server.app** starts or reveals its dedicated local server dashboard window.
- **Mechana Worker Control.app** runs Worker Control as an ordinary desktop app.
- **Mechana Job Launcher.app** runs the Client Job Launcher as an ordinary desktop app.
- **Mechana Reverb.app** runs the production convolution-reverb plugin entirely
  on one Mac, with no server, worker, network connection, or separate Java installation.

The bundles are unsigned local-development builds. They include a Java runtime,
so Finder and Dock launches do not depend on a shell, `JAVA_HOME`, Maven, or a
Terminal window. Distribution to other users will require an Apple Developer ID,
code signing, hardened-runtime validation, and notarization.

Mechana Reverb keeps its working impulse-response library in
`~/Library/Application Support/Mechana Reverb/IR Profiles`. On launch it installs
or refreshes factory profiles from the bundle.
Profiles imported with **Add…** and profiles created from sweep recordings are
copied into the same durable library and remain available across app upgrades.
Each library WAV remains untouched and receives a checksum-bound calibration
sidecar. One stereo-linked, sample-rate-aware energy gain is applied identically
in Preview and Apply; recalibration happens automatically when profile audio
changes. The gain is capped conservatively to avoid amplifying noisy captures.
IR peak safety and streaming peak protection are automatic in the standalone app,
with fixed 1 dB headroom. These implementation safeguards are not shown alongside
the creative reverb controls.
The preview transport includes an elapsed/total scrub bar. Releasing a dragged
position restarts preview there after a silent state-building pre-roll, preserving
the captured reverb rather than starting the effect from an empty convolution.

The build also writes `Mechana-Reverb-macOS-arm64.zip`, preserving the application
bundle for transfer to another Apple Silicon Mac. Because development builds are
not notarized, a recipient may need to Control-click the app and choose **Open**.
Use `packaging/macos/build-reverb-app.sh` with `JAVA_HOME` set to a Java 25 JDK of
the desired architecture to build only the standalone app. An Intel JDK produces
`Mechana-Reverb-macOS-x86_64.zip`; Rosetta permits that build on an Apple Silicon
development Mac. The JDK architecture determines the bundled runtime and native
launcher architecture.

## Build and install

Use Java 25 on macOS and run from the repository root:

```shell
packaging/macos/build-apps.sh
```

The bundles are written to `packaging/macos/target/apps`. Install them in the
standard macOS Applications folder:

```shell
packaging/macos/install-apps.sh
```

Or build and install in one operation:

```shell
packaging/macos/build-apps.sh --install
```

The installer copies the bundles to `/Applications`, so they appear under Finder's
standard **Applications** shortcut. Drag each app from there to the Dock. Set
`MECHANA_APP_DESTINATION` when invoking the installer only if a different location
is explicitly required. Rebuilding is deterministic from the shaded application JARs, the
function-specific Mechana icon variants, the current Java 25 `jpackage`, macOS's
Swift compiler and WebKit framework, and a bundled runtime image.

Mechana Reverb uses its own impulse-and-decaying-reflections icon rather than the
Job Launcher's paper-plane artwork, while retaining the shared Mechana hexagon and
color family.

## Server lifecycle

**Mechana Server.app** is a dedicated dashboard app, not the server process. It
first requests `http://127.0.0.1:8787/api/dashboard`. If a Mechana server
responds, the app opens the dashboard and starts nothing. Otherwise it
writes and bootstraps the per-user LaunchAgent
`~/Library/LaunchAgents/dev.mechana.server.plist`, waits for that same status API,
and opens its WebKit dashboard window. The LaunchAgent label provides singleton process
ownership; API readiness verifies that the listener is actually Mechana.

The dashboard window belongs to Mechana Server rather than Safari. Clicking its
Dock icon while the app is open activates the existing window instead of creating
another one. Closing the dashboard window quits only the dashboard app and leaves
the LaunchAgent server running. Clicking the Dock icon again creates one fresh
window connected to the same server.

The LaunchAgent runs the packaged server and plugin JARs with the Java runtime
inside **Mechana Server.app**. It needs no `sudo`, has `KeepAlive` enabled, and
writes standard and error output to `~/.mechana/logs/server.log` and
`~/.mechana/logs/server-error.log`. Closing the dashboard therefore has no effect
on the server. Its native-tool path includes `/opt/homebrew/bin`,
`/usr/local/bin`, and standard system directories, allowing coordinator-side
FFmpeg assembly when the app is launched without a shell.

The former desktop shortcut stored server history under
`~/Projects/mechana/.mechana/server`. The app detects and continues using that
directory when it exists; new installations use `~/.mechana/server`. Moving the
app after first launch is supported: the next launch refreshes the LaunchAgent
when the server is not running. Do not delete or replace the installed bundle
while its server is running because its bundled runtime is the executable used by
launchd.

The dashboard's **Restart server** action remains the normal restart UI. A
launchd-managed server exits and is relaunched by its agent instead of spawning a
second unmanaged JVM. **Stop server** unloads the LaunchAgent and closes the
dashboard frontend; opening the Server app starts and loads it again. The native
WebKit frontend supplies macOS confirmation dialogs for restart, stop, and purge
actions. Developer lifecycle commands are also available:

```shell
packaging/macos/server-control.sh status
packaging/macos/server-control.sh stop
packaging/macos/server-control.sh start
packaging/macos/server-control.sh restart
```

`stop` unloads the per-user agent, so `KeepAlive` does not immediately start it
again. Opening **Mechana Server.app** bootstraps it again.

## Desktop launcher lifecycle

Both Swing tools use their existing entry points and settings locations. Their
`jpackage` launchers appear as ordinary GUI applications and do not allocate a
console. Closing either main window uses its existing `EXIT_ON_CLOSE` behavior,
which terminates that application completely. Worker Control's saved host, SSH,
plugin, and deployment profiles are retained. Its app bundle also carries the
current host-agent and worker JARs needed by **Reinstall + start via SSH**, so that
operation does not depend on a repository working directory. Existing profiles
using the former repository-relative defaults migrate to the bundled copies;
explicit custom deployment paths are preserved.

In Worker Control, leaving **Plugins (blank = all)** empty starts workers with
every plugin supported and allowed by the installed host-agent build. Entering a
comma-separated list remains available when a host should deliberately advertise
only a restricted subset. Existing profiles containing the former complete
default list migrate to the simpler blank selection.

The standalone Reverb app uses the same `AudioConvolutionReverbPlugin` class as a
distributed worker. It supplies a local task context and executes one job at a
time without opening a network listener. Job history, the WAV output, plugin
result metadata, a machine-readable `job.json`, and a human-readable
`reverb-job-report.txt` are retained under the selected artifacts folder. A
successful job enables **Play Output**, which launches the generated WAV in the
Mac's default player, and **Show in Finder**, which reveals and selects that exact
WAV. A
recipient may select WAV/WAVE, M4A with AAC or Apple Lossless (including Voice
Memos), raw AAC, MP4 containing AAC audio, or AIFF dry audio. The bundled permissively
licensed pure-Java decoders, MP4 parsers, and windowed-sinc resampler convert
it to a 24-bit WAV at the selected IR's sample rate before invoking the
convolution plugin. IR inputs remain WAV-only.
Six starter IRs are included in the bundle: the five synthetic small room,
medium room, short large room, large stone church, and vocal plate profiles, plus
Scott's first measured RVB hardware profile. **Choose a bundled IR
profile…** opens that collection. The normal IR chooser accepts a user's own
compatible deconvolved IR WAV from any location. The **Create IR from Sweep** tab
uses the bundled standardized sweep and converts a raw recorded wet return into a
plugin-ready IR locally. The recorded return must preserve the sweep's leading and
trailing silence and should be captured at 100% wet. The raw recorded sweep return is
not itself an impulse response.

## Local verification

After installation, Finder-equivalent launches can be exercised with:

```shell
open "/Applications/Mechana Server.app"
open "/Applications/Mechana Worker Control.app"
open "/Applications/Mechana Job Launcher.app"
open "/Applications/Mechana Reverb.app"
```

Use Activity Monitor or `launchctl print gui/$(id -u)/dev.mechana.server` to
inspect the background service. A second Server app launch must leave the same
server PID visible in the dashboard. Quit Worker Control and Job Launcher from
their application menus and confirm their processes disappear.

## Future Windows standalone package

The standalone DSP and Swing UI are portable, but this repository currently
produces only native macOS Reverb bundles. A Windows 11 x64 package should be
built natively on Windows with Java 25 `jpackage`, bundle its own runtime and the
same sweep/profile library, use File Explorer for output reveal, and initially be
distributed as a portable ZIP. Windows packaging is planning scope only in the
current change.
