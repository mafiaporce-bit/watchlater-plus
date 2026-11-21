# WatchLater+ (Android)

Save any video to watch later. Tap **Share** in Facebook, YouTube, TikTok, Instagram or any app,
choose **WatchLater+**, pick a folder, done. No more sending videos to your own Messenger.

- `app/src/main/assets/index.html`: the app screens (home list, folders, settings).
- `app/src/main/java/.../ShareActivity.java`: the "Save to which folder?" popup in the Share menu.
- `Sorter.java`: auto-sort by words (free, offline) that learns from the folders you pick.
- `Previews.java`: fetches each link's title and thumbnail in the background.
- `Reminders.java`: the daily "videos waiting" notification.
- GitHub Actions builds `WatchLaterPlus.apk` on every push to `main`. Download it from **Releases**.

Copyright © 2026 Zenix_dev@company. All rights reserved. Developed by JPSums

## Web version (iPhone and any browser)

The folder `web/` is the same app as a web page that works offline (a service worker keeps a copy on the phone).
It is made from the phone app's page: after changing `app/src/main/assets/index.html`, run

    python3 tools/make_web.py

Netlify publishes the `web/` folder (see `netlify.toml`). To upload by hand instead, drag the `web` folder onto Netlify's Deploys page.
