# Windows sandbox worker

The Windows backend runs each plugin host in a fresh AppContainer and assigns
the complete child tree to a Job Object. `SANDBOXED` starts only after a live
probe succeeds; it never falls back to managed or trusted execution.

## Host requirements

- Windows 11 with AppContainer and Job Object support;
- a JDK 25 installation used once to build Mechana's private runtime;
- PowerShell 5.1 and NTFS on the Mechana runtime and sandbox directories;
- the Mechana worker JAR and any deliberately configured native plugin tools.

Install the private runtime from an elevated PowerShell prompt:

```powershell
Set-ExecutionPolicy -Scope Process Bypass
.\scripts\windows\install-mechana-java-runtime.ps1 -JdkHome 'C:\Program Files\Zulu\zulu-25'
```

This creates `C:\ProgramData\Mechana\runtime\java-25`. The installer refuses
to overwrite an existing runtime. Build a replacement separately, stop workers,
then replace the old directory as an explicit maintenance operation.

Worker Control's **Reinstall + start via SSH** action deploys the agent and worker
JARs and starts the scheduled task. The sandbox launcher is embedded in the worker
JAR as PowerShell; no separately built `mechana-windows-sandbox.exe` is required.
The private Java runtime above remains a host prerequisite. When an older profile
still contains the generic `~/.mechana/sandbox` default, Windows provisioning maps
it to `C:\ProgramData\Mechana\sandbox`; explicit absolute custom paths are preserved.

## Validate a host

```powershell
C:\ProgramData\Mechana\runtime\java-25\bin\java.exe `
  -cp mechana-plugin-runtime.jar dev.mechana.runtime.plugin.WindowsSandboxProbe
```

The probe must end with `validation=passed`. It performs actual allowed and
forbidden filesystem operations, live loopback-network denial, Java security
initialization, timeout, cancellation, crash, recovery, and resource checks.

## Enforced today

- a unique transient AppContainer identity for every launch;
- network denial when requested by policy;
- workspace output/work/log writes and read-only staged input;
- denial of writes outside the workspace and reads from the user home;
- Job Object hard memory, CPU-rate, active-process, and kill-on-close controls;
- inherited-handle allowlisting, captured output, timeout, cancellation, and
  process-tree termination;
- private Java runtime selection and per-attempt cleanup.

Java's Windows `toRealPath()` enumerates every path component. During a launch,
the transient Package SID therefore receives directory-name listing plus
traverse/read-attributes/synchronize rights on the volume root, `ProgramData`,
and Mechana/runtime ancestors. It receives read/execute access to the private
runtime. These entries are removed after the child exits. They do not grant file
content reads outside the runtime or writes outside the workspace.

`filesystem_restriction` remains false: Windows and Java runtime resources remain
visible. Mechana reports the narrower verified write restriction and home denial.

## Worker launch

```powershell
C:\ProgramData\Mechana\runtime\java-25\bin\java.exe `
  -Dmechana.execution.mode=sandboxed `
  -Dmechana.sandbox.root=C:\ProgramData\Mechana\sandbox `
  -jar mechana-worker.jar http://COORDINATOR:8787 fractal-render worker-id
```

Native runtimes are staged beneath `C:\ProgramData\Mechana\runtime` and supplied
as explicit absolute paths. The AppContainer receives temporary read/execute
access only to the declared runtime directories; those Package SID ACLs are
removed after every attempt. Native plugins use bounded multi-process Job Object
profiles rather than the single-process pure-Java profile.

## Hyperion certification (2026-08-06)

Hyperion, Windows 11 build 26200 with Mechana Java 25.0.4, passed both the
adversarial backend probe and real coordinator-to-worker jobs for every current
plugin:

| Plugin | Runtime | Job | Result |
| --- | --- | --- | --- |
| `sleep` | Java 25.0.4 | `604b8e29-77ca-4cf2-818c-6f3a5963b9dd` | Succeeded, attempt 1 |
| `fractal-render` | Java 25.0.4 | `2de64c00-7eb0-4068-bc36-ba6d94b7fde2` | Succeeded, attempt 1 |
| `ocr-tesseract` | Tesseract 5.4.0 | `3029b1b0-43c3-4f00-ba11-0876a9e51093` | Succeeded, attempt 1 |
| `video-ffmpeg` | FFmpeg/FFprobe 8.1.1 | `e22e011f-ddf5-40c5-b2aa-547764dd5222` | Succeeded, attempt 1 |
| `blender-render` | Blender 4.5.3 LTS | `5af68039-8115-4527-aa3f-9a5ea9f06478` | Succeeded, attempt 1 |

The native executable probes and job workloads both ran inside AppContainer and
Job Object enforcement. The final audit found no residual transient Package SID
ACLs or attempt workspaces. Certification applies to these exact runtime versions,
the tested CPU/headless paths, and the network-denied policy; it does not certify
GPU device isolation, arbitrary Blender add-ons, alternate codecs/languages, or
future runtime versions.
