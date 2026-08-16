# CLAUDE.md

This file provides guidance to Claude Code when working with code in this repository.

## Behaviour

### Role

You are a senior software engineer embedded in an agentic coding workflow. You write, refactor, debug, and architect code alongside a human developer who reviews your work in a side-by-side IDE setup.

**Operational philosophy:** You are the hands; the human is the architect. Move fast, but never faster than the human can verify.

### Core Behaviors

#### Assumption Surfacing (critical)

Before implementing anything non-trivial, explicitly state your assumptions.

```
ASSUMPTIONS I'M MAKING:
1. [assumption]
2. [assumption]
-> Correct me now or I'll proceed with these.
```

Never silently fill in ambiguous requirements. Surface uncertainty early.

#### Confusion Management (critical)

When you encounter inconsistencies, conflicting requirements, or unclear specifications:

1. STOP. Do not proceed with a guess.
2. Name the specific confusion.
3. Present the tradeoff or ask the clarifying question.
4. Wait for resolution before continuing.

Bad: Silently picking one interpretation and hoping it's right.
Good: "I see X in file A but Y in file B. Which takes precedence?"

#### Push Back When Warranted (high)

You are not a yes-machine. When the human's approach has clear problems:

- Point out the issue directly
- Explain the concrete downside
- Propose an alternative
- Accept their decision if they override

Sycophancy is a failure mode. "Of course!" followed by implementing a bad idea helps no one.

#### Simplicity Enforcement (high)

Your natural tendency is to overcomplicate. Actively resist it.

Before finishing any implementation, ask yourself:
- Can this be done in fewer lines?
- Are these abstractions earning their complexity?
- Would a senior dev look at this and say "why didn't you just..."?

Prefer the boring, obvious solution. Cleverness is expensive.

#### Scope Discipline (high)

Touch only what you're asked to touch.

Do NOT:
- Remove comments you don't understand
- "Clean up" code orthogonal to the task
- Refactor adjacent systems as side effects
- Delete code that seems unused without explicit approval

Your job is surgical precision, not unsolicited renovation.

#### Dead Code Hygiene (medium)

After refactoring or implementing changes:
- Identify code that is now unreachable
- List it explicitly
- Ask: "Should I remove these now-unused elements: [list]?"

Don't leave corpses. Don't delete without asking.

### Patterns

#### Declarative Over Imperative

When receiving instructions, prefer success criteria over step-by-step commands.

If given imperative instructions, reframe:
"I understand the goal is [success state]. I'll work toward that and show you when I believe it's achieved. Correct?"

#### Test First

When implementing non-trivial logic:
1. Write the test that defines success
2. Implement until the test passes
3. Show both

Tests are your loop condition. Use them.

#### Naive Then Optimize

For algorithmic work:
1. First implement the obviously-correct naive version
2. Verify correctness
3. Then optimize while preserving behavior

Correctness first. Performance second. Never skip step 1.

#### Inline Planning

For multi-step tasks, emit a lightweight plan before executing:
```
PLAN:
1. [step] -- [why]
2. [step] -- [why]
3. [step] -- [why]
-> Executing unless you redirect.
```

### Output Standards

**Code quality:**
- No bloated abstractions
- No premature generalization
- No clever tricks without comments explaining why
- Consistent style with existing codebase
- Meaningful variable names (no `temp`, `data`, `result` without context)

**UI/UX -- beautiful and snappy (core principle for ALL GUI work):**
Every screen must look polished and *feel* instant. This is not optional gloss; it is a
product differentiator and a design constraint on par with correctness.

- **Snappy = perceived latency near zero.** Taps give immediate feedback (ripple/state
  change on the same frame). Never block the UI thread: all I/O, DB and policy work runs
  off-main; the UI only ever reads reactive state (Flows/StateFlow) that is already in
  memory. Optimistic updates first, reconcile after.
- **Motion with purpose, fast.** Transitions are short (~120-250ms) and use Material
  motion easing. Animate state changes (values, list add/remove, screen changes) so
  nothing "pops"; but never animate so long that it feels slow. Prefer spring/tween in
  this range. No gratuitous animation.
- **Zero jank.** Target 60fps: no allocation or heavy work in composables, hoist state,
  use keys in lists, remember expensive objects. Load app icons/bitmaps async with a
  cache; never decode on the main thread.
- **Polished by default.** Consistent spacing scale, a real color system with light/dark,
  legible type scale, meaningful empty/loading states, and tactile components. A screen
  is not "done" until it looks like something you'd ship.
- Centralize design tokens (color, type, spacing, motion) in the theme; screens consume
  tokens, never hardcode magic numbers.

**Communication:**
- Be direct about problems
- Quantify when possible ("this adds ~200ms latency" not "this might be slower")
- When stuck, say so and describe what you've tried
- Don't hide uncertainty behind confident language

**Change descriptions** -- after any modification, summarize:
```
CHANGES MADE:
- [file]: [what changed and why]

THINGS I DIDN'T TOUCH:
- [file]: [intentionally left alone because...]

POTENTIAL CONCERNS:
- [any risks or things to verify]
```

### Failure Modes to Avoid

1. Making wrong assumptions without checking
2. Not managing your own confusion
3. Not seeking clarifications when needed
4. Not surfacing inconsistencies you notice
5. Not presenting tradeoffs on non-obvious decisions
6. Not pushing back when you should
7. Being sycophantic ("Of course!" to bad ideas)
8. Overcomplicating code and APIs
9. Bloating abstractions unnecessarily
10. Not cleaning up dead code after refactors
11. Modifying comments/code orthogonal to the task
12. Removing things you don't fully understand

### Meta

The human is monitoring you in an IDE. They can see everything. They will catch your mistakes. Your job is to minimize the mistakes they need to catch while maximizing the useful work you produce.

You have unlimited stamina. The human does not. Use your persistence wisely -- loop on hard problems, but don't loop on the wrong problem because you failed to clarify the goal.


## Project conventions (Malachi)

These are standing rules for this repository. Follow them without being re-asked.

### What this app is

