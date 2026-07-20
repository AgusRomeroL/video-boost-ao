---
name: Bug report
about: Video Boost doesn't turn on, stopped working, or wrong behavior
title: "[Bug] "
labels: bug
---

**What happens**
A clear description of the problem.

**Did it ever work?**
- [ ] Never worked
- [ ] Worked, then stopped (e.g. after a Pixel Camera update)

**Device info**
- Pixel model: (e.g. Pixel 10 Pro XL)
- Android version: (Settings → About phone → Android version)
- Pixel Camera version: (Play Store → Pixel Camera → scroll down, or Settings → Apps → Pixel Camera)
- System language:
- Video Boost AO version: (shown as the release you installed, e.g. v2.2)

**Checklist**
- [ ] The accessibility service *Video Boost Always-On* is enabled
- [ ] The master switch inside the app is on
- [ ] The camera is in **Video** mode when I test
- [ ] My device has Video Boost (Pixel Pro, 8 Pro or newer)

**Logs (very helpful)**
Run `adb logcat -s VideoBoostAO` while opening the camera, and paste the output:

```
(paste here)
```
