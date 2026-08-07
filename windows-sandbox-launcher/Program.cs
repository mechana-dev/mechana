using System.ComponentModel;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Security.AccessControl;
using System.Security.Principal;

internal static partial class Program
{
    private const string ProfileName = "Mechana.PluginSandbox";
    private const int ErrorAlreadyExists = unchecked((int)0x800700B7);
    private const uint CreateSuspended = 0x00000004;
    private const uint ExtendedStartupInfoPresent = 0x00080000;
    private const uint StartfUseStdHandles = 0x00000100;
    private const nuint ProcThreadAttributeSecurityCapabilities = 0x00020009;
    private const uint JobObjectExtendedLimitInformationClass = 9;
    private const uint JobObjectCpuRateControlInformationClass = 15;
    private const uint JobObjectLimitActiveProcess = 0x00000008;
    private const uint JobObjectLimitProcessMemory = 0x00000100;
    private const uint JobObjectLimitKillOnJobClose = 0x00002000;
    private const uint JobObjectCpuRateControlEnable = 0x1;
    private const uint JobObjectCpuRateControlHardCap = 0x4;
    private const uint Infinite = 0xFFFFFFFF;

    public static int Main(string[] args)
    {
        if (!OperatingSystem.IsWindows())
        {
            Console.Error.WriteLine("Windows sandbox launcher requires Windows");
            return 125;
        }
        try
        {
            if (args.Length == 1 && args[0] == "--probe")
                return Probe();
            Options options = Options.Parse(args);
            return Launch(options);
        }
        catch (Exception failure)
        {
            string detail = failure is Win32Exception windows
                ? $"{failure.Message} (Win32 error {windows.NativeErrorCode})"
                : failure.Message;
            Console.Error.WriteLine($"Windows sandbox launcher failed: {detail}");
            return 125;
        }
    }

    private static int Probe()
    {
        IntPtr sid = EnsureProfile();
        Native.FreeSid(sid);
        Console.WriteLine("appcontainer=true job-object=true network-denied=true");
        return 0;
    }

    private static int Launch(Options options)
    {
        IntPtr appContainerSid = EnsureProfile();
        try
        {
            SecurityIdentifier identity = new(appContainerSid);
            GrantWorkspace(options.Workspace, identity);
            GrantReadPaths(options.ReadPaths, identity);
            foreach (string path in options.ReadTrees)
                GrantReadTree(path, identity);
            using SafeKernelHandle job = CreateJob(options);
            ProcessInformation process = CreateAppContainerProcess(options, appContainerSid);
            try
            {
                if (!Native.AssignProcessToJobObject(job.DangerousGetHandle(), process.Process))
                    throw Win32("AssignProcessToJobObject");
                Native.ResumeThread(process.Thread);
                Native.WaitForSingleObject(process.Process, Infinite);
                if (!Native.GetExitCodeProcess(process.Process, out uint exitCode))
                    throw Win32("GetExitCodeProcess");
                return unchecked((int)exitCode);
            }
            finally
            {
                Native.CloseHandle(process.Thread);
                Native.CloseHandle(process.Process);
            }
        }
        finally
        {
            Native.FreeSid(appContainerSid);
        }
    }

    private static IntPtr EnsureProfile()
    {
        int result = Native.CreateAppContainerProfile(ProfileName, "Mechana plugin sandbox",
            "Isolated Mechana plugin process", IntPtr.Zero, 0, out IntPtr sid);
        if (result == 0)
            return sid;
        if (result != ErrorAlreadyExists)
            Marshal.ThrowExceptionForHR(result);
        result = Native.DeriveAppContainerSidFromAppContainerName(ProfileName, out sid);
        if (result != 0)
            Marshal.ThrowExceptionForHR(result);
        return sid;
    }

    private static void GrantWorkspace(string workspace, SecurityIdentifier identity)
    {
        // Permit traversal of the workspace root while keeping its contents inaccessible
        // unless a more specific rule below grants access.
        GrantDirectory(workspace, identity, FileSystemRights.ReadAndExecute | FileSystemRights.Synchronize,
            InheritanceFlags.None);
        GrantDirectory(Path.Combine(workspace, "input"), identity,
            FileSystemRights.ReadAndExecute | FileSystemRights.Synchronize);
        GrantDirectory(Path.Combine(workspace, "work"), identity, FileSystemRights.Modify | FileSystemRights.Synchronize);
        GrantDirectory(Path.Combine(workspace, "output"), identity, FileSystemRights.Modify | FileSystemRights.Synchronize);
        GrantDirectory(Path.Combine(workspace, "logs"), identity, FileSystemRights.Modify | FileSystemRights.Synchronize);
    }

