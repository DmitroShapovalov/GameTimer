using System.Security.Cryptography;
using System.Text.Json;
using System.Text.Json.Nodes;
using GameTimer.Protocol.Encodings;
using GameTimer.Protocol.Identity;
using GameTimer.Protocol.Messages;
using GameTimer.Protocol.Noise;

namespace GameTimer.Protocol.Agent;

public sealed partial class AgentHost
{
    public const int MaxHubNameLength = 64;

    /// <summary>
    /// <c>/v2/pair</c> (§8.2): IKpsk1 with the current pairing secret, <c>pair.hello</c>, the owner types
    /// the 6-digit code on the PC, then <c>pair.result</c>. Only pair.hello, pair.result and bye are allowed.
    /// </summary>
    private async Task RunPairingAsync(IFrameTransport transport, HandshakeTicket ticket, CancellationToken cancel)
    {
        var ip = ticket.RemoteIp;
        PairingOffer? offer;
        lock (_lock) offer = _pairing;
        if (offer != null && Expired(offer)) offer = null;
        // No window open: still read msg 1 and fail it like a wrong secret, so the timing doesn't tell
        // the LAN whether a pairing window is open (§3: no oracle)
        var psk = offer?.Secret ?? RandomNumberGenerator.GetBytes(32);
        var aborted = offer?.Aborted ?? CancellationToken.None;

        CipherState send, recv;
        byte[] hubKey, handshakeHash;
        string hubName;
        bool counted = false;

        try
        {
            using (var timeout = new CancellationTokenSource(HandshakeTimeout, _time))
            using (var hsCancel = CancellationTokenSource.CreateLinkedTokenSource(cancel, timeout.Token, aborted))
            {
                try
                {
                    var msg1 = await transport.ReceiveAsync(hsCancel.Token).ConfigureAwait(false);
                    if (msg1 == null || OverFailureLimit(ip)) return;

                    var hs = new HandshakeState(NoisePattern.IKpsk1, initiator: false, Prologue, _key, remoteStatic: null, psk: psk);
                    byte[] payload;
                    try { payload = hs.ReadMessage(msg1); }
                    catch (NoiseException) { RecordFailure(ip); return; } // wrong secret (or no window)
                    if (offer == null || !IsBareMessage(payload, "pair")) { RecordFailure(ip); return; }

                    // A valid msg 1 may be a replay: it does not use up the secret, but takes an attempt slot
                    lock (_lock)
                    {
                        if (_pairing != offer || Expired(offer) || offer.Attempts >= MaxPairingAttempts) return;
                        offer.Attempts++;
                        counted = true;
                    }

                    await transport.SendAsync(hs.WriteMessage(ReadOnlySpan<byte>.Empty), hsCancel.Token).ConfigureAwait(false);
                    (send, recv) = hs.Split();
                    hubKey = hs.RemoteStaticPublicKey!;
                    handshakeHash = hs.GetHandshakeHash();

                    var first = await transport.ReceiveAsync(hsCancel.Token).ConfigureAwait(false);
                    if (first == null || !TryReadPairHello(recv, first, out hubName!)) { RecordFailure(ip); return; }
                }
                catch (OperationCanceledException) when (!cancel.IsCancellationRequested)
                {
                    if (!aborted.IsCancellationRequested) RecordFailure(ip);
                    return;
                }
                catch (ProtocolViolation)
                {
                    RecordFailure(ip);
                    return;
                }
            }

            // pair.hello: only now has a live phone proven the secret (§8.2 step 4)
            ticket.Dispose();
            var keyText = Base64Url.Encode(hubKey);
            CancellationTokenSource attempt;
            CancellationTokenSource? replaced;
            bool twoPhones;
            lock (_lock)
            {
                offer!.UsedBy ??= keyText;
                twoPhones = offer.UsedBy != keyText;
                attempt = CancellationTokenSource.CreateLinkedTokenSource(offer.Aborted);
                replaced = twoPhones ? null : offer.ActiveAttempt;
                if (!twoPhones) offer.ActiveAttempt = attempt;
                if (twoPhones)
                {
                    // Close the window and abort the waiting attempt before anything else can run,
                    // so an owner confirming at this very moment can't commit the first phone
                    if (_pairing == offer) _pairing = null;
                    offer.Abort();
                }
            }

            if (twoPhones)
            {
                // A second, different phone with the same QR: it may have been photographed (§8.2)
                await SendDirectAsync(transport, send, Bye("code_used")).ConfigureAwait(false);
                attempt.Dispose();
                _pairingUi.PairingAborted(PairingAbortReason.TwoPhones);
                return;
            }
            try { replaced?.Cancel(); } catch (ObjectDisposedException) { } // the same phone started over: its older attempt ends

            using (attempt)
                await ConfirmAndCommitAsync(transport, send, recv, offer, attempt, keyText, hubName, handshakeHash, cancel).ConfigureAwait(false);
        }
        finally
        {
            if (counted) lock (_lock) offer!.Attempts--;
        }
    }

