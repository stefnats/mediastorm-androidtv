# Native release reporting

Run `./gradlew :core:testDebugUnitTest :app:testDebugUnitTest :mobile:testDebugUnitTest`.
Build TV and phone test packages with `./gradlew :app:assembleDebug :mobile:assembleDebug`.
No server account or `secrets.properties` is needed. The unit fixtures use synthetic
prequeue IDs, releases and a replaceable `ReleaseReportGateway`.

`ReleaseReportsTest` checks explicit adoption after preparation, resume position at
the time of adoption, wrong-content reset, five language intents, report-only and
undo, transient manual retry, duplicate submission, cancellation during POST,
profile changes, failed and timed-out alternatives, affected track validation,
older servers, preservation of subtitle preferences, wire-model decoding and the
shared remote/touch menu choices.

This client requires the backend release-report endpoints. Playback without a
server prequeue binding (manual direct sources, downloads, trailers, live TV and
direct library playback) does not expose an unverified report action. Old servers
show an unavailable message instead of falling back to a global blacklist write.

Preparation selects and probes a source on the server. It does not buffer a second
video or analyze audio samples. Technical playback-error suggestions are handled
separately by `AudioRecoveryPolicy`. Adoption opens the prepared source through
the existing player coordinator and preserves the server's track descriptors and
prequeue binding. The default resume choice is ten seconds back; wrong content
always starts at zero. Different cuts are not assumed to be aligned.

Physical TV remote focus, touch layout, decoder playback, seek accuracy and backend
integration still require device acceptance. Automated unit tests and APK builds
alone do not establish those properties. Prior saved progress for incorrectly
identified content is not repaired by this change.

## Technical audio recovery suggestions

`AudioRecoveryTest` checks fatal audio output errors and audio-renderer decoder
errors after the existing local fallback paths. Video, network and container errors
are ignored. Missing-track hints require server-expected audio, selected video,
actual playback progress and ten seconds without any audio tracks. Pause, zero
player volume, disabled audio, startup, seek jumps and clock resets do not establish
a missing-track defect. Presence of a silent track is not analyzed.

The existing Auto Switch Sources setting gates automatic preparation. There is one
automatic suggestion per playback and no further automatic audio preparation after
adopting that suggestion. The UI offers review and dismissal; it does not switch by
itself or save a report. Both manual and automatic menus share the prepared-source
flow, so opening the menu cannot duplicate source preparation. A deliberate audio
selection, another manual source, Stop or a profile/context change discards the
suggestion and releases its unused preparation.

The policy and gateway tests are synthetic. Device error delivery, TV focus, phone
layout, real decoder recovery and backend/provider integration need physical-device
acceptance. No empirical content-audio defect classification is claimed.

Explicit audio intent from the details/manual track panel also waits for its save
before automatic preparation. Unknown tags and subtitle-only changes leave audio
intent unchanged. The single automatic-attempt budget follows a source handoff
within the same episode; the next episode starts its own budget.

## Optional spoken-audio language check

The shared TV and phone report menu offers Check spoken audio language only when
its current server advertises audioLanguageCheckAvailable. Older or unconfigured
servers default to false. The user explicitly starts each check; loading details,
opening the report menu or an uncertain silence signal does not start sampling.

The request retains the original profile, preparation source version and actual
selected container audio track, starting at the current playback position. If the
player cannot safely map its selected track, it does not guess a track or sample
another one. The fixed POST has a 100-second budget and never refreshes credentials,
reconnects, redirects or replays automatically. Closing the menu, changing audio,
changing sources or retiring the original profile cancels or rejects its reply.

The configured backend checks two short samples. Match and mismatch are tentative
hints, and insufficient, conflicting or malformed evidence stays unknown. No
transcript is exposed or saved by the client. No report, permanent exclusion,
history correction, source switch or playback grant follows automatically; the
existing report and alternative-source controls remain explicit actions. Existing
authorization denials retain their normal security handling.

SpokenAudioCheckTest and SpokenAudioTransportTest cover the workflow and synthetic
loopback transport. They do not establish language-model accuracy, model setup,
real-provider behavior, physical-device playback or lip-sync correctness. The
client does not install a speech model or edit the server analysis endpoint.


Optional automatic spoken language hints are a separate profile/server preference,
`playback.autoCheckAudioLanguage`, disabled on old servers and by default. TV and
mobile offer the existing server-editable, single-field profile setting with
explicit inheritance; device settings cannot enable it. Each actual active source
gets at most one opportunity after 45 seconds of continuous video advancement.
Pause, buffering, seek, track change, menus, recovery and owner retirement cancel
or restart the observation window; an already spent opportunity is not retried.
Only a credible two-sample mismatch offers the existing audio/source review card.
Match and unknown stay silent. The card does not prepare, adopt, report or block
anything automatically. This is optional model evidence, not a language or lip
sync guarantee. Source-only test additions cover the policy, joined cancellation,
conservative hint publication, explicit false/inheritance and confirmed settings
readback. Physical validation is coordinated separately.
