# Copyright (c) 2026 Mark Vita
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

param(
    [Parameter(Mandatory = $true)][string]$Workspace,
    [Parameter(Mandatory = $true)][string]$MechanaHome,
    [Parameter(Mandatory = $true)][string]$JavaHome,
    [Parameter(Mandatory = $true)][long]$MemoryBytes,
    [Parameter(Mandatory = $true)][int]$CpuCount,
    [Parameter(Mandatory = $true)][int]$MaxProcesses,
    [Parameter(Mandatory = $true)][string]$ChildCommandBase64,
    [Parameter(Mandatory = $true)][AllowEmptyString()][string]$RuntimePathsBase64
)

$ErrorActionPreference = 'Stop'
$Command = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($ChildCommandBase64)) -split "`0"
$RuntimePaths = @()
if ($RuntimePathsBase64) {
    $RuntimePaths = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($RuntimePathsBase64)) -split "`0"
}
if ($Command.Count -eq 0) { throw 'Windows sandbox child command is missing' }

$FilesystemAclMutex = [Threading.Mutex]::new($false, 'Mechana.AppContainer.FilesystemAcl')

function Invoke-WithFilesystemAclLock([scriptblock]$Action) {
    $lockTaken = $false
    try {
        try {
            $lockTaken = $FilesystemAclMutex.WaitOne()
        } catch [Threading.AbandonedMutexException] {
            $lockTaken = $true
        }
        & $Action
    } finally {
        if ($lockTaken) { $FilesystemAclMutex.ReleaseMutex() }
    }
}