    private static void GrantReadPaths(IEnumerable<string> paths, SecurityIdentifier identity)
    {
        foreach (string path in paths.Where(path => !string.IsNullOrWhiteSpace(path)).Distinct(StringComparer.OrdinalIgnoreCase))
        {
            if (Directory.Exists(path))
                GrantDirectory(path, identity, FileSystemRights.ReadAndExecute | FileSystemRights.Synchronize);
            else if (File.Exists(path))
                GrantFile(path, identity);
        }
    }

    private static void GrantReadTree(string path, SecurityIdentifier identity)
    {
        FileSystemRights rights = FileSystemRights.ReadAndExecute | FileSystemRights.Synchronize;
        GrantDirectory(path, identity, rights);
        foreach (string directory in Directory.EnumerateDirectories(path, "*", SearchOption.AllDirectories))
            GrantDirectory(directory, identity, rights);
        foreach (string file in Directory.EnumerateFiles(path, "*", SearchOption.AllDirectories))
            GrantFile(file, identity);
    }

    private static void GrantFile(string path, SecurityIdentifier identity)
    {
        FileSecurity security = FileSystemAclExtensions.GetAccessControl(new FileInfo(path));
        security.AddAccessRule(new FileSystemAccessRule(identity,
            FileSystemRights.ReadAndExecute | FileSystemRights.Synchronize, AccessControlType.Allow));
        FileSystemAclExtensions.SetAccessControl(new FileInfo(path), security);
    }

    private static void GrantDirectory(string path, SecurityIdentifier identity, FileSystemRights rights,
        InheritanceFlags inheritance = InheritanceFlags.ContainerInherit | InheritanceFlags.ObjectInherit)
    {
        DirectoryInfo directory = new(path);
        DirectorySecurity security = FileSystemAclExtensions.GetAccessControl(directory);
        security.AddAccessRule(new FileSystemAccessRule(identity, rights,
            inheritance,
            PropagationFlags.None, AccessControlType.Allow));
        FileSystemAclExtensions.SetAccessControl(directory, security);
    }

    private static SafeKernelHandle CreateJob(Options options)
    {
        SafeKernelHandle job = new(Native.CreateJobObject(IntPtr.Zero, null));
        if (job.IsInvalid)
            throw Win32("CreateJobObject");
        JobObjectExtendedLimitInformation limits = new();
        limits.BasicLimitInformation.LimitFlags = JobObjectLimitKillOnJobClose | JobObjectLimitActiveProcess
            | JobObjectLimitProcessMemory;
        limits.BasicLimitInformation.ActiveProcessLimit = checked((uint)options.MaxProcesses);
        limits.ProcessMemoryLimit = checked((nuint)options.MemoryBytes);
        SetJob(job, JobObjectExtendedLimitInformationClass, limits);
        uint cpuRate = checked((uint)Math.Clamp(options.CpuCount * 10000L / options.HostCpuCount, 1, 10000));
        JobObjectCpuRateControlInformation cpu = new()
        {
            ControlFlags = JobObjectCpuRateControlEnable | JobObjectCpuRateControlHardCap,
            CpuRate = cpuRate
        };
        SetJob(job, JobObjectCpuRateControlInformationClass, cpu);
        return job;
    }

    private static void SetJob<T>(SafeKernelHandle job, uint informationClass, T value) where T : struct
    {
        int size = Marshal.SizeOf<T>();
        IntPtr memory = Marshal.AllocHGlobal(size);
        try
        {
            Marshal.StructureToPtr(value, memory, false);
            if (!Native.SetInformationJobObject(job.DangerousGetHandle(), informationClass, memory, (uint)size))
                throw Win32("SetInformationJobObject");
        }
        finally
        {
            Marshal.FreeHGlobal(memory);
        }
    }

