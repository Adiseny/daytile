# Daytile

Daytile is an offline Android app for planning your day as blocks on a 24-hour timeline.

<p align="center">
  <img src="docs/readme/daytile-timeline.png" width="240" alt="Daytile 24-hour timeline with planned tasks">
  <img src="docs/readme/daytile-create.png" width="240" alt="Creating a task">
  <img src="docs/readme/daytile-actions.png" width="240" alt="Block actions for a task">
</p>

## Privacy

No account, no network, no analytics, no logging. Zero Android permissions, not even `INTERNET`. System backup and device transfer are disabled, and data is stored only in a local on-device database.

This is enforced, not just promised. The `verifyPrivacy` Gradle task fails the release build if a permission, networking or analytics dependency, or logging call is added.

```bash
./gradlew :app:verifyPrivacy
```

## License

Proprietary, all rights reserved. The source is public so the privacy claims above can be verified, not reused. See [LICENSE](LICENSE).