function Grant-AppContainerAccess([string]$Path, [string]$Rights, [string]$Sid) {
    Invoke-WithFilesystemAclLock {
        $aclPath = if ($Path.EndsWith('\')) { $Path + '.' } else { $Path }
        $start = [Diagnostics.ProcessStartInfo]::new()
        $start.FileName = "$env:SystemRoot\System32\icacls.exe"
        $start.Arguments = '"' + $aclPath.Replace('"', '\"') + '" /grant "*' + $Sid + ':' + $Rights + '" /C'
        $start.UseShellExecute = $false
        $start.CreateNoWindow = $true
        $start.RedirectStandardOutput = $true
        $start.RedirectStandardError = $true
        $process = [Diagnostics.Process]::Start($start)
        $stdout = $process.StandardOutput.ReadToEnd()
        $stderr = $process.StandardError.ReadToEnd()
        $process.WaitForExit()
        if ($process.ExitCode -ne 0) { throw "icacls failed for $Path with exit $($process.ExitCode): $stdout $stderr" }
    }
}

function Remove-AppContainerAccess([string]$Path, [string]$Sid) {
    Invoke-WithFilesystemAclLock {
        $aclPath = if ($Path.EndsWith('\')) { $Path + '.' } else { $Path }
        $start = [Diagnostics.ProcessStartInfo]::new()
        $start.FileName = "$env:SystemRoot\System32\icacls.exe"
        $start.Arguments = '"' + $aclPath.Replace('"', '\"') + '" /remove "*' + $Sid + '" /C /Q'
        $start.UseShellExecute = $false
        $start.CreateNoWindow = $true
        $process = [Diagnostics.Process]::Start($start)
        $process.WaitForExit()
    }
}

function Reset-WorkspaceAccess([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path)) { return }
    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = "$env:SystemRoot\System32\icacls.exe"
    $start.Arguments = '"' + $Path.Replace('"', '\"') + '" /reset /T /C /Q'
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    $process = [Diagnostics.Process]::Start($start)
    $stdout = $process.StandardOutput.ReadToEnd()
    $stderr = $process.StandardError.ReadToEnd()
    $process.WaitForExit()
    if ($process.ExitCode -ne 0) {
        throw "icacls workspace reset failed for $Path with exit $($process.ExitCode): $stdout $stderr"
    }
}

$source = @'
using System;
using System.ComponentModel;
using System.Runtime.InteropServices;
using System.Text;
using System.IO;
using System.Threading;
using Microsoft.Win32.SafeHandles;

public static class MechanaWindowsSandbox {
    const uint EXTENDED_STARTUPINFO_PRESENT = 0x00080000;
    const uint CREATE_SUSPENDED = 0x00000004;
    const uint STARTF_USESTDHANDLES = 0x00000100;
    const uint PROC_THREAD_ATTRIBUTE_SECURITY_CAPABILITIES = 0x00020009;
    const uint PROC_THREAD_ATTRIBUTE_HANDLE_LIST = 0x00020002;
    const uint JOB_OBJECT_LIMIT_ACTIVE_PROCESS = 0x00000008;
    const uint JOB_OBJECT_LIMIT_JOB_MEMORY = 0x00000200;
    const uint JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x00002000;
    const uint JOB_OBJECT_CPU_RATE_CONTROL_ENABLE = 0x1;
    const uint JOB_OBJECT_CPU_RATE_CONTROL_HARD_CAP = 0x4;
    const int JobObjectExtendedLimitInformation = 9;
    const int JobObjectCpuRateControlInformation = 15;
    const uint INFINITE = 0xffffffff;
    const uint HANDLE_FLAG_INHERIT = 0x00000001;
    const uint GENERIC_READ = 0x80000000;
    const uint DACL_SECURITY_INFORMATION = 0x00000004;
    const int SE_WINDOW_OBJECT = 7;
    const int SET_ACCESS = 2;
    const int REVOKE_ACCESS = 4;
    const int TRUSTEE_IS_SID = 0;
    const int TRUSTEE_IS_USER = 1;

    [StructLayout(LayoutKind.Sequential)] struct SECURITY_CAPABILITIES {
        public IntPtr AppContainerSid;
        public IntPtr Capabilities;
        public uint CapabilityCount;
        public uint Reserved;
    }
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)] struct STARTUPINFOEX {
        public STARTUPINFO StartupInfo;
        public IntPtr lpAttributeList;
    }
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)] struct STARTUPINFO {
        public uint cb; public string lpReserved; public string lpDesktop; public string lpTitle;
        public uint dwX; public uint dwY; public uint dwXSize; public uint dwYSize;
        public uint dwXCountChars; public uint dwYCountChars; public uint dwFillAttribute;
        public uint dwFlags; public ushort wShowWindow; public ushort cbReserved2;
        public IntPtr lpReserved2; public IntPtr hStdInput; public IntPtr hStdOutput; public IntPtr hStdError;
    }
    [StructLayout(LayoutKind.Sequential)] struct PROCESS_INFORMATION {
        public IntPtr hProcess; public IntPtr hThread; public uint dwProcessId; public uint dwThreadId;
    }
    [StructLayout(LayoutKind.Sequential)] struct SECURITY_ATTRIBUTES {
        public uint nLength; public IntPtr lpSecurityDescriptor; public bool bInheritHandle;
    }
    [StructLayout(LayoutKind.Sequential)] struct TRUSTEE {
        public IntPtr pMultipleTrustee; public int MultipleTrusteeOperation; public int TrusteeForm;
        public int TrusteeType; public IntPtr ptstrName;
    }
    [StructLayout(LayoutKind.Sequential)] struct EXPLICIT_ACCESS {
        public uint grfAccessPermissions; public int grfAccessMode; public uint grfInheritance; public TRUSTEE Trustee;
    }
    [StructLayout(LayoutKind.Sequential)] struct IO_COUNTERS {
        public ulong ReadOperationCount, WriteOperationCount, OtherOperationCount;
        public ulong ReadTransferCount, WriteTransferCount, OtherTransferCount;
    }
    [StructLayout(LayoutKind.Sequential)] struct JOBOBJECT_BASIC_LIMIT_INFORMATION {
        public long PerProcessUserTimeLimit, PerJobUserTimeLimit;
        public uint LimitFlags;
        public UIntPtr MinimumWorkingSetSize, MaximumWorkingSetSize;
        public uint ActiveProcessLimit;
        public UIntPtr Affinity;
        public uint PriorityClass, SchedulingClass;
    }
    [StructLayout(LayoutKind.Sequential)] struct JOBOBJECT_EXTENDED_LIMIT_INFORMATION {
        public JOBOBJECT_BASIC_LIMIT_INFORMATION BasicLimitInformation;
        public IO_COUNTERS IoInfo;
        public UIntPtr ProcessMemoryLimit, JobMemoryLimit, PeakProcessMemoryUsed, PeakJobMemoryUsed;
    }
    [StructLayout(LayoutKind.Sequential)] struct JOBOBJECT_CPU_RATE_CONTROL_INFORMATION {
        public uint ControlFlags, CpuRate;
    }

    [DllImport("userenv.dll", CharSet=CharSet.Unicode)] static extern int DeriveAppContainerSidFromAppContainerName(string name, out IntPtr sid);
    [DllImport("userenv.dll", CharSet=CharSet.Unicode)] static extern int CreateAppContainerProfile(string name, string displayName, string description, IntPtr capabilities, uint capabilityCount, out IntPtr sid);
    [DllImport("userenv.dll", CharSet=CharSet.Unicode)] static extern int DeleteAppContainerProfile(string name);
    [DllImport("advapi32.dll", EntryPoint="ConvertSidToStringSidW", ExactSpelling=true, SetLastError=true)] static extern bool ConvertSidToStringSid(IntPtr sid, out IntPtr text);
    [DllImport("kernel32.dll")] static extern IntPtr LocalFree(IntPtr value);
    [DllImport("kernel32.dll", SetLastError=true)] static extern bool InitializeProcThreadAttributeList(IntPtr list, int count, int flags, ref IntPtr size);
    [DllImport("kernel32.dll", SetLastError=true)] static extern bool UpdateProcThreadAttribute(IntPtr list, uint flags, UIntPtr attribute, IntPtr value, IntPtr size, IntPtr previous, IntPtr returned);
    [DllImport("kernel32.dll")] static extern void DeleteProcThreadAttributeList(IntPtr list);
    [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)] static extern bool CreateProcess(string application, StringBuilder command, IntPtr processAttributes, IntPtr threadAttributes, bool inheritHandles, uint flags, IntPtr environment, string currentDirectory, ref STARTUPINFOEX startup, out PROCESS_INFORMATION process);
    [DllImport("kernel32.dll", SetLastError=true)] static extern IntPtr CreateJobObject(IntPtr attributes, string name);
    [DllImport("kernel32.dll", SetLastError=true)] static extern bool SetInformationJobObject(IntPtr job, int infoClass, IntPtr info, uint length);
    [DllImport("kernel32.dll", SetLastError=true)] static extern bool AssignProcessToJobObject(IntPtr job, IntPtr process);
    [DllImport("kernel32.dll", SetLastError=true)] static extern uint ResumeThread(IntPtr thread);
    [DllImport("kernel32.dll", SetLastError=true)] static extern uint WaitForSingleObject(IntPtr handle, uint milliseconds);
    [DllImport("kernel32.dll", SetLastError=true)] static extern bool GetExitCodeProcess(IntPtr process, out uint exitCode);
    [DllImport("kernel32.dll", SetLastError=true)] static extern bool CreatePipe(out IntPtr read, out IntPtr write, ref SECURITY_ATTRIBUTES attributes, uint size);
    [DllImport("kernel32.dll", SetLastError=true)] static extern bool SetHandleInformation(IntPtr handle, uint mask, uint flags);
    [DllImport("user32.dll")] static extern IntPtr GetProcessWindowStation();
    [DllImport("user32.dll")] static extern IntPtr GetThreadDesktop(uint threadId);
    [DllImport("kernel32.dll")] static extern uint GetCurrentThreadId();
    [DllImport("advapi32.dll", SetLastError=true)] static extern uint GetSecurityInfo(IntPtr handle, int objectType, uint securityInfo, out IntPtr owner, out IntPtr group, out IntPtr dacl, out IntPtr sacl, out IntPtr securityDescriptor);
    [DllImport("advapi32.dll", CharSet=CharSet.Unicode, SetLastError=true)] static extern uint SetEntriesInAcl(uint count, ref EXPLICIT_ACCESS entries, IntPtr oldAcl, out IntPtr newAcl);
    [DllImport("advapi32.dll", SetLastError=true)] static extern uint SetSecurityInfo(IntPtr handle, int objectType, uint securityInfo, IntPtr owner, IntPtr group, IntPtr dacl, IntPtr sacl);
    [DllImport("kernel32.dll")] static extern bool CloseHandle(IntPtr handle);

    static void Check(bool ok, string operation) { if (!ok) { int code=Marshal.GetLastWin32Error(); throw new Win32Exception(code, operation + " failed with Win32 error " + code); } }
    static void CheckStatus(uint status, string operation) { if (status != 0) throw new Win32Exception((int)status, operation + " failed with Win32 error " + status); }
    static readonly Mutex UserObjectAclMutex = new Mutex(false, "Mechana.AppContainer.UserObjectAcl");
    static void ChangeUserObjectAccess(IntPtr handle, IntPtr sid, int mode) {
        UserObjectAclMutex.WaitOne();
        IntPtr owner=IntPtr.Zero, group=IntPtr.Zero, oldAcl=IntPtr.Zero, sacl=IntPtr.Zero, descriptor=IntPtr.Zero, newAcl=IntPtr.Zero;
        try {
            CheckStatus(GetSecurityInfo(handle, SE_WINDOW_OBJECT, DACL_SECURITY_INFORMATION, out owner, out group, out oldAcl, out sacl, out descriptor), "GetSecurityInfo(user object)");
            var access = new EXPLICIT_ACCESS {
                grfAccessPermissions=mode == REVOKE_ACCESS ? 0 : GENERIC_READ,
                grfAccessMode=mode,
                grfInheritance=0,
                Trustee=new TRUSTEE { pMultipleTrustee=IntPtr.Zero, MultipleTrusteeOperation=0, TrusteeForm=TRUSTEE_IS_SID, TrusteeType=TRUSTEE_IS_USER, ptstrName=sid }
            };
            CheckStatus(SetEntriesInAcl(1, ref access, oldAcl, out newAcl), "SetEntriesInAcl(user object)");
            CheckStatus(SetSecurityInfo(handle, SE_WINDOW_OBJECT, DACL_SECURITY_INFORMATION, IntPtr.Zero, IntPtr.Zero, newAcl, IntPtr.Zero), "SetSecurityInfo(user object)");
        } finally {
            if (newAcl != IntPtr.Zero) LocalFree(newAcl); if (descriptor != IntPtr.Zero) LocalFree(descriptor);
            UserObjectAclMutex.ReleaseMutex();
        }
    }
    static string Quote(string value) {
        if (value.Length > 0 && value.IndexOfAny(new[]{' ', '\t', '"'}) < 0) return value;
        var result = new StringBuilder("\""); int slashes = 0;
        foreach (char c in value) {
            if (c == '\\') { slashes++; continue; }
            if (c == '"') { result.Append('\\', slashes * 2 + 1).Append(c); slashes = 0; continue; }
            result.Append('\\', slashes).Append(c); slashes = 0;
        }
        result.Append('\\', slashes * 2).Append('"'); return result.ToString();
    }
    public static string SidString(string name) {
        IntPtr sid, text;
        int hr = DeriveAppContainerSidFromAppContainerName(name, out sid);
        if (hr != 0) Marshal.ThrowExceptionForHR(hr);
        try { Check(ConvertSidToStringSid(sid, out text), "ConvertSidToStringSid"); try { return Marshal.PtrToStringUni(text); } finally { LocalFree(text); } }
        finally { LocalFree(sid); }
    }
    public static void CreateProfile(string name) {
        IntPtr sid; int hr = CreateAppContainerProfile(name, name, "Temporary Mechana plugin sandbox", IntPtr.Zero, 0, out sid);
        if (hr != 0) Marshal.ThrowExceptionForHR(hr);
        if (sid != IntPtr.Zero) LocalFree(sid);
    }
    public static void DeleteProfile(string name) {
        int hr = DeleteAppContainerProfile(name); if (hr != 0) Marshal.ThrowExceptionForHR(hr);
    }
    public static int Launch(string profile, string workspace, long memoryBytes, int cpuCount, int maxProcesses, string[] args) {
        IntPtr sid = IntPtr.Zero, attributes = IntPtr.Zero, capabilities = IntPtr.Zero, handleList = IntPtr.Zero, job = IntPtr.Zero, limits = IntPtr.Zero, cpu = IntPtr.Zero;
        IntPtr inputRead = IntPtr.Zero, inputWrite = IntPtr.Zero, outputRead = IntPtr.Zero, outputWrite = IntPtr.Zero, errorRead = IntPtr.Zero, errorWrite = IntPtr.Zero;
        IntPtr windowStation = IntPtr.Zero, desktop = IntPtr.Zero; bool windowStationGranted = false, desktopGranted = false;
        PROCESS_INFORMATION pi = new PROCESS_INFORMATION();
        try {
            int hr = DeriveAppContainerSidFromAppContainerName(profile, out sid); if (hr != 0) Marshal.ThrowExceptionForHR(hr);
            IntPtr bytes = IntPtr.Zero; InitializeProcThreadAttributeList(IntPtr.Zero, 2, 0, ref bytes);
            attributes = Marshal.AllocHGlobal(bytes); Check(InitializeProcThreadAttributeList(attributes, 2, 0, ref bytes), "InitializeProcThreadAttributeList");
            var sc = new SECURITY_CAPABILITIES { AppContainerSid=sid, Capabilities=IntPtr.Zero, CapabilityCount=0, Reserved=0 };
            capabilities = Marshal.AllocHGlobal(Marshal.SizeOf(sc)); Marshal.StructureToPtr(sc, capabilities, false);
            Check(UpdateProcThreadAttribute(attributes, 0, (UIntPtr)PROC_THREAD_ATTRIBUTE_SECURITY_CAPABILITIES, capabilities, (IntPtr)Marshal.SizeOf(sc), IntPtr.Zero, IntPtr.Zero), "UpdateProcThreadAttribute");
            job = CreateJobObject(IntPtr.Zero, null); Check(job != IntPtr.Zero, "CreateJobObject");
            var limit = new JOBOBJECT_EXTENDED_LIMIT_INFORMATION();
            limit.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE | JOB_OBJECT_LIMIT_JOB_MEMORY | JOB_OBJECT_LIMIT_ACTIVE_PROCESS;
            limit.BasicLimitInformation.ActiveProcessLimit = (uint)maxProcesses; limit.JobMemoryLimit = (UIntPtr)(ulong)memoryBytes;
            limits = Marshal.AllocHGlobal(Marshal.SizeOf(limit)); Marshal.StructureToPtr(limit, limits, false);
            Check(SetInformationJobObject(job, JobObjectExtendedLimitInformation, limits, (uint)Marshal.SizeOf(limit)), "SetInformationJobObject(limits)");
            uint processors = (uint)Math.Max(1, Environment.ProcessorCount); uint rate = Math.Max(1u, Math.Min(10000u, (uint)(10000L * cpuCount / processors)));
            var cpuLimit = new JOBOBJECT_CPU_RATE_CONTROL_INFORMATION { ControlFlags=JOB_OBJECT_CPU_RATE_CONTROL_ENABLE | JOB_OBJECT_CPU_RATE_CONTROL_HARD_CAP, CpuRate=rate };
            cpu = Marshal.AllocHGlobal(Marshal.SizeOf(cpuLimit)); Marshal.StructureToPtr(cpuLimit, cpu, false);
            Check(SetInformationJobObject(job, JobObjectCpuRateControlInformation, cpu, (uint)Marshal.SizeOf(cpuLimit)), "SetInformationJobObject(cpu)");
            var sa = new SECURITY_ATTRIBUTES { nLength=(uint)Marshal.SizeOf(typeof(SECURITY_ATTRIBUTES)), bInheritHandle=true };
            Check(CreatePipe(out inputRead, out inputWrite, ref sa, 0), "CreatePipe(stdin)");
            Check(CreatePipe(out outputRead, out outputWrite, ref sa, 0), "CreatePipe(stdout)");
            Check(CreatePipe(out errorRead, out errorWrite, ref sa, 0), "CreatePipe(stderr)");
            Check(SetHandleInformation(inputWrite, HANDLE_FLAG_INHERIT, 0), "SetHandleInformation(stdin)");
            Check(SetHandleInformation(outputRead, HANDLE_FLAG_INHERIT, 0), "SetHandleInformation(stdout)");
            Check(SetHandleInformation(errorRead, HANDLE_FLAG_INHERIT, 0), "SetHandleInformation(stderr)");
            handleList = Marshal.AllocHGlobal(IntPtr.Size * 3);
            Marshal.WriteIntPtr(handleList, 0, inputRead); Marshal.WriteIntPtr(handleList, IntPtr.Size, outputWrite); Marshal.WriteIntPtr(handleList, IntPtr.Size * 2, errorWrite);
            Check(UpdateProcThreadAttribute(attributes, 0, (UIntPtr)PROC_THREAD_ATTRIBUTE_HANDLE_LIST, handleList, (IntPtr)(IntPtr.Size * 3), IntPtr.Zero, IntPtr.Zero), "UpdateProcThreadAttribute(handles)");
            windowStation = GetProcessWindowStation(); Check(windowStation != IntPtr.Zero, "GetProcessWindowStation");
            desktop = GetThreadDesktop(GetCurrentThreadId()); Check(desktop != IntPtr.Zero, "GetThreadDesktop");
            ChangeUserObjectAccess(windowStation, sid, SET_ACCESS); windowStationGranted=true;
            ChangeUserObjectAccess(desktop, sid, SET_ACCESS); desktopGranted=true;
            var sx = new STARTUPINFOEX(); sx.StartupInfo.cb = (uint)Marshal.SizeOf(sx); sx.StartupInfo.dwFlags = STARTF_USESTDHANDLES;
            sx.StartupInfo.hStdInput=inputRead; sx.StartupInfo.hStdOutput=outputWrite; sx.StartupInfo.hStdError=errorWrite; sx.lpAttributeList=attributes;
            string line = string.Join(" ", Array.ConvertAll(args, Quote));
            Check(CreateProcess(args[0], new StringBuilder(line), IntPtr.Zero, IntPtr.Zero, true, EXTENDED_STARTUPINFO_PRESENT | CREATE_SUSPENDED, IntPtr.Zero, workspace + "\\work", ref sx, out pi), "CreateProcess(AppContainer)");
            CloseHandle(inputRead); inputRead=IntPtr.Zero; CloseHandle(outputWrite); outputWrite=IntPtr.Zero; CloseHandle(errorWrite); errorWrite=IntPtr.Zero;
            var stdin = new FileStream(new SafeFileHandle(inputWrite, true), FileAccess.Write); inputWrite=IntPtr.Zero;
            var stdout = new FileStream(new SafeFileHandle(outputRead, true), FileAccess.Read); outputRead=IntPtr.Zero;
            var stderr = new FileStream(new SafeFileHandle(errorRead, true), FileAccess.Read); errorRead=IntPtr.Zero;
            var inputPump = new Thread(() => { try { Console.OpenStandardInput().CopyTo(stdin); } catch {} finally { stdin.Dispose(); } }); inputPump.IsBackground=true;
            var outputPump = new Thread(() => { try { stdout.CopyTo(Console.OpenStandardOutput()); } catch {} finally { stdout.Dispose(); } }); outputPump.IsBackground=true;
            var errorPump = new Thread(() => { try { stderr.CopyTo(Console.OpenStandardError()); } catch {} finally { stderr.Dispose(); } }); errorPump.IsBackground=true;
            inputPump.Start(); outputPump.Start(); errorPump.Start();
            Check(AssignProcessToJobObject(job, pi.hProcess), "AssignProcessToJobObject");
            if (ResumeThread(pi.hThread) == 0xffffffff) throw new Win32Exception(Marshal.GetLastWin32Error(), "ResumeThread");
            WaitForSingleObject(pi.hProcess, INFINITE); outputPump.Join(2000); errorPump.Join(2000); uint exitCode; Check(GetExitCodeProcess(pi.hProcess, out exitCode), "GetExitCodeProcess"); return unchecked((int)exitCode);
        } finally {
            if (pi.hThread != IntPtr.Zero) CloseHandle(pi.hThread); if (pi.hProcess != IntPtr.Zero) CloseHandle(pi.hProcess);
            if (job != IntPtr.Zero) CloseHandle(job); if (attributes != IntPtr.Zero) DeleteProcThreadAttributeList(attributes);
            if (inputRead != IntPtr.Zero) CloseHandle(inputRead); if (inputWrite != IntPtr.Zero) CloseHandle(inputWrite);
            if (outputRead != IntPtr.Zero) CloseHandle(outputRead); if (outputWrite != IntPtr.Zero) CloseHandle(outputWrite);
            if (errorRead != IntPtr.Zero) CloseHandle(errorRead); if (errorWrite != IntPtr.Zero) CloseHandle(errorWrite);
            try { if (desktopGranted) ChangeUserObjectAccess(desktop, sid, REVOKE_ACCESS); } catch {}
            try { if (windowStationGranted) ChangeUserObjectAccess(windowStation, sid, REVOKE_ACCESS); } catch {}
            if (attributes != IntPtr.Zero) Marshal.FreeHGlobal(attributes); if (capabilities != IntPtr.Zero) Marshal.FreeHGlobal(capabilities);
            if (handleList != IntPtr.Zero) Marshal.FreeHGlobal(handleList);
            if (limits != IntPtr.Zero) Marshal.FreeHGlobal(limits); if (cpu != IntPtr.Zero) Marshal.FreeHGlobal(cpu); if (sid != IntPtr.Zero) LocalFree(sid);
        }
    }
}
'@

