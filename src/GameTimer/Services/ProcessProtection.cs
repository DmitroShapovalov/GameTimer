using System.Runtime.InteropServices;
using System.Security.AccessControl;
using System.Security.Principal;

namespace GameTimer.Services;

/// <summary>
/// Parental-control resilience: makes the child unable to close the app from Task Manager by
/// denying PROCESS_TERMINATE on this process's security descriptor for the current user, while
/// keeping every other right (so the app can still manage and later re-open itself). The app's
/// own clean exit uses normal shutdown, not TerminateProcess, so it is unaffected.
///
/// Limitation: an administrator token can still override the DACL (take ownership, SeDebugPrivilege).
/// This is a deterrent for the common case, not a guarantee. Off by default; the parent enables it.
/// </summary>
public static class ProcessProtection
{
    private const uint PROCESS_TERMINATE = 0x0001;
    private const uint PROCESS_ALL_ACCESS = 0x001FFFFF; // STANDARD_RIGHTS_REQUIRED | SYNCHRONIZE | 0xFFFF
    private const int DACL_SECURITY_INFORMATION = 0x00000004;

    [DllImport("advapi32.dll", SetLastError = true)]
    private static extern bool SetKernelObjectSecurity(IntPtr handle, int securityInformation, byte[] securityDescriptor);

    [DllImport("kernel32.dll")]
    private static extern IntPtr GetCurrentProcess();

    /// <summary>Denies termination of this process for the current user. Returns false on failure.</summary>
    public static bool DenyTermination() => Apply(deny: true);

    /// <summary>Restores normal access, so the process can be closed again. Returns false on failure.</summary>
    public static bool AllowTermination() => Apply(deny: false);

    private static bool Apply(bool deny)
    {
        try
        {
            var user = WindowsIdentity.GetCurrent().User;
            if (user == null) return false;

            var dacl = new RawAcl(GenericAcl.AclRevision, 2);
            int index = 0;
            if (deny)
            {
                // Deny ACE must precede the allow ACE to take effect
                dacl.InsertAce(index++, new CommonAce(
                    AceFlags.None, AceQualifier.AccessDenied, (int)PROCESS_TERMINATE, user, false, null));
            }
            dacl.InsertAce(index, new CommonAce(
                AceFlags.None, AceQualifier.AccessAllowed, (int)PROCESS_ALL_ACCESS, user, false, null));

            var sd = new RawSecurityDescriptor(ControlFlags.DiscretionaryAclPresent, null, null, null, dacl);
            var binary = new byte[sd.BinaryLength];
            sd.GetBinaryForm(binary, 0);

            return SetKernelObjectSecurity(GetCurrentProcess(), DACL_SECURITY_INFORMATION, binary);
        }
        catch (Exception ex) when (ex is not OutOfMemoryException)
        {
            return false;
        }
    }
}
