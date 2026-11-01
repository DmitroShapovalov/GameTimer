# Privacy

GameTimer is built so that a family's data stays on the family's own devices. There are no accounts,
no cloud storage, no analytics and no ads. This note says exactly what goes where, including the two
outside services used for "Control from anywhere": the relay (hosted on Fly.io) and Google's push
service (Firebase Cloud Messaging). Both are used only when that option is on.

The technical details are in [`docs/protocol.md`](protocol.md) (§10.5 "What the relay sees", §10.6
"Push wake-ups") and the threat model in [`SECURITY.md`](SECURITY.md).

## On the PC

- Settings, today's usage, the running timer and the history are stored only on the PC, encrypted for
  the Windows account (DPAPI).
- The PC's key pair, the paired parent apps (their name, role and public key) and, per parent app, its
  push token if it asked for wake-ups. A parent app's push token is never shown or sent to other
  parent apps, and it is deleted with that parent app.

## On the parent's phone

- The phone's key pair (encrypted with a key in the Android Keystore), the paired PCs and the
  notification settings. Nothing is backed up to the cloud: Android backup is turned off for the app.
- The app shows the timer, the schedule, the settings and the history it reads from the PC; it doesn't
  keep a copy of the history beyond what notifications need (the last events seen per PC).

## Between the phone and the PC

Everything between a parent app and a PC is end-to-end encrypted (Noise, keys pinned at pairing).
At home they talk directly over the home network. Nobody in between — not the network, not the relay
— can read the timer, the history or the names, or send a command.

## "Control from anywhere" (off by default)

The PC owner turns it on in the PC's settings (Windows Hello). Only then:

**The relay** (`gametimer-relay.fly.dev`, run by the project on Fly.io in Amsterdam) passes encrypted
messages between the phone and the PC when they are not on the same network. It stores nothing and
keeps nothing after a connection closes. What it can see:

- IP addresses, when the PC and the phones connect, and the size and timing of messages;
- that one PC talks to a given number of phones. The addresses it uses for a PC change every day, but
  the relay itself can link a PC and its phones over time;
- for a wake-up (below): the phone's push token, together with the PC's connection.

It never sees what is in the messages, any names, keys or the ids the devices use for each other.
The relay writes no IP addresses, routes or tokens to its logs, only counts and errors. Fly.io, the
host, handles connection data to run its network under its own privacy policy; app logs there are kept
for up to 7 days.

**Push wake-ups (Android).** Away from home the app isn't connected all the time. When something
happens on the PC that the parent wants to be told about, the PC asks the relay to wake the phone
through Firebase Cloud Messaging. The message says only "wake" — no event, no PC, no name. The phone
then connects (through the relay) and fetches the news itself, end-to-end encrypted. Google sees that
the phone was woken and when (in effect, when something happened on the PC), never what. The Firebase
library on the phone keeps an installation id and Google keeps delivery logs, under Google's policy.
Firebase Analytics is turned off. Wake-ups stop when background notifications are turned off on the
phone, when the PC is removed from the phone, or when the PC owner turns "Control from anywhere" off.

## At home only

With "Control from anywhere" off, nothing leaves the home network: the phone finds the PC by a
rotating tag on the local network (mDNS) and talks to it directly. No outside service is involved.

## Questions

Open an issue in the repository: https://github.com/DmitroShapovalov/GameTimer/issues