    private async Task ConfirmAndCommitAsync(IFrameTransport transport, CipherState send, CipherState recv, PairingOffer offer,
        CancellationTokenSource attempt, string keyText, string hubName, byte[] handshakeHash, CancellationToken cancel)
    {
        var expected = PairingCode.Derive(handshakeHash);

        using var timeout = new CancellationTokenSource(PairingConfirmTimeout, _time);
        using var confirm = CancellationTokenSource.CreateLinkedTokenSource(cancel, timeout.Token, attempt.Token);

        // The phone must stay quiet while the owner types; anything it sends (or a close) ends the attempt
        var watch = WatchQuietAsync(transport, confirm);

        string? typed = null;
        try
        {
            // WaitAsync: the attempt ends on time even if the dialog ignores its token
            typed = await _pairingUi.AskForCodeAsync(hubName, confirm.Token).WaitAsync(confirm.Token).ConfigureAwait(false);
        }
        catch (OperationCanceledException) { }
        catch (Exception) { } // a broken dialog counts as a cancel

        bool accepted;
        lock (_lock)
        {
            accepted = typed != null && !confirm.IsCancellationRequested && _pairing == offer && offer.UsedBy == keyText
                       && !offer.Aborted.IsCancellationRequested && CodesEqual(typed.Trim(), expected);
        }
        if (!accepted)
        {
            confirm.Cancel();
            await SendDirectAsync(transport, send, Bye("denied")).ConfigureAwait(false);
            await watch.ConfigureAwait(false);
            return;
        }

        // Commit, then send: if the phone vanishes in between it is paired without knowing it and simply
        // pairs again (same key: same role, a new ak). The opposite order could tell a phone "paired"
        // for a hub the PC never stored.
        var peer = CommitPeer(keyText, hubName);
        var result = new JsonObject
        {
            ["t"] = "pair.result",
            ["ok"] = true,
            ["role"] = RoleName(peer.Role),
            ["agent"] = AgentJson(),
            ["ak"] = peer.Ak,
        };
        confirm.Cancel();
        await watch.ConfigureAwait(false);
        await SendDirectAsync(transport, send, result).ConfigureAwait(false);
        PublishPeers("added", peer.Id, origin: null);
        PeersChanged?.Invoke();
    }

    /// <summary>Stores the hub (§8.2 step 7). A hub key paired before keeps its role and gets a new ak.</summary>
    private Peer CommitPeer(string keyText, string hubName)
    {
        Peer result;
        lock (_lock)
        {
            var existing = _state.Peers.FirstOrDefault(p => p.PublicKey == keyText);
            var role = existing?.Role
                       ?? (_state.Peers.Any(p => p.Role == PeerRole.Admin) ? PeerRole.Parent : PeerRole.Admin);
            var publicKey = Base64Url.TryDecode(keyText)!;
            var peer = new Peer
            {
                Id = Ids.HubId(publicKey),
                PublicKey = keyText,
                Name = hubName,
                Role = role,
                PairedAt = existing?.PairedAt ?? _time.GetUtcNow(),
                LastSeen = existing?.LastSeen,
                Ak = Base64Url.Encode(RandomNumberGenerator.GetBytes(32)),
            };
            if (existing != null) _state.Peers.Remove(existing);
            _state.Peers.Add(peer);
            result = Clone(peer);
        }
        Persist();
        return result;
    }

    private bool TryReadPairHello(CipherState recv, byte[] frame, out string? name)
    {
        name = null;
        byte[] plain;
        try { plain = recv.Decrypt(frame); }
        catch (NoiseException) { return false; }
        using var doc = StrictJson.Parse(plain);
        var r = doc.RootElement;
        if (Str(r, "t") != "pair.hello") return false;
        if (!r.TryGetProperty("protocol", out var p) || !p.TryGetInt32(out var major) || major != ProtocolMajor) return false;
        var n = Str(r, "name")?.Trim();
        // No control or format characters (e.g. U+202E right-to-left override) in a name the owner reads
        if (string.IsNullOrEmpty(n) || n.Length > MaxHubNameLength
            || n.Any(c => char.IsControl(c) || char.GetUnicodeCategory(c) == System.Globalization.UnicodeCategory.Format)) return false;
        name = n;
        return true;
    }

    /// <summary>Completes when the phone sends anything or closes, or when <paramref name="confirm"/> is cancelled.</summary>
    private static async Task WatchQuietAsync(IFrameTransport transport, CancellationTokenSource confirm)
    {
        try
        {
            await transport.ReceiveAsync(confirm.Token).ConfigureAwait(false);
        }
        catch (Exception)
        {
            // cancelled, closed or a protocol violation: all end the attempt the same way
        }
        try { confirm.Cancel(); } catch (ObjectDisposedException) { }
    }

    private static async Task SendDirectAsync(IFrameTransport transport, CipherState send, JsonObject message)
    {
        try { await transport.SendAsync(send.Encrypt(Serialize(message)), CancellationToken.None).ConfigureAwait(false); }
        catch (Exception) { /* the phone is gone */ }
    }

    private static JsonObject Bye(string code) => new() { ["t"] = "bye", ["code"] = code };

    private bool Expired(PairingOffer offer) => _time.GetUtcNow() >= offer.ExpiresAt;
}
