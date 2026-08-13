package dev.malachi.data

/**
 * When to offer a backup, and when to shut up about it.
 *
 * The thing being protected is the only part of this app that cannot be recreated: a year of
 * exceptions written one broken app at a time, and the handful of lists somebody settled on after
 * finding out which ones break their bank. Everything else — the filter, the blocklists, the
 * statistics — rebuilds itself on a new phone in a minute.
 *
 * So the offer follows the work rather than the calendar. It appears when there is something
 * unsaved, it can be put off on a widening schedule, it can be silenced for good, and **once a
 * backup exists it never asks again until the decisions actually change**. A reminder that fires
 * when nothing has changed is how a person learns to ignore reminders.
 *
 * Pure, and every clock is a parameter: a fortnight of putting it off is a number here.
 */
object BackupPolicy {

    /**
     * The gaps between one reminder and the next, in days. The first offer is immediate — it is
     * prompted by the change itself — and the last gap repeats for as long as the decisions stay
     * unsaved. Widening, because somebody who has said "later" twice has a reason.
     */
    val SNOOZE_DAYS = listOf(3L, 15L)

    private const val DAY_MS = 24 * 60 * 60 * 1000L

    /** True when the rules or the lists have moved on from whatever the last backup covered. */
    fun isStale(settings: MalachiSettings): Boolean =
        settings.hasDecisionsWorthKeeping() &&
            settings.decisionsFingerprint() != settings.backupFingerprint

    /** True when the reminder should be on screen right now. */
    fun reminderDue(settings: MalachiSettings, nowMs: Long): Boolean =
        !settings.backupRemindersOff && isStale(settings) && nowMs >= settings.backupRemindAtMs

    /** How long the reminder waits after being put off for the [stage]-th time. */
    fun snoozeMs(stage: Int): Long = SNOOZE_DAYS[stage.coerceIn(0, SNOOZE_DAYS.lastIndex)] * DAY_MS

    /**
     * The reminder has been put off. The next gap is longer than the last, and the stage is kept
     * so that reinstalling the reminder from a later change starts from the top again.
     */
    fun laterFrom(settings: MalachiSettings, nowMs: Long): MalachiSettings = settings.copy(
        backupRemindAtMs = nowMs + snoozeMs(settings.backupRemindStage),
        backupRemindStage = settings.backupRemindStage + 1,
    )

    /** "Don't remind me again", until it is switched back on from the settings screen. */
    fun silenced(settings: MalachiSettings): MalachiSettings =
        settings.copy(backupRemindersOff = true)

    fun unsilenced(settings: MalachiSettings): MalachiSettings =
        settings.copy(backupRemindersOff = false, backupRemindAtMs = 0, backupRemindStage = 0)

    /**
     * A backup has just been written, or one has been restored — either way what is on disk now
     * matches what is in the app, so the reminder starts over from nothing. Not silenced: the
     * next change is worth mentioning again, and this time from the beginning of the schedule.
     */
    fun backedUp(settings: MalachiSettings): MalachiSettings = settings.copy(
        backupFingerprint = settings.decisionsFingerprint(),
        backupRemindAtMs = 0,
        backupRemindStage = 0,
    )
}
