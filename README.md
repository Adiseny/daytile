# Daytile

Daytile is an offline Android app for planning your day as blocks on a 24-hour timeline.

<p align="center">
  <img src="docs/readme/daytile-timeline.png" width="240" alt="Daytile 24-hour timeline with planned tasks">
  <img src="docs/readme/daytile-create.png" width="240" alt="Creating a task">
  <img src="docs/readme/daytile-actions.png" width="240" alt="Block actions for a task">
</p>

## Download

Download the APK from the [latest GitHub release](https://github.com/Adiseny/daytile/releases/latest). Daytile requires Android 8.0 or newer.

The unavailable v1.0.x signing key means v1.1.0 starts a new signing lineage. If v1.0.x is installed, it must be uninstalled before v1.1.0 can be installed. Uninstalling removes its local plans because Daytile deliberately has no backup or transfer access.

## Use

Tap an empty time to add a block. Tap a block for its actions, hold and drag it to move it, or drag its lower handle to resize it. Swipe horizontally to change day and tap the date heading to jump further.

## Privacy

No account, no network, no analytics, no logging. Zero Android permissions, not even `INTERNET`. System backup and device transfer are disabled, and data is stored only in a local on-device database.

This is enforced, not just promised. The `verifyPrivacy` Gradle task fails the release build if a permission, networking or analytics dependency, or logging call is added.

```bash
./gradlew :app:verifyPrivacy
```

## Licence

Proprietary, all rights reserved. The source is public so the privacy claims above can be verified, not reused. See [LICENSE](LICENSE).
