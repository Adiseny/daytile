# Daytile

https://github.com/user-attachments/assets/d70025df-86f2-4962-9f8f-eacb93eca2bf

Daytile is an offline Android app for planning your day as blocks on a 24-hour timeline.

## Download

Download the APK from the [latest GitHub release](https://github.com/Adiseny/daytile/releases/latest).

## Use

Tap an empty time to add a block. Tap a block for its actions, hold and drag it to move it, or drag its lower handle to resize it. Swipe horizontally to change day and tap the date heading to jump further.

Tap the date heading and use the bell at the bottom left to switch reminders on. While they are on, every block reminds you five minutes before it starts, counting down to the start, and again as it starts, counting down to the end. A reminder clears itself when its countdown ends, or as soon as you delete or move the block. Reminders are off until you turn them on.

Blocks take their colour from the time of day they start, and the planner follows the clock rather than the system theme: light by day, dark from 20:00 to 07:00.

<p align="center">
  <img src="docs/readme/daytile-timeline.png" width="240" alt="A full day planned as blocks on the timeline">
  <img src="docs/readme/daytile-add.gif" width="240" alt="Adding a block: tap an empty time, type a title, tap Add">
  <img src="docs/readme/daytile-move.gif" width="240" alt="Holding a block to move it, then dragging its lower handle to lengthen it">
</p>

<p align="center">
  <img src="docs/readme/daytile-days.gif" width="240" alt="Swiping to the next days, then jumping to a date from the calendar">
  <img src="docs/readme/daytile-reminder.gif" width="240" alt="A reminder arriving five minutes before a block and counting down to its start">
  <img src="docs/readme/daytile-evening.png" width="240" alt="The same day in the evening, on dark paper">
</p>

## Privacy

No account, no network, no analytics, no logging. No `INTERNET` permission, so planner data cannot leave your device. System backup and device transfer are disabled, and data is stored only in a local on-device database.

Daytile declares four permissions, all of them for reminders, and **none of them grants access to any data**: `POST_NOTIFICATIONS` (draw a reminder on your screen), `USE_EXACT_ALARM` and `SCHEDULE_EXACT_ALARM` (fire it at the right minute rather than whenever the system next wakes up), and `RECEIVE_BOOT_COMPLETED` (re-arm your reminders after a restart). Notification access is requested only the first time you switch reminders on, and never if you don't.

This is enforced, not just promised. The `verifyPrivacy` Gradle task fails the release build if any *other* permission — `INTERNET` included — or a networking or analytics dependency, or a logging call is added.

```bash
./gradlew :app:verifyPrivacy
```

## Licence

Proprietary, all rights reserved. The source is public so the privacy claims above can be verified, not reused. See [LICENSE](LICENSE).

Daytile is compiled from Kotlin and ships no libraries. A few functions of the Kotlin standard library are compiled into its own code; they are under the Apache License 2.0, reproduced in [docs/kotlin-licence.txt](docs/kotlin-licence.txt).