    private static ProcessInformation CreateAppContainerProcess(Options options, IntPtr sid)
    {
        nuint listSize = 0;
        Native.InitializeProcThreadAttributeList(IntPtr.Zero, 1, 0, ref listSize);
        IntPtr attributes = Marshal.AllocHGlobal(checked((int)listSize));
        IntPtr capabilitiesMemory = IntPtr.Zero;
        try
        {
            if (!Native.InitializeProcThreadAttributeList(attributes, 1, 0, ref listSize))
                throw Win32("InitializeProcThreadAttributeList");
            SecurityCapabilities capabilities = new() { AppContainerSid = sid };
            capabilitiesMemory = Marshal.AllocHGlobal(Marshal.SizeOf<SecurityCapabilities>());
            Marshal.StructureToPtr(capabilities, capabilitiesMemory, false);
            if (!Native.UpdateProcThreadAttribute(attributes, 0, ProcThreadAttributeSecurityCapabilities,
                    capabilitiesMemory, (nuint)Marshal.SizeOf<SecurityCapabilities>(), IntPtr.Zero, IntPtr.Zero))
                throw Win32("UpdateProcThreadAttribute");
            StartupInfoEx startup = new();
            startup.StartupInfo.Cb = checked((uint)Marshal.SizeOf<StartupInfoEx>());
            startup.StartupInfo.Flags = StartfUseStdHandles;
            startup.StartupInfo.StdInput = Native.GetStdHandle(-10);
            startup.StartupInfo.StdOutput = Native.GetStdHandle(-11);
            startup.StartupInfo.StdError = Native.GetStdHandle(-12);
            startup.AttributeList = attributes;
            string commandLine = string.Join(" ", options.Command.Select(Quote));
            string work = Path.Combine(options.Workspace, "work");
            if (!Native.CreateProcess(null, commandLine, IntPtr.Zero, IntPtr.Zero, true,
                    CreateSuspended | ExtendedStartupInfoPresent, IntPtr.Zero, work,
                    ref startup, out ProcessInformation process))
                throw Win32("CreateProcessW command=" + commandLine);
            return process;
        }
        finally
        {
            if (attributes != IntPtr.Zero)
                Native.DeleteProcThreadAttributeList(attributes);
            if (capabilitiesMemory != IntPtr.Zero)
                Marshal.FreeHGlobal(capabilitiesMemory);
            Marshal.FreeHGlobal(attributes);
        }
    }

    private static string Quote(string value)
    {
        if (value.Length > 0 && !value.Any(char.IsWhiteSpace) && !value.Contains('"'))
            return value;
        var quoted = new System.Text.StringBuilder("\"");
        int backslashes = 0;
        foreach (char character in value)
        {
            if (character == '\\')
            {
                backslashes++;
                continue;
            }
            if (character == '"')
            {
                quoted.Append('\\', backslashes * 2 + 1).Append(character);
                backslashes = 0;
                continue;
            }
            quoted.Append('\\', backslashes).Append(character);
            backslashes = 0;
        }
        quoted.Append('\\', backslashes * 2).Append('"');
        return quoted.ToString();
    }

    private static Win32Exception Win32(string operation) => new(Marshal.GetLastWin32Error(), operation);

    private sealed record Options(string Workspace, long MemoryBytes, int CpuCount, int HostCpuCount,
        int MaxProcesses, IReadOnlyList<string> ReadPaths, IReadOnlyList<string> ReadTrees,
        IReadOnlyList<string> Command)
    {
        public static Options Parse(string[] args)
        {
            string? workspace = null;
            long memory = 0;
            int cpu = 0, hostCpu = 0, processes = 0;
            List<string> read = [];
            List<string> readTrees = [];
            int index = 0;
            for (; index < args.Length && args[index] != "--"; index++)
            {
                string value = index + 1 < args.Length ? args[++index] : throw new ArgumentException("Missing option value");
                switch (args[index - 1])
                {
                    case "--workspace": workspace = Path.GetFullPath(value); break;
                    case "--memory": memory = long.Parse(value); break;
                    case "--cpu": cpu = int.Parse(value); break;
                    case "--host-cpu": hostCpu = int.Parse(value); break;
                    case "--processes": processes = int.Parse(value); break;
                    case "--read": read.Add(Path.GetFullPath(value)); break;
                    case "--read-tree": readTrees.Add(Path.GetFullPath(value)); break;
                    default: throw new ArgumentException($"Unknown option {args[index - 1]}");
                }
            }
            if (index >= args.Length || ++index >= args.Length || workspace is null || memory < 1 || cpu < 1
                    || hostCpu < 1 || processes < 1)
                throw new ArgumentException("Required launch options or command are missing");
            return new Options(workspace, memory, cpu, hostCpu, processes, read, readTrees, args[index..]);
        }
    }

    private sealed class SafeKernelHandle : Microsoft.Win32.SafeHandles.SafeHandleZeroOrMinusOneIsInvalid
    {
        public SafeKernelHandle(IntPtr value) : base(true) => SetHandle(value);
        protected override bool ReleaseHandle() => Native.CloseHandle(handle);
    }

