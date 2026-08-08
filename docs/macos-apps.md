# Mechana macOS apps

Mechana provides three native macOS application bundles for local development:

- **Mechana Server.app** starts or reveals its dedicated local server dashboard window.
- **Mechana Worker Control.app** runs Worker Control as an ordinary desktop app.
- **Mechana Job Launcher.app** runs the Client Job Launcher as an ordinary desktop app.

The bundles are unsigned local-development builds. They include a Java runtime,
so Finder and Dock launches do not depend on a shell, `JAVA_HOME`, Maven, or a
Terminal window. Distribution to other users will require an Apple Developer ID,
code signing, hardened-runtime validation, and notarization.

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
is explicitly required. Rebuilding is deterministic from the shaded application JARs, three
subtly color-coded Mechana icon variants, the current Java 25 `jpackage`, macOS's
Swift compiler and WebKit framework, and a bundled runtime image.

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
on the server.

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

## Worker Control and Job Launcher lifecycle

Both Swing tools use their existing entry points and settings locations. Their
`jpackage` launchers appear as ordinary GUI applications and do not allocate a
console. Closing either main window uses its existing `EXIT_ON_CLOSE` behavior,
which terminates that application completely. Worker Control's saved host, SSH,
plugin, and deployment profiles are unchanged.

## Local verification

After installation, Finder-equivalent launches can be exercised with:

```shell
open "/Applications/Mechana Server.app"
open "/Applications/Mechana Worker Control.app"
open "/Applications/Mechana Job Launcher.app"
```

Use Activity Monitor or `launchctl print gui/$(id -u)/dev.mechana.server` to
inspect the background service. A second Server app launch must leave the same
server PID visible in the dashboard. Quit Worker Control and Job Launcher from
their application menus and confirm their processes disappear.