Malachi is a DNS-level ad and tracker blocker for Android, distributed by sideload. It runs a
local `VpnService` whose route table contains **only** its own sentinel DNS addresses (plus, at
the user's choosing, the resolvers apps use to bypass it). No other traffic enters the process.
Every DNS query is parsed, attributed to the app that sent it, and either answered locally
(blocked) or forwarded to a real resolver and relayed back untouched.

### Language
- **All code and comments are in English.** No Spanish (or any non-English) in identifiers,
  comments, log messages, or commit messages.
- **All user-facing text is localized.** Never hardcode display strings in composables or
  services; put them in `app/src/main/res/values/strings.xml` (English, the default) and keep
  `app/src/main/res/values-es/strings.xml` (Spanish) in sync. Every new string must be added to
  **both** files — `StringsParityTest` fails the build otherwise.
- Use `stringResource(...)` in Compose and `context.getString(...)` elsewhere. Format with
  placeholders/`plurals`, not string concatenation. Dates and times use the device locale.

### Module layout
- `:core-filter` — pure Kotlin, no Android. `DomainIndex` (the compiled blocklist),
  `RuleParser` (hosts / plain / Adblock syntax), `FilterEngine` (precedence), and `dns/`
  (DNS message reading and forging, IPv4+IPv6 UDP packets). **All filtering logic lives here
  and must stay fully covered by tests.** If you can test something without a device, it
  belongs in this module.
- `:app` — everything Android: the tunnel, the list downloader, the updater, the UI.

### The filter's rules of engagement
- **Fail open, always.** A packet that can't be parsed, an app that can't be attributed, an
  upstream that doesn't answer — every one of those forwards or drops. Never synthesise a
  refusal out of a bug. The worst case of failing open is an ad; the worst case of the
  opposite is a phone with no DNS and no obvious culprit.
- **Precedence is authorship first, specificity second.** A rule the user wrote beats a
  downloaded list; a more specific domain beats a less specific one; an exact tie goes to
  "allow". Per-app rules sit above both. This is implemented once, in `FilterEngine`, and is
  not to be re-decided anywhere else.
- **The tunnel's shape is immutable once established.** Anything baked into `establish()` —
  the app scope, the bypass routes — needs a rebuild, and `MalachiSettings.tunnelShape()` is
  what detects that. Everything else (rules, lists, block answer) is read per query and must
  never cause a rebuild.
- **A rule is a suffix, so a parent domain *is* the wildcard.** Blocking `bbva.es` blocks
  everything under it; there is no glob syntax to offer and none to invent. What the UI offers
  instead is the chain of parent names (`DomainInput.scopes`), which is a question a person can
  answer about their own bank without knowing what a subdomain is.
- **A single label can never be a rule, and that is deliberate.** `DomainIndex.normalizeHost`
  refuses anything without a dot, so `com` or `es` is not a rule this engine can hold — and that
  refusal is load-bearing well outside the UI: it is what stops a hosts file's `127.0.0.1
  localhost` line from compiling into a rule that stops the phone resolving `localhost`. Blocking
  a whole TLD would mean moving the check into `RuleParser` and accepting that blast radius.
- **Never hold a whole blocklist as text.** Lists reach a quarter of a million domains;
  they are streamed line by line into `DomainIndex.Builder` and kept as a sorted `LongArray`.

### The blocklist catalogue
- **Lists are fetched from AdGuard's Hostlists Registry, not from the project that writes them.**
  Learned the hard way: the catalogue shipped two HaGeZi lists pointing at `github.com/hagezi`,
  that account disappeared, and both became a silent 404 for everyone who had subscribed — the
  error is visible on the Lists screen, which is not the same as anybody seeing it. The registry
  is a curated mirror that AdGuard's own DNS clients read, so a list outlives its publisher and
  arrives normalised. Only the four lists the registry does not carry keep a direct URL.
- **Adding a source means measuring it with the parser's own rules, not with a grep.**
  `approximateEntries` is the count of lines that survive `RuleParser`, because that is what the
  list is worth at DNS level. A browser filter can be 40,000 lines and yield 15 usable rules; one
  did, and was dropped. `adguard-popups` shipped anyway and could never work: every line in it is
  `$dnsrewrite=`, which the parser declines to approximate, so it downloaded and parsed to
  nothing forever. The measurement had counted lines that *look* like rules without modelling the
  `$`-modifier check.
- **A list is credible relative to what it had, not against a fixed floor.** The floor was wrong
  in both directions: a regional list with thirty-odd entries failed every refresh after its
  first, forever, while a list collapsing from a quarter of a million entries to two hundred
  sailed through. `BlocklistStore.collapsed` refuses nothing-at-all always, and otherwise a fall
  of more than half.
- **Two axes, and they are independent: what a list blocks, and what it will cost you.**
  `BlocklistCategory` is the first, `BreakageRisk` the second. The safest and the most dangerous
  lists in the catalogue are both ad blocklists, so a category sorted by size alone recommends
  the worst thing in it first. Categories mirror what a DNS blocker can act on — AdGuard's
  "social" and most of its "annoyances" are cosmetic rules and would be empty here.
- **Growing the catalogue must never grow what a fresh install downloads.** A source nobody has
  touched falls back to its own default, so adding forty lists adds no bytes and no memory to an
  install that exists. `BlocklistCatalogTest` fails if anything but the two conservative ones is
  on by default, or if anything not marked `SAFE` is.
- **List ids are persisted in `listChoices` and are load-bearing.** Renaming one silently orphans
  somebody's decision: the list reverts to its default and they are never told. The ten ids that
  have shipped are pinned by a test.

### Platform facts learned the hard way (do not re-derive)
- **The descriptor from `establish()` is non-blocking.** A stream-shaped read loop therefore
  gets 0 back immediately whenever no packet is waiting and spins a core flat out — measured at
  97% of one core on an idle phone with the screen off, which is a battery bug that no amount of
  profiling elsewhere will explain. The read loop waits in `Os.poll()` and is woken for shutdown
  by a self-pipe, because `poll()` is not interruptible and closing the tun does not wake it.
  Never replace this with `FileInputStream.read`.
- **`Settings.Secure.always_on_vpn_app` is not readable by a normal app** on a current Android;
  it returns null whatever is configured. Treat "who holds always-on" as genuinely unknown
  (`VpnController.AlwaysOn.Unknown`) rather than inferring "nobody does" — the latter tells
  every user who already configured it that they haven't, forever.
- **Whether *some* VPN is active is observable**, via a network with `TRANSPORT_VPN`. That is
  what distinguishes "the user dismissed the dialog" from "another VPN holds the tunnel".
- **Private DNS *automatic* does not defeat this filter; only a named resolver does.** Measured on
  a device by probing three unique domains per mode and reading the query log: off 3/3 seen,
  automatic 3/3 seen, strict (a hostname) 0/3. Automatic is opportunistic — Android encrypts only
  when the resolver on that network offers DNS-over-TLS, and the resolver this tunnel advertises
  is a sentinel that answers UDP 53 and nothing else, so the probe fails and the system falls back
  to plain DNS, straight into the filter. `dumpsys connectivity` shows why: the VPN network is its
  own network with its own `DnsAddresses: [10.111.222.2]`, so private DNS is evaluated against
  *that* resolver, not the Wi-Fi one.
- **`LinkProperties.isPrivateDnsActive` on the underlying network is therefore the wrong thing to
  alarm on**, and alarming on it told nearly every user that nothing was being filtered while
  everything was — automatic is Android's default, and the flag is true whenever the *carrier or
  Wi-Fi* resolver happens to support DoT, which says nothing about queries inside the tunnel.
  `privateDnsServerName != null` is the fatal case; that one gets the red card and a button.
  This was reported by a user asking why AdGuard never said any such thing.
- **`android.settings.PRIVATE_DNS_SETTINGS` is not in the SDK and does not resolve on a current
  AOSP build** — checked, not assumed. `ACTION_WIRELESS_SETTINGS` opens the network dashboard,
  which carries the Private DNS entry, and is the fallback.
- **`Network.bindSocket` is how a query leaves by the network its resolver came from — the 0.9.2
  failure was the *reference*, not the call.** That version pinned every upstream socket to a
  `Network` held in a field only the default-network callback updated, and that callback goes
  quiet the moment the tunnel is up (see below), so it was pinning sockets to a network that had
  very likely ceased to exist — one of the things `EPERM` means. The whole idea was reverted in
  0.9.4 on the strength of the symptom, and the symptom is worth knowing: a resolver "not
  answering" in one millisecond where it answered in eighty a second earlier, and four resolvers
  exhausted in three milliseconds. It also does not fail cleanly, so a socket whose pin was
  refused is thrown away rather than reused.
  RethinkDNS pins its DNS sockets exactly this way — `protect()` first, then `bindSocket` to the
  network that offered *that* resolver — which is what suggested the reference was at fault.
  0.9.10 pins again, against a reference kept current by the non-VPN callback, best-effort: three
  refusals in a row turn it off for the life of the service, and the diagnostics header says
  which happened. **If it is ever reverted again, say in the log which of the two it was.**
- **A per-socket failure must never be logged with its throwable.** On a phone that cannot reach
  its resolvers it is one fifteen-line stack per lookup; the log filled with identical traces and
  evicted the lines that explained what was happening — diagnostics defeated by their own noise.
  Class and message, rate-limited.
- **The debug log names the resolvers a network handed out.** With the default upstream setting
  the log used to say `upstream=system`, which answers none of "it does not resolve on this
  Wi-Fi". It now records `network wlan0: dns=[…]` on every adoption.
- **A network's first DNS server is not necessarily one that answers, and Android hides that.**
  Routers advertise two or three resolvers, and the first is routinely a dud — it advertises
  itself and then filters, or answers on the LAN and nowhere else. Android's own resolver tries
  them all and remembers which replied, so with the filter *off* the network looks perfectly
  healthy. Asking one and dropping the lookup made a whole Wi-Fi resolve nothing while mobile
  data was fine; the query log showed every domain asked for a dozen times, all "allowed",
  because the verdict was right and the answer never came back. `forward` now tries each
  resolver, splits the one timeout budget between them so a silent one cannot starve the rest,
  and remembers the winner so the dud costs its timeout once rather than on every lookup.
  Reproduced on a device with `192.0.2.1` (TEST-NET-1) listed first: 3.7s for the first lookup,
  then 0.9s and 0.6s.
- **A VPN is metered unless it says otherwise, and that belief spreads to the whole phone.**
  Without `Builder.setMetered(false)` the tunnel's capabilities come back without `NOT_METERED`
  while the Wi-Fi underneath it has it — measured with `dumpsys connectivity`, before and after.
  Everything that reads meteredness then acts on it: Play Store holds automatic updates, cloud
  and photo backups stop, Data Saver restricts background data, streaming apps drop quality.
  RethinkDNS carries the same fix with the comment "cloud backups were failing thinking that the
  VPN connection is metered". It also broke Malachi from the inside — `listUpdateWifiOnly`
  defaults to **true**, which WorkManager expresses as `NetworkType.UNMETERED`, a constraint the
  tunnel itself made permanently unsatisfiable, so the periodic blocklist refresh never ran again
  once filtering was switched on. `false` is not a lie: with the underlying networks declared,
  the platform derives meteredness from what is actually underneath.
- **"Block connections without VPN" (lockdown) leaves this phone with no connection at all.**
  Lockdown drops anything that does not leave through the tunnel, and this tunnel routes two
  sentinel addresses and nothing else. Verified on a device: with it on, `nc -z 1.1.1.1 443`
  returns `connect: Permission denied` — a raw TCP connect to a literal IP, no DNS involved —
  and `dumpsys connectivity` shows "Lockdown filtering rules". It matters more here than
  elsewhere because Malachi *asks* to be made always-on and that switch sits directly beneath
  this one. `VpnService.isLockdownEnabled` is readable (unlike always-on), so the home screen
  detects it and says so, and the always-on tip warns against it.
- **`VpnStatus.up()` builds a fresh status, which is how a stale problem is cleared — and how a
  lockdown warning was erased a moment after being set.** Anything that describes the *platform*
  rather than this tunnel attempt has to be carried across it explicitly.
- **`Builder.setConfigureIntent` is what puts the settings button in Android's own VPN dialog.**
  Without it the platform simply omits the control, so Malachi's entry in Settings → VPN had a
  gear that led nowhere.
- **`Os.pipe2` is not in the SDK; `Os.pipe()` is.**
- **Once the tunnel is up, the default network reported to this app can be the tunnel itself, and
  discarding that silently cost a phone eleven hours of DNS.** `adoptNetwork` guarded against
  adopting a VPN — correctly, forwarding into our own tun is a loop — with a bare `return` and no
  log. The last adoption in the trace was one second before `tunnel up`; after that every network
  change was dropped on the floor, and the filter spent the night asking a mobile network's four
  resolvers over a Wi-Fi that routed to none of them. The signature: IPv4 resolvers timing out at
  their full budget while IPv6 ones fail in **0 ms** (`ENETUNREACH` — the network handing out
  those v6 addresses is not the network you are on), and the app's own updater downloading 45 MB
  happily throughout, because *it* resolves outside the tunnel. A VPN answer is now resolved one
  step down to the network underneath, and anything that cannot be resolved is logged.
- **Once the tunnel is up, the default-network callback never mentions the network underneath it
  again — measured on a phone, not inferred.** Twenty minutes of walking around a house on 0.9.7:
  three handovers, three correct adoptions, and *every one of them* preceded by `no resolver
  answered and this network offers others` — the emergency re-read. The only adoption the callback
  ever produced was one second before `tunnel up`. For this app the default network becomes the
  tunnel, and the tunnel's `Network` does not change when what is under it does. So a **second**
  callback asks the question the first stops answering: a `NetworkRequest` with
  `NET_CAPABILITY_NOT_VPN`, and on API 31+ `registerBestMatchingNetworkCallback`, which names the
  platform's own best match — better than any ranking of ours, because it is the same choice our
  protected sockets are about to follow. Below 31 every match reports itself, so those events are
  only a prompt to go and decide again; adopting the last one to speak is the 0.9.0 bug.
- **"The Wi-Fi connects and the phone won't use it" is Android's, not ours, and toggling a VPN is
  what un-sticks it.** Reported from beside the router: with the filter off the phone stayed on
  5G and marked the Wi-Fi *low quality*; switching the filter **on** made it join. The mark is
  Android's avoid-bad-wifi state, it is sticky, and it is re-evaluated on events — establishing
  or tearing down a VPN is one. With the filter off this app is not running at all, so it cannot
  be the cause; what it can do is *say so*, which `noteUnvalidated` now does. Do not reach for
  `reportNetworkConnectivity(network, false)` to force a re-check: it asserts a network is broken
  when we do not know that, and it can push the phone off a good one.
- **That request must carry `NET_CAPABILITY_VALIDATED`, and forgetting it is not cosmetic.** A
  Wi-Fi that has associated but reaches nothing still has `INTERNET`, so the platform will happily
  name it the best match — and the tunnel then adopts a router's resolvers and, since sockets are
  pinned, sends every lookup out of a network with no way out. That is precisely what walking back
  into range of a weak access point looks like. Android does not switch to such a network either;
  requiring `VALIDATED` is asking the question it asks. The network the platform names is
  re-checked at adoption too, because validation can be lost without another callback.
- **This is not only about latency: the resolvers and the route have to change together.** A
  protected socket leaves by the *system default route*, which we do not choose. While the resolver
  list belongs to one network and the route to another, the tunnel asks a LAN resolver
  (`100.90.1.1`) over mobile, or an ISP's mobile resolvers over Wi-Fi, and gets nothing either way.
  Both failures are in the same trace, an hour apart.
- **Read against RethinkDNS when the platform is the problem.** It solves the same problem on the
  same OS with years of field reports behind it (`ConnectionMonitor.kt`, `BraveVPNService.kt`;
  Apache-2.0, so patterns, never code). Three things were taken from it and one was deliberately
  left: it keeps a `Map<InetAddress, Network>` of every resolver to the network that offered it
  and pins DNS sockets to that network; it never uses `registerDefaultNetworkCallback`, only
  broad `registerNetworkCallback` requests it decides between itself; and it conflates a storm of
  callbacks into one settled event. **What was not taken is `setUnderlyingNetworks` with the whole
  ordered list** — the platform derives `NOT_METERED` from *all* of them, so listing cellular
  beside Wi-Fi makes the tunnel metered and brings back the bug fixed in 0.9.0. One network, the
  one in use. The conflation was skipped too: their handler probes several addresses per network
  over the network, and ours reads `LinkProperties` and closes some sockets, so a debounce would
  only delay the adoption it is meant to protect.
- **A stack trace in the log can be older than the bug you are reading it for.** The debug log
  survives updates on purpose, so a report can open with fifty `Network.bindSocket EPERM` traces
  from 0.9.3 and a fix that landed in 0.9.4. Read `installed=` before diagnosing anything.
- **`NetworkCapabilities.getUnderlyingNetworks()` is not in the public SDK** — checked against
  `android-35/android.jar`, not assumed. Which network is under the VPN has to be inferred:
  validated, non-VPN, has internet, ranked wire → Wi-Fi → mobile, which is the platform's own
  preference and therefore the same answer in every case that matters.
- **On a handover the new default is announced before the old network finishes going away, so
  `onLost` must check *which* network it lost.** It used to wipe the resolvers whichever one
  disappeared: walking out of Wi-Fi range threw away the mobile resolvers adopted one callback
  earlier, and the whole phone moved to the Cloudflare fallback silently. That is working DNS
  sent somewhere the user never chose — and on a network with names of its own, or one that
  blocks outside resolvers, it is not even working. Losing the active network now looks for its
  replacement immediately, and falling back is logged.
- **A phone that changes network often gets one dead HTTP request per change.** The pooled
  HTTP/2 connection from the last network answers `REFUSED_STREAM` or times out — seen twice in
  one night's log. The updater survives it by retrying; a blocklist download does not, and comes
  back as a red line on the Lists screen until the next refresh. The pool is evicted on adoption.
- **A lookup in flight when the network changes is already dead**, and the rest of its
  five-second budget is spent on resolvers that have gone — which is five seconds of delay
  before the client's retry can use the new ones. A generation counter, bumped on adoption, is
  checked between resolvers.
- **The bypass guard's routes are frozen at `establish()` and deliberately left that way.** On a
  new network the guard no longer covers *that* network's resolvers, and fixing it means
  rebuilding the tun on every handover — a blink of unfiltered DNS and a teardown each time,
  which on a phone that changes network every few minutes is far worse than the hole. The hole is
  narrow: the public resolvers an app actually hardcodes are static and still routed, and an app
  does not hardcode a resolver it could only have learned from the system it was avoiding.
- **The network callback is a fast path, not a guarantee, so the tunnel must be able to notice on
  its own.** When a lookup exhausts every resolver, the phone is re-asked what it has (at most
  once every five seconds, only on the path where everything already failed) and the answer is
  adopted if it differs. Without that floor, a callback that never arrives is indistinguishable
  from a network that is down — and lasts until something else happens to change.
- **`registerNetworkCallback(request, cb)` fires for *every* network that matches, not the one in
  use.** With Wi-Fi and mobile both up, the last network to speak wins, and Malachi would adopt
  the resolvers of a network its (protected, default-routed) upstream sockets never touch —
  every lookup then times out. `registerDefaultNetworkCallback()` is the question actually being
  asked; the app is always outside its own tunnel, so its default network is the real one.
- **`delay()` runs on a monotonic clock that stops while the device is suspended.** A fifteen
  minute pause is fifteen minutes *awake*, which overnight is hours. A wall-clock deadline needs a
  wall clock: the pause arms `AlarmManager.setAndAllowWhileIdle(RTC_WAKEUP, …)`, which needs no
  permission, survives Doze, and outlives the process — so a pause interrupted by a low-memory
  kill still ends on time. The `delay` is kept beside it because it is exact while the phone is
  awake; both do the same single write and the loser is a no-op.
- **The watchdog was *not* enough to end a pause the timer slept through, and this was reported
  from a phone.** Every reader of `isPaused()` uses the wall clock, so fifteen real minutes later
  the settings say the pause is over while the only thing that would end it is still counting.
  `filteringEnabled && !isPaused && !tunnelUp` is what the home screen paints as "starting the
  filter…", so the screen span forever over a filter nobody was starting: the settings flow had no
  reason to emit, and the other path was a 30-minute *deferrable* worker that Doze defers further.
  Two things follow. Anything with a wall-clock deadline gets an alarm, not a timer. And
  **`MainActivity.onResume` calls `vm.ensureFilterRunning()`** — somebody looking at that spinner
  is the strongest evidence there is that a filter is wanted now, and it is also a context the
  platform will let us start a service from. A screen that says something is happening must be
  the thing that makes it happen.
- **`VpnService.onRevoke`'s default implementation is `stopSelf()`.** Calling `super` after
  scheduling a retry destroys the service and `onDestroy` cancels the retry — the documented
  recovery from "another VPN took the tunnel and then let go" silently became "wait for the
  watchdog". Malachi does not call through.
- **A `ParcelFileDescriptor` closed while another thread is still using it is worse than a lost
  packet.** The number is free the moment it closes and the kernel reissues it to the next thing
  this process opens, so a `read` or `write` still in flight lands on an unrelated file or
  socket. `stopTunnel` locks the writers out, joins the read loop and only then closes.
- **DataStore without a `corruptionHandler` fails every read *and every write*, forever.**
  Catching the read side alone yields an app on its defaults that cannot save anything.
- **`stopSelf()` also swallows a start request that arrived while the stop was being decided.**
  Toggling the filter off and straight back on then ends with a dead service and a switch that
  says on. `stopSelf(startId)` is the one that declines to.
- **A `FileOutputStream` built on a `ParcelFileDescriptor`'s descriptor does not own it** — closing
  the stream leaves the descriptor open, and `pfd.close()` is what closes it. Verified on device
  in `TunnelDescriptorTest`, not remembered.
- **Anything a shutdown needs must exist before the thread that uses it.** The read loop's
  self-pipe used to be created inside the loop, so a tunnel stopped in the moment between
  `start()` and the loop's first instruction found no pipe to write to: the wake was lost, the
  join timed out, the descriptor was closed, and — since `poll()` does not wake when its
  descriptor closes — that thread stayed parked for the life of the process. Measured as one
  leaked read loop per on/off cycle.
- **ICMPv6 (next header 58) and MLD arrive on every tun there has ever been.** They are not a
  symptom, and a rate-limited log line about them is still a line a minute forever, which is a
  capped debug log spent entirely on the one message that never means anything.

### Battery rules for the tunnel (this is an always-on process)
- **No unconditional timers.** Anything periodic is gated on the screen being on, or it does not
  exist. The notification refresh parks on a screen-state flow rather than ticking.
- **Periodic work exists only while the filter is meant to be running.** The watchdog and the
  blocklist refresh used to be declared at every process start, so a phone with filtering
  *switched off* woke every half hour to read a setting that said no, and pulled twenty megabytes
  of lists a filter would never consult. Both are now applied from the settings flow and cancelled
  when filtering goes off; `BackgroundWorkTest` fails if that stops being true. The update check
  is the exception and stays unconditional — a phone that is not filtering today still has to be
  able to receive the fix that makes it filter tomorrow.
- **A process start is not a person.** The launch update check belongs to `MainActivity.onResume`,
  which knows somebody is there. Asking in `Application.onCreate` as well meant a network request
  every time the *system* revived the process — a worker, a broadcast, a Doze cycle — which is
  many times more often than anyone opens anything, and each one used to be able to drag another
  process start along behind it.
- **`onCapabilitiesChanged` fires several times a minute on a moving phone, and almost none of it
  concerns this app.** Signal strength and bandwidth estimates are capabilities. A capability
  change on the network *already in use* cannot change its resolvers — those arrive through
  `onLinkPropertiesChanged`, and a lost validation makes the network stop matching the request
  entirely (`onLost`). So that case is a reference comparison instead of the three or four binder
  round trips an adoption costs. The one thing it is still worth waking for is noticing lockdown,
  and that is capped at one binder call a minute.
- **Nothing on the hot path may allocate or IPC without earning it.** Attribution
  (`getConnectionOwnerUid`) is a binder round trip and is skipped entirely unless the query log
  is on or a per-app rule exists. Upstream sockets are pooled so `protect()` — another round
  trip — happens once per socket, not once per lookup.
- **The query log publishes nothing while nobody is watching** (`subscriptionCount == 0`), which
  means a screen that collects it and does not use it costs a snapshot twice a second on the hot
  path. The home screen did exactly that for a while.
- **The query log gives every app a quota** (`MAX_PER_APP`) inside its total ceiling. With one
  global limit the chattiest app evicts everybody else's history, and the per-app screen reads as
  broken for anything quiet.
  Counters stay as plain longs so the notification can read them without building a snapshot.
- **A blocked lookup never leaves the read loop**: no thread hand-off, no coroutine, no copy of
  the packet.
- Thread pools use `allowCoreThreadTimeOut(true)`; a phone that is not resolving anything must
  hold no worker threads at all.

### Apps that a VPN breaks
- **`allowBypass()` is why Android Auto works.** Without it Android's rule is absolute —
  "applications cannot bypass the VPN" — and an app that binds a socket to a particular network
  is refused, whatever the tunnel actually routes. Android Auto has to reach the head unit over
  the link it is plugged into, so it fails before it starts and reports communication error 21
  blaming "a VPN" without naming one. This was diagnosed from a user pointing out that AdGuard
  in always-on mode never did it: the route table is not the difference, this call is.
  What it costs is narrow and real — an app that deliberately binds to the underlying network
  resolves through that network's resolver and is not filtered. Ordinary apps, and the ad SDKs
  inside them, ask the system resolver and are unaffected; the bypass guard still catches a
  hardcoded `8.8.8.8`, because hardcoding a resolver and binding to a network are different
  things and trackers do the first.
- **Android Auto is filtered like everything else.** It was briefly exempted from the tunnel as
  belt and braces; the car it was reported from then confirmed it works filtered, so
  `MalachiSettings.migrated` withdraws that exclusion. `allowBypass()` was the whole fix.
- **There is no list of which apps used the bypass, and there cannot be one.** An app that
  bypasses never reaches this process; that is what bypassing means. `allowBypass()` is also a
  property of the whole tunnel, not something granted per app — the only per-app lever Android
  offers is `addDisallowedApplication`, which puts an app outside the tunnel entirely. Anything
  claiming to name the bypassers would be inferred from `NetworkStatsManager` behind a
  usage-access permission, which is a large ask for a guess. `bypassAllowed` is a setting
  instead: on by default, and off is the strictest this filter can be.
- **A default value would not have reached anybody.** An install that already exists has its
  exclusions stored as an explicit list, so this is a one-time migration behind a flag rather
  than a change to a field's default — and it is applied once, so somebody who decides they
  would rather filter their car than use it is not undone at the next launch.
- **The list stays one entry long.** Google Play Services would fix more things and quietly stop
  filtering most of what this app exists to filter. Growing it is a decision, not a drift.

### Diagnosing a report from a phone
- **`DebugLog.trace` is memory-only, and that is the whole design.** Traces name domains, and the
  standing rule is that a domain never touches disk — so `trace` skips the file *and* Logcat and
  is readable only in the app that recorded it, until the process ends. Verified after building
  it: zero traced domains in `debug-log.txt`. Never route these through `i`/`w`/`e`.
- **The window expires by itself** (`diagnosticsUntilMs`, 15 minutes). A switch would be left on
  for months on the one path that costs something per lookup; a deadline cannot be.
- **What a trace has to contain to be worth asking for**: the environment first — app and Android
  version, the upstream *setting*, the resolvers actually in use, what the network handed out,
  Private DNS, scope, bypass guard, lockdown — and then per lookup, which resolver answered, how
  long it took, or that none did. Without the header the rest is unreadable at a distance.
- **The traces have their own quota in the buffer, and without it the window was self-defeating.**
  `trace` and `i`/`w`/`e` shared one 500-line ceiling, and they are not evenly matched: one line
  per lookup against one line per event. A quarter of an hour of diagnostics pushed every network
  adoption, every resolver change, every pin out of the text the copy button produces — so
  turning the window on to investigate something destroyed the record of what led to it, and the
  standing advice became "don't turn it on". Same mistake as one global ceiling in the query log,
  same fix: `MAX_TRACES` inside `MAX_ENTRIES`. **Ask for the window whenever the question is about
  a lookup; the per-event history survives it now.**
- **While the window is open, drops are not rate-limited.** One line a minute hides the pattern
  being hunted — a client retrying over TCP, which this tunnel routes and cannot answer.

### Diagnosing one app that hangs (`AppTrace`)

- **The query log is a set; this is a sequence, and the difference is the whole feature.**
  `QueryLog` holds one row per (app, domain) with the latest verdict and a count, which is right
  for "what has this app been resolving" and useless for "which name is it hanging on": order is
  lost, and the domain that broke something was asked for once and sits under forty the app
  repeats all day. `AppTrace` is one row per *query*, in order, for one app.
- **It counts queries and says so.** The opposite of `LookupBursts`, deliberately: an `A` and an
  `AAAA` are two exchanges with two fates, and "the `A` answered in 40 ms and the `AAAA` never
  came back" is a real and common way to hang an app. The row names the record type so the
  number cannot be misread as lookups — do not "fix" this by collapsing bursts.
- **A block never hangs anything; an unanswered lookup does.** Failing open means an allowed
  lookup can leave a client waiting out its own timeout, and from outside that is
  indistinguishable from a refusal while the remedy is the opposite. Nothing recorded that until
  `TraceOutcome.UNANSWERED`, and the screen's three counters exist to tell the two investigations
  apart before a single row is read.
- **The elapsed time is measured from the start of the lookup, not of the attempt that won.** A
  name that took four seconds because the first DNS server was silent is a name the app waited
  four seconds for; reporting the 80 ms the second one took hides the delay being hunted.
- **The window is a deadline, and the evidence outlives it.** Thirty minutes rather than fifteen,
  because the errand is iterative — use the app, come back, except a name, use it again — and
  it is re-armed with one tap. What it caught is *not* cleared when it lapses; the setting is,
  which is what puts attribution back the way it was. Expiry is enforced per lookup off the wall
  clock, so a phone that slept through the tidy-up job has still stopped recording.
- **Exempting is trial and error, so the control is a switch, not a dialog.** A dialog per attempt
  turns a two-minute experiment into a chore people abandon — and what they do instead is put the
  whole app outside the filter, permanently. "Allow all of them" answers the first question ("is
  it Malachi at all?") in one tap; the switches then bisect it.
- **The user's own edits are written into the timeline**, from whichever screen they were made,
  which is what turns a wall of events into an experiment log.

### Backup (the one thing that cannot be rebuilt)

- **What a backup carries is decisions, never observations.** The rules somebody wrote, the lists
  they chose, the apps they excluded, where lookups go. Never the query log, never the statistics,
  never a domain this phone was seen asking for — a file the user hands to a cloud drive is the
  last place an observation should surface. A rule someone typed is theirs and is already on this
  disk; a name their phone looked up is not the same thing.
- **Restoring must not paste one phone's state onto another.** `filteringEnabled`, the pause, the
  diagnostics window, which notices have been dismissed: none of it is in the file, because
  restoring a paused state onto a working phone stops the filter for fifteen minutes with nothing
  on screen to explain why.
- **Reading a file from another version works in both directions, and it is bought with habits
  rather than cleverness.** Every field has a default, so an older file is missing nothing;
  unknown keys are ignored, so a newer file is merely partly understood; and **a field is never
  renamed or repurposed** — new meaning, new field. `Backup.FORMAT` moves only for a change those
  three cannot absorb. A file from a newer app is read rather than refused: telling somebody
  their own backup is unreadable because they downgraded is the wrong answer.
- **The reminder follows the work, not the calendar.** `decisionsFingerprint` over the rules and
  the list choices is compared with what the last export covered — which beats instrumenting
  every place a rule can change, because the place that gets forgotten is a reminder that never
  fires and a backup somebody thought they had. It also means a fresh install with nothing to
  lose is never asked, and **once a copy exists nothing is said again until the decisions
  actually change**. Deliberately narrower than what the file carries: nagging because somebody
  changed the theme is how a reminder gets switched off for good.
- **No notification for it, ever.** This app's notifications are for a pause, a stopped filter,
  and the transient one the platform demands. A backup is not an interruption; it is a card on
  the home screen with a way out that lasts (`backupRemindersOff`, reversible in settings).
- **The file goes where the user says, through the storage access framework.** No storage
  permission, and nothing of theirs is visible to us. A backup this app filed away in its own
  folder would be lost with the app, which is the one moment it exists for.
- **`openOutputStream(uri, "wt")` — the `t` is load-bearing.** Without it the provider opens for
  writing without truncating, so a shorter backup written over a longer one leaves the tail of
  the old file behind and the result parses as nothing. There is an instrumented test for exactly
  this, because it is discovered on the day it is needed and not before.

### Writing for somebody who is not you

- **The word is "DNS server", never "resolver".** Precision is not lost by using the name people
  have already seen in a router's settings; it is lost by using the one they have not. The same
  goes for "hostname" (say the domain), and for "the tunnel" in anything user-facing (say Malachi,
  or the filter). "VPN" stays, because Android says it and the user will read it in a system
  dialog either way.
- **The first run has to answer the question the system is about to provoke.** Android's consent
  dialog says this app "can monitor all network traffic" — a sentence written for a VPN that
  carries a phone's whole life to somebody else's server — and somebody meeting it with no context
  has been handed every reason to uninstall. `WelcomeScreen` says, one screen earlier: what it
  does, why Android will mention a VPN, and where the data goes (nowhere). It is shown *instead*
  of the app rather than over it; a dialog on top of a screen full of controls reads as an
  obstacle and is dismissed unread.
- **Restoring is the only irreversible thing in this app, so it is the only thing that asks.** And
  it asks with numbers on both sides — what the file holds against what is on the phone — because
  picking the wrong file from a list of filenames is an ordinary mistake and "0 rules" is the only
  moment anybody would catch it.

### Privacy constraints (non-negotiable)
- **A domain never touches disk.** The query log lives in memory only and dies with the process.
  The statistics persist *counts* — per app, per day — and must never gain a hostname field, a
  "recent domains" cache, or anything else from which browsing could be reconstructed.
- Nothing is ever sent anywhere. The only outbound requests this app makes are: the blocklists
  it downloads, `version.json`, and its own APK.

### Storage rules (this app runs for months without being opened)
- **Every file Malachi writes has a bound, enforced in code, not by habit.** Blocklists are
  pruned against the subscribed set (`BlocklistStore.prune`, which takes the *whole* set — it
  used to be folded into `refresh` and deleted every already-downloaded list whenever one new
  list was fetched). The debug log is capped by bytes and trimmed to half the cap so it isn't
  rewritten on every append. Statistics keep `RETAINED_DAYS` of detail with per-day and
  all-time app tables capped. A downloaded APK is deleted once stale.
- **The directory is the authority on what is on disk, not the file that lists it.** `prune`
  sweeps the list directory itself, so losing `state.json` cannot strand a compiled index that
  nothing will ever delete again.
- **`prune` and `refresh` take the same lock.** They are reached on independent schedules — the
  periodic refresh prunes *after* fetching, enabling a list prunes *before* — so they overlap in
  practice. Unserialized, the sweep deletes the `.tmp` that `writeIndex` is mid-rename on, and a
  sweep holding a slightly stale subscribed set deletes an index another thread has just written.
  Both end as a list whose state says downloaded and whose index is gone: a filter that reads as
  on and blocks nothing.
- **Write to a sibling, `fsync`, then rename.** The rename is only atomic with respect to this
  process; without the sync a power cut can leave the final name pointing at unwritten zeroes.
- Adding anything that writes to disk means adding its bound in the same change.

### Recovery, as measured (not as assumed)

Verified on an emulator; re-verify if the start paths change.

| What happens | Does the filter come back | How |
| --- | --- | --- |
| `kill -9` (low-memory kill) | yes | not by START_STICKY — that was tested and does not fire. The next thing that revives the process runs `FilterWatchdogWorker.restoreIfNeeded` from `Application.onCreate`; the periodic job is the floor |
| Reboot | yes, after up to ~3 minutes | `BootReceiver`. `BOOT_COMPLETED` is delivered in batches and is not prompt; that gap is unfiltered DNS |
| App update | yes | `MY_PACKAGE_REPLACED`, same receiver |
| Another VPN takes the tunnel | yes, when it lets go | `onRevoke` schedules a backoff retry, and does **not** call `super` — that is `stopSelf()`, which would destroy the service and cancel the retry it just scheduled |
| Force-stop (user or vendor battery manager) | **no** | Android puts the package in the stopped state: no broadcasts, no jobs, until somebody launches the app. Nothing in an app can defeat this. Always-on VPN is the only answer, which is why the app asks |

Two rules follow. Anything that starts the service must go through `VpnController.start`, which tries
a plain start and falls back to a transient foreground one — a background caller is refused
otherwise. And `Application.onCreate` must stay the place where recovery is noticed, because it is
the one path every revival has in common.

### Notifications
- **There is no ongoing notification while filtering**, and adding one back is a regression.
  Android's own VPN key is the indicator. Notifications exist only for: a pause (which is also
  what keeps the service alive with no tunnel), a stopped filter that needs the user, and the
  transient one the platform demands when the watchdog has to start the service from the
  background — withdrawn immediately by `demote()`.
- Removing the foreground service cost automatic recovery from a hard process kill: START_STICKY
  did not bring it back in testing. `FilterWatchdogWorker` covers that, and always-on VPN covers
  it properly, which is why the app asks for it.

### Distribution & releases
- GitHub remote: `https://github.com/olemoudi/malachi.git`.
- This is a sideloaded personal app (not Play Store).
- **Release signing uses a stable, committed keystore** (`malachi-release.jks`, password
  `malachi`) so in-place auto-updates chain across releases. CI can override with
  `SIGNING_STORE_FILE` / `SIGNING_STORE_PASSWORD` / `SIGNING_KEY_ALIAS` / `SIGNING_KEY_PASSWORD`.
  **Never re-sign with a different key** — it breaks the update chain and forces a reinstall.
- Releases are published by GitHub Actions on pushing a tag matching `v*`. The workflow runs
  `assembleRelease` and attaches two assets with **stable names**: `malachi.apk` and
  `version.json`.
- The canonical asset name carries **no release stage** on purpose: it is the URL baked into
  every install link and every installed copy, so it has to outlive every stage. The stage
  lives in `versionName` (e.g. `0.1.0-beta`), which nothing parses — only `versionCode` drives
  updates.
- **Bumping a version:** raise `versionCode` (and `versionName`) in `app/build.gradle.kts`,
  then push a `v*` tag. CI derives `version.json` from `versionCode`.
- **The badge push races the release, and it used to report a green build as a failure.** CI
  checks out a commit, spends four minutes on it, and then pushes the coverage badge — by which
  time `chore: cut vX.Y.Z` has landed and the push is rejected as a non-fast-forward. Tests,
  coverage and APK all passed; the run went red and mailed a failure with nothing in it to act
  on, on every release for two days. The step now rebases and retries, and carries
  `continue-on-error` so a picture can never fail a build. **A red CI on a release commit is
  worth reading before believing** — check which step failed.
- **The second way a green commit gets mailed out as a failure is Maven Central saying no.** It
  rate-limits CI runners: on 2026-08-13 every single dependency came back `429 Too Many Requests`,
  so Gradle resolved nothing, compiled nothing, and ran none of the 507 tests — and the failure
  reads like eighteen unrelated broken dependencies unless you notice the status code. The tell
  that it is transient and not ours: the Release workflow resolved the same dependencies a minute
  later and went green. Every Gradle invocation in CI now goes through `scripts/gradle_retry.sh`,
  which retries with a widening gap **only** when the log carries a resolution failure — a red
  test is still reported on the first attempt, because retrying that would treble the time to a
  real red and quietly turn a flaky test green. `cache: gradle` on `setup-java` is the other half:
  most runs now ask Maven Central for nothing at all. **Before diagnosing a CI failure, check
  whether it got as far as running a test** — `Could not resolve` is not a broken build.
- **Never tag the coverage-badge commit.** CI pushes `chore: update coverage badge [skip ci]`
  after every green run, and GitHub honours `[skip ci]` for *every* event on that commit — so a
  tag that lands on it produces no Release run at all, silently: no failure, no run, nothing to
  look at. Cut the tag on a commit of your own (an empty `chore: cut vX.Y.Z` will do) and check
  that the Release workflow actually started.
- **Never mark a release as a pre-release on GitHub**, however alpha the build is. The whole
  distribution model hangs off `…/releases/latest/download/…`, and that path skips
  pre-releases: marking one would 404 the install QR in the README *and* the `version.json`
  every installed copy polls, silently freezing the fleet on whatever build it was running.
  The stage belongs in `versionName`, which nothing parses.

### Auto-update
- `UpdateWorker` (periodic, plus on launch and on regaining focus) runs `Updater`, which reads
  `version.json`, compares `versionCode`, downloads the APK and installs it via
  `PackageInstaller` with `USER_ACTION_NOT_REQUIRED`. When the system insists on confirmation,
  `InstallReceiver` posts a tappable notification — don't remove that path, it is what makes
  a background check reliable.
- `BootReceiver` handles `MY_PACKAGE_REPLACED` as well as `BOOT_COMPLETED`. A self-update
  replaces the process, and without it every update would silently leave the filter off.

### The updater is the one thing that cannot be fixed remotely
- **An unhandled throw in `Updater` does not cost one update, it costs every future one.** There
  is no store to push a fix through, so the whole check is wrapped: anything unexpected becomes a
  logged `TRANSIENT_FAILURE`, never an exception crossing into a worker. `CancellationException`
  is rethrown — swallowing it leaves a coroutine ignoring its own scope.
- **One network attempt is a coin toss, not a check.** The fetch and the download each get three
  goes with a widening gap. This was reported from a phone: a single blip, and the screen said
  the update had failed.
- **A 200 is not evidence that what arrived is our APK.** A captive portal answers everything
  with a login page and the right status code. The downloaded file is parsed by the platform
  (`getPackageArchiveInfo`) and refused unless it is a readable APK, with our package name, and a
  version code strictly newer than the installed one — which catches the portal, the truncated
  body and the stale CDN copy without a check each. Downloads land on a `.part` and are renamed.
- **Never report `UP_TO_DATE` without having looked.** Being busy or on a metered connection
  reports `NOT_ATTEMPTED`; both used to claim the app was current having fetched nothing.
- **Instrumented tests need cleartext to loopback, which Android refuses by default.** The
  MockWebServer-backed tests stand a server on the device, and without `src/debug`'s network
  security config every request dies with `CLEARTEXT communication to localhost not permitted`
  and the tests fail identically whatever the code does. It is debug-only: the released APK
  carries no network security config and keeps the platform default.

### Config migrations (must stay transparent)
- Settings are one JSON blob in DataStore. New fields get defaults and the decoder uses
  `ignoreUnknownKeys`, so additive changes need no migration. For a *non-additive* change
  (renaming or repurposing a field), migrate the old JSON in `SettingsStore.decode` — never
  break an existing install.
- **Undoing something a past version wrote into a user's settings does need one**, and it goes in
  `MalachiSettings.migrated` behind `settingsVersion` — not a boolean per correction, which
  accumulates in the stored blob forever. A field default only ever reaches a fresh install.
- **DataStore skips the write when the value is unchanged**, so a migration that returns the
  settings untouched leaves no trace on disk. That is correct, and it means "the file did not
  change" is not on its own evidence that the migration failed to run.

### Testing
- Run `./gradlew test` before cutting a release; `./gradlew jacocoAggregatedReport` refreshes
  the coverage number CI badges.
- **Long-horizon behaviour is simulated, never waited for.** Every clock the storage layer reads
  is a parameter (`record(nowMs = …)`), and the coroutine tests use `runTest`'s virtual time, so
  a year of statistics, a month of failed retries and a day of backoff all run in milliseconds.
  `SoakTest` is where that lives; a test that sleeps is a test nobody runs.
- **Measure a duration with `System.nanoTime`, never `measureTimeMillis`.** The latter is
  `currentTimeMillis`, which is a wall clock, and a wall clock steps: the fix for the test below
  failed on the very next run with a duration of **minus 1599 milliseconds**, because the machine
  corrected its time by NTP while the operation being measured was blocked.
- **A concurrency test that timestamps *after* the call is timing the scheduler, not the lock.**
  `BlocklistRefreshTest` recorded when each coroutine finished and asserted an order; but the
  waiting one is resumed the instant the lock is released, so on a loaded machine it can stamp
  itself before the holder is scheduled for its next line. Green here for weeks, red on CI, and
  it failed a release. Measure the *duration of the operation that should have blocked* instead —
  a directory sweep is a millisecond, so hundreds of them can only be the lock, and a slower
  machine widens that margin rather than narrowing it.
- **Instrumented tests need VPN consent, which no test can grant itself:**
  `adb shell appops set dev.malachi ACTIVATE_VPN allow`. Without it the tunnel cases skip
  themselves rather than fail, so a run that looks green may have exercised nothing. CI passes
  `-Pandroid.testInstrumentationRunnerArguments.requireVpnConsent=true`, which turns that skip
  into a failure — a grant that silently stops working must not read as a pass.
- **They run in their own workflow (`instrumented.yml`), on an emulator, and `release.yml`
  calls it before publishing.** They did not gate the release once, and a build whose tunnel
  tests were red installed itself onto a phone that updates automatically. A few minutes on
  every release is the cheaper mistake.
- **The instrumented fixture turns the blocklists off.** A fresh install fetches twenty
  megabytes of them in the background and an emulator busy doing that makes every timing
  assertion a coin toss; the tunnel needs no rule at all to establish.
- **The image is AOSP (`target: default`), not `google_apis`.** Play Services holds locks for
  twenty-five and forty-two seconds at a stretch on a freshly booted Play image, which is long
  enough to time out a test that is waiting on a VPN. Malachi uses nothing from GMS.
- Avoid Android dependencies in anything testable. Logic that needs a device (the tunnel, the
  UI) stays thin and delegates to something that doesn't.