    [StructLayout(LayoutKind.Sequential)] private struct SecurityCapabilities
    {
        public IntPtr AppContainerSid;
        public IntPtr Capabilities;
        public uint CapabilityCount;
        public uint Reserved;
    }
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)] private struct StartupInfo
    {
        public uint Cb; public string? Reserved; public string? Desktop; public string? Title;
        public uint X, Y, XSize, YSize, XCountChars, YCountChars, FillAttribute, Flags;
        public ushort ShowWindow, Reserved2; public IntPtr ReservedPointer, StdInput, StdOutput, StdError;
    }
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)] private struct StartupInfoEx
    {
        public StartupInfo StartupInfo; public IntPtr AttributeList;
    }
    [StructLayout(LayoutKind.Sequential)] private struct ProcessInformation
    {
        public IntPtr Process, Thread; public uint ProcessId, ThreadId;
    }
    [StructLayout(LayoutKind.Sequential)] private struct IoCounters
    {
        public ulong ReadOperationCount, WriteOperationCount, OtherOperationCount,
            ReadTransferCount, WriteTransferCount, OtherTransferCount;
    }
    [StructLayout(LayoutKind.Sequential)] private struct BasicLimitInformation
    {
        public long PerProcessUserTimeLimit, PerJobUserTimeLimit;
        public uint LimitFlags; public nuint MinimumWorkingSetSize, MaximumWorkingSetSize;
        public uint ActiveProcessLimit; public nuint Affinity; public uint PriorityClass, SchedulingClass;
    }
    [StructLayout(LayoutKind.Sequential)] private struct JobObjectExtendedLimitInformation
    {
        public BasicLimitInformation BasicLimitInformation; public IoCounters IoInfo;
        public nuint ProcessMemoryLimit, JobMemoryLimit, PeakProcessMemoryUsed, PeakJobMemoryUsed;
    }
    [StructLayout(LayoutKind.Sequential)] private struct JobObjectCpuRateControlInformation
    {
        public uint ControlFlags, CpuRate;
    }

    private static class Native
    {
        [DllImport("userenv.dll", CharSet = CharSet.Unicode)]
        internal static extern int CreateAppContainerProfile(string name, string displayName, string description,
            IntPtr capabilities, uint capabilityCount, out IntPtr sid);
        [DllImport("userenv.dll", CharSet = CharSet.Unicode)]
        internal static extern int DeriveAppContainerSidFromAppContainerName(string name, out IntPtr sid);
        [DllImport("advapi32.dll")] internal static extern IntPtr FreeSid(IntPtr sid);
        [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
        internal static extern IntPtr CreateJobObject(IntPtr attributes, string? name);
        [DllImport("kernel32.dll", SetLastError = true)] [return: MarshalAs(UnmanagedType.Bool)]
        internal static extern bool SetInformationJobObject(IntPtr job, uint informationClass, IntPtr information, uint length);
        [DllImport("kernel32.dll", SetLastError = true)] [return: MarshalAs(UnmanagedType.Bool)]
        internal static extern bool AssignProcessToJobObject(IntPtr job, IntPtr process);
        [DllImport("kernel32.dll", SetLastError = true)] [return: MarshalAs(UnmanagedType.Bool)]
        internal static extern bool InitializeProcThreadAttributeList(IntPtr list, uint count, uint flags, ref nuint size);
        [DllImport("kernel32.dll", SetLastError = true)] [return: MarshalAs(UnmanagedType.Bool)]
        internal static extern bool UpdateProcThreadAttribute(IntPtr list, uint flags, nuint attribute, IntPtr value,
            nuint size, IntPtr previousValue, IntPtr returnSize);
        [DllImport("kernel32.dll")] internal static extern void DeleteProcThreadAttributeList(IntPtr list);
        [DllImport("kernel32.dll", EntryPoint = "CreateProcessW", CharSet = CharSet.Unicode, SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)] internal static extern bool CreateProcess(string? applicationName,
            string commandLine, IntPtr processAttributes, IntPtr threadAttributes, [MarshalAs(UnmanagedType.Bool)] bool inheritHandles,
            uint creationFlags, IntPtr environment, string currentDirectory, ref StartupInfoEx startupInfo,
            out ProcessInformation processInformation);
        [DllImport("kernel32.dll")] internal static extern uint ResumeThread(IntPtr thread);
        [DllImport("kernel32.dll")] internal static extern uint WaitForSingleObject(IntPtr handle, uint milliseconds);
        [DllImport("kernel32.dll", SetLastError = true)] [return: MarshalAs(UnmanagedType.Bool)]
        internal static extern bool GetExitCodeProcess(IntPtr process, out uint exitCode);
        [DllImport("kernel32.dll", SetLastError = true)] [return: MarshalAs(UnmanagedType.Bool)]
        internal static extern bool CloseHandle(IntPtr handle);
        [DllImport("kernel32.dll")] internal static extern IntPtr GetStdHandle(int handle);
    }
}
