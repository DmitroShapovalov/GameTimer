namespace GameTimer.Services;

/// <summary>
/// Gives background code (Kestrel request handlers) a way to run work on the UI thread,
/// and lets UI-thread-only classes check they are called correctly.
/// Wraps a SynchronizationContext so it has no WPF dependency.
/// </summary>
public sealed class UiThread
{
    private readonly SynchronizationContext _context;
    private readonly int _threadId;

    /// <summary>Create on the UI thread, passing that thread's synchronization context.</summary>
    public UiThread(SynchronizationContext context)
    {
        _context = context;
        _threadId = Environment.CurrentManagedThreadId;
    }

    public bool CheckAccess() => Environment.CurrentManagedThreadId == _threadId;

    public void VerifyAccess()
    {
        if (!CheckAccess())
            throw new InvalidOperationException("This member must be called on the UI thread.");
    }

    public void Post(Action action) => _context.Post(_ => action(), null);

    public Task<T> InvokeAsync<T>(Func<T> func)
    {
        var tcs = new TaskCompletionSource<T>(TaskCreationOptions.RunContinuationsAsynchronously);
        _context.Post(_ =>
        {
            try { tcs.SetResult(func()); }
            catch (Exception ex) { tcs.SetException(ex); }
        }, null);
        return tcs.Task;
    }
}
