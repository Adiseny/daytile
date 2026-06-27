# Daytile

Daytile is a private offline Android timeline planner.

## Screenshots

<p>
  <img src="docs/readme/daytile-timeline.png" alt="Daytile family timeline with appointments and errands" width="240">
  <img src="docs/readme/daytile-create.png" alt="Daytile student schedule with create task sheet" width="240">
  <img src="docs/readme/daytile-actions.png" alt="Daytile small business schedule with block actions sheet" width="240">
</p>

Daytile has no account system, no network permission, no analytics, and stores planner data only in a local Room database. Backup and device-transfer extraction are disabled.

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:lintDebug
.\gradlew.bat :app:verifyPrivacy
.\gradlew.bat :app:assembleRelease
```

See [docs/release.md](docs/release.md) for signing, connected tests, and benchmark checks.

Proprietary. All rights reserved.
