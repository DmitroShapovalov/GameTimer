# Privacy

GameTimer is built so that a family's data stays on the family's own devices. There are no accounts,
no cloud storage, no analytics and no ads. This note says what goes where, including the two outside
services used only for "Control from anywhere": the relay (run by this project on Fly.io) and Google's
push service (Firebase Cloud Messaging).

The technical details are in [`protocol.md`](protocol.md) (§9 discovery, §10.5 "What the relay sees",
§10.6 push wake-ups, §11 security assumptions); the threat model of the PC side in
[`SECURITY.md`](SECURITY.md).

## On the PC

- Settings, today's usage, the running timer and the history are stored only on the PC, encrypted for
  the Windows account (DPAPI). That protects them from other accounts and from editing by hand, not
  from someone who uses the same Windows account.
- The PC also keeps its own key pair, and for each paired parent app its name, role and public key, and
  its push token if that app asked for wake-ups. A push token is never shown or sent to other parent
  apps. Removing a parent app (on the PC, or in the app under More → Parents) deletes all of that.
  Uninstalling the app from a phone does not: remove that phone on the PC too.
- To find the PC again after its address changes, the PC remembers up to 16 networks where a parent
  app connected (a hash of the subnet and the router's hardware address).

## On the parent's phone

- The phone's key pair (encrypted with a key in the Android Keystore), the paired PCs (name, last
  address on the home network, connection keys), the app's settings, its push token, and for each PC a
  list of up to 400 recent history events, so it never shows the same notification twice. Android
  backup is turned off for the app: none of this goes to the cloud.
- Everything else the app shows (the timer, schedule, settings, history) is read from the PC when you
  open it.

## Between the phone and the PC

Everything between the parent app and a PC is end-to-end encrypted (Noise, keys pinned at pairing). At
home they talk directly over the home network. Nobody in between — not the network, not the relay —
can read the timer, the history or the names, or send a command.

- **Pairing.** The QR code shown on the PC contains the PC's name, its address on the home network and
  one-time secrets; it works once, for 2 minutes. Don't share a photo of it.
- **Finding the PC on the home network (mDNS).** The PC announces itself under a neutral service name
  with a random host name and one short tag per paired phone; the tags change every day and only the
  paired phones can recognise them. Someone on the same network can see that such a device is there and
  how many phones are paired, nothing more. The PC announces only on networks where a phone already
  connected.
- **The older phone web page** (for phones paired before the Android app) is plain HTTP on the home
  network, not end-to-end encrypted. New setups should use the app.

## "Control from anywhere" (off by default)

The PC owner turns it on in the PC's settings (Windows Hello). Only then do the relay and Google's push
service come into play; until a paired PC has it on, the app doesn't ask Google for a push token.

**The relay** (`gametimer-relay.fly.dev`, in Amsterdam) passes encrypted messages between the phone
and the PC when they are not on the same network. Nothing is written to disk. In memory it holds only
what a live connection needs, short-lived counters per address for its limits, and short hashes of push
tokens (about 20 seconds; tokens Google reports as unknown until the relay restarts). What it can see:

- IP addresses, when the PC and the phones connect, and the size and timing of messages;
- that one PC talks to a given number of phones. The routes it uses for a PC change every day, but the
  relay itself can link a PC and its phones over time;
- at a wake-up (below), the phone's push token together with the PC's connection. The token is a stable
  id of the phone, so with the phone's mobile IP address the relay could link it to your PC and
  roughly tell where the phone is.

It never sees what is in the messages, any names, keys or the ids the devices use for each other. The
relay writes no IP addresses, routes or tokens to its logs, only counts and errors.

**Push wake-ups (Android).** Away from home the app isn't connected all the time. When something
happens on the PC that the parent wants to be told about, the PC asks the relay to wake the phone
through Firebase Cloud Messaging. The message says only "wake" — no event, no PC, no name. The phone
then connects (through the relay) and fetches the news itself, end-to-end encrypted. Google sees that
the phone was woken and when (in effect, when something happened on the PC), never what. The Firebase
library on the phone keeps an installation id and Google keeps delivery logs. Firebase Analytics is
off. Wake-ups stop when background notifications are turned off on the phone, when the PC is removed
from the phone, or when the PC owner turns "Control from anywhere" off.

**The hosts.** Fly.io (hosting the relay) sees connection data such as IP addresses to run its
network, and keeps the relay's own logs searchable for 7 days. Google runs the push service. Both are
US companies acting under their own privacy policies:
[Fly.io](https://fly.io/legal/privacy-policy/), [Google](https://policies.google.com/privacy).

## At home only

With "Control from anywhere" off, nothing leaves the home network: the phone finds the PC on the local
network and talks to it directly. No outside service is involved.

## Questions

Open an issue in the repository: https://github.com/DmitroShapovalov/GameTimer/issues