Add-Type -TypeDefinition $source -Language CSharp
$profile = 'Mechana.PluginSandbox.' + [Guid]::NewGuid().ToString('N')
[MechanaWindowsSandbox]::CreateProfile($profile)
$temporaryGrants = [Collections.Generic.List[string]]::new()
try {
    $sid = [MechanaWindowsSandbox]::SidString($profile)
    $ancestor = Split-Path -Parent $Workspace
    while ($ancestor -and $ancestor.StartsWith($MechanaHome, [StringComparison]::OrdinalIgnoreCase)) {
        Grant-AppContainerAccess $ancestor '(S,RD,X,RA)' $sid
        $temporaryGrants.Add($ancestor)
        if ($ancestor.Equals($MechanaHome, [StringComparison]::OrdinalIgnoreCase)) { break }
        $ancestor = Split-Path -Parent $ancestor
    }
    $javaAncestor = Split-Path -Parent $JavaHome
    while ($javaAncestor -and $javaAncestor.StartsWith($MechanaHome, [StringComparison]::OrdinalIgnoreCase)) {
        if (-not $temporaryGrants.Contains($javaAncestor)) {
            Grant-AppContainerAccess $javaAncestor '(S,RD,X,RA)' $sid
            $temporaryGrants.Add($javaAncestor)
        }
        if ($javaAncestor.Equals($MechanaHome, [StringComparison]::OrdinalIgnoreCase)) { break }
        $javaAncestor = Split-Path -Parent $javaAncestor
    }
    $programData = Split-Path -Parent $MechanaHome
    Grant-AppContainerAccess $programData '(S,RD,X,RA)' $sid
    $temporaryGrants.Add($programData)
    $volumeRoot = [IO.Path]::GetPathRoot($MechanaHome)
    Grant-AppContainerAccess $volumeRoot '(S,RD,X,RA)' $sid
    $temporaryGrants.Add($volumeRoot)
    Grant-AppContainerAccess $JavaHome '(OI)(CI)(RX)' $sid
    $temporaryGrants.Add($JavaHome)
	foreach ($runtimePath in $RuntimePaths) {
		if (-not (Test-Path -LiteralPath $runtimePath)) { throw "Configured sandbox runtime path is missing: $runtimePath" }
		Grant-AppContainerAccess $runtimePath '(OI)(CI)(RX)' $sid
		$temporaryGrants.Add($runtimePath)
	}
    foreach ($javaSecurityPath in @(
        (Join-Path $JavaHome 'conf'),
        (Join-Path $JavaHome 'conf\security'),
        (Join-Path $JavaHome 'conf\security\java.security')
    )) {
        Grant-AppContainerAccess $javaSecurityPath '(RX)' $sid
        $temporaryGrants.Add($javaSecurityPath)
    }
    foreach ($grant in @(
        @($Workspace, '(RX)'),
        @((Join-Path $Workspace 'input'), '(OI)(CI)(RX)'),
        @((Join-Path $Workspace 'work'), '(OI)(CI)(M)'),
        @((Join-Path $Workspace 'output'), '(OI)(CI)(M)'),
        @((Join-Path $Workspace 'logs'), '(OI)(CI)(M)')
    )) {
        Grant-AppContainerAccess $grant[0] $grant[1] $sid
    }
    exit [MechanaWindowsSandbox]::Launch($profile, $Workspace, $MemoryBytes, $CpuCount, $MaxProcesses, $Command)
} finally {
    Reset-WorkspaceAccess $Workspace
    if ($sid) {
        foreach ($path in $temporaryGrants) { Remove-AppContainerAccess $path $sid }
    }
    [MechanaWindowsSandbox]::DeleteProfile($profile)
}
