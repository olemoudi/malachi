# Malachi — a DNS ad and tracker blocker for Android

[![CI](https://github.com/olemoudi/malachi/actions/workflows/ci.yml/badge.svg)](https://github.com/olemoudi/malachi/actions/workflows/ci.yml)
[![coverage](.github/badges/coverage.svg)](https://github.com/olemoudi/malachi/actions/workflows/ci.yml)

Blocks ads and trackers for every app on the phone, not just the browser. No account, no
subscription, no server: the filtering happens on the device, and nothing about what you look
up ever leaves it.

## Download

Point your phone's camera at this code, or tap the link below.

<img src="docs/install-qr.png" width="200" alt="QR code linking to the latest Malachi APK">

**[github.com/olemoudi/malachi/releases/latest/download/malachi.apk](https://github.com/olemoudi/malachi/releases/latest/download/malachi.apk)**

Android 10 or newer. Once installed, Malachi keeps itself up to date from that same page — the
QR always resolves to the newest release, so it never has to be reprinted.

Your phone will warn you that the file comes from outside the Play Store, and Play Protect may
offer to scan it first — that is normal for any app installed this way.

> **Alpha software.** Offered as-is. It filters DNS and nothing else, so the worst it can do to
> a misbehaving app is make one hostname unreachable, and the switch on the home screen turns it
> off in one tap if you need to rule it out. Read *What it can't block* below before relying on
> it.

## What it does

**Blocks at the DNS layer, system-wide.** Malachi runs as a local VPN, which is the only honest
way an Android app can see another app's traffic. But the tunnel is a decoy with a very small
route table: it advertises its own address as the phone's DNS server and routes that address,
and nothing else. Every other byte the phone sends never touches this app. What arrives is a
DNS query, which is answered locally if the domain is on a list you subscribed to, and
forwarded to a real resolver untouched if it isn't.

**Per-app control, in both directions.** Filter everything except a few apps ("block ads
everywhere, but leave my banking app alone"), or filter nothing except a few apps ("I only want
this one game filtered"). It is one switch read from either end rather than two features that
can disagree. Apps out of scope are excluded by the platform when the tunnel is built, so their
lookups never reach Malachi at all.

**Statistics that go back.** Today, this week, this month and all time: what proportion of
lookups was refused, which apps generate the most refusals, and which apps have the highest
*rate* of them — a different question, and usually a different app. Only counts are kept, never
a domain.

**A query log you can act on.** Every lookup is shown with the app that made it and the reason
for the verdict — which list blocked it, or which of your own rules. Any line is one tap from
four actions: block or allow it everywhere, or block or allow it *in that app only*. That last
pair is the difference between switching a whole app out of the filter to make it work again
and exempting the one hostname that was breaking it.

**Reputable lists, kept current.** Ten curated sources, two of them on by default (AdGuard's
DNS filter and AdAway — the broad conservative one plus the mobile-specific one). Everything
else is opt-in, grouped by what it is for, and ordered roughly by how likely it is to break
something. Refreshes are conditional requests, so the usual cost of staying current is one
round trip rather than twenty megabytes.

**Your rules always win.** A rule you wrote beats every downloaded list, and a more specific
domain beats a less specific one, so allowing `cdn.example.com` while blocking `example.com`
does what it looks like it does. Tomorrow's list refresh cannot overwrite a decision you made.

**It comes back.** A low-memory kill, a reboot, or its own update all restore the filter without
you opening anything; after a reboot that can take a couple of minutes, and Malachi says so
rather than pretending otherwise. Setting it as your always-on VPN makes recovery immediate and
is the only thing that survives a force-stop. Updates are checked every twelve hours whether or
not you ever open the app, and a new version announces itself with a notification.

**Honest about its own state.** Only one VPN can run on Android at a time, consent can be
withdrawn, and the system's Private DNS setting sends lookups somewhere Malachi will never see
them. All of those look identical from a settings screen — a switch that is on and a filter
that isn't running — so the home screen names which one is happening instead of showing a green
light over nothing.

## What it can't block

- **Apps that use their own encrypted DNS** (DoH or DoT). They never ask the system which
  resolver to use. The *Apps that go around the filter* setting can cut them off by routing the
  resolvers they embed into the tunnel — at the cost of breaking that traffic, which is why it
  is a dial and not a default.
- **The system's Private DNS**, for the same reason. If it is on, Malachi says so on the home
  screen; turning it off in Android settings is the only fix.
- **Content fetched from a bare IP address**, with no lookup to intercept.
- **Ads served from the same domain as the content around them.** DNS sees a name and nothing
  else; it cannot tell an article from the advertisement beside it.
- **Anything at all, while another VPN app is running.** Android allows exactly one.

## Privacy

**No domain is ever written down.** The query log — the one place a hostname appears — is held
in memory and nowhere else, and it is gone when the filter stops. The statistics do survive
restarts, but they are arithmetic: how many lookups an app made on a given day and how many were
refused. You cannot reconstruct a single visited site from them, and "Reset statistics" clears
them for good.

Nothing is transmitted anywhere. The only outbound requests Malachi makes are the blocklists it
downloads, its own `version.json`, and its own APK.

**No permanent notification.** Android shows a VPN key in the status bar while the tunnel is up,
which is the honest indicator; a second one of our own would only be noise. A notification
appears while filtering is paused, and if the filter stops and needs you.

**It does not grow on disk.** Every file has a bound: the blocklists are pruned to what you
subscribe to, the debug log is capped at 128 KB, the statistics keep 90 days of detail, and a
downloaded update is deleted once installed.

## Building

```
./gradlew test              # unit tests
./gradlew :app:assembleDebug
```

Requires JDK 17 and an Android SDK (set `sdk.dir` in `local.properties`).

## How it is put together

| Module | What lives there |
| --- | --- |
| `:core-filter` | Pure Kotlin, no Android. The compiled blocklist (`DomainIndex`), the list parser (`RuleParser`), the precedence rules (`FilterEngine`), and just enough DNS and IP to read a question and forge an answer. |
| `:app` | The tunnel (`MalachiVpnService`), the list downloader, the self-updater, and the Compose UI. |

A blocklist is not kept as text. Each domain is reduced to a 64-bit hash and the hashes are held
sorted in one `LongArray`: eight bytes per domain, contiguous, no per-entry object, and a lookup
is a binary search over primitives. A 250,000-domain list occupies about 2 MB instead of the
hundreds of megabytes the obvious `Set<String>` would cost in a process that has to stay alive
to answer every query.

## Releases

Push a tag matching `v*`. GitHub Actions builds the release APK and publishes it as
`malachi.apk` alongside a `version.json`, which is what the in-app updater reads. The asset
names never change: they are baked into every installed copy.
