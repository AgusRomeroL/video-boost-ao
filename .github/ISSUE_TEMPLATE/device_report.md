---
name: Device report
about: Tell me whether it works on your Pixel, especially a model I cannot test
title: "[Device] "
labels: device-report
---

I test every release on a Pixel 10 Pro XL, which is the only device I own.
Reports from other models are what keep this app honest, and a report saying
"it just works" is as useful as one saying it broke.

**Did it work?**
- [ ] Yes, Video Boost turned on by itself
- [ ] Partly (describe below)
- [ ] No

**Device**
- Pixel model: (e.g. Pixel 11 Pro XL)
- Android version: (Settings, About phone, Android version)
- Pixel Camera version: (Settings, Apps, Pixel Camera, scroll down)
- System language:
- Video Boost AO version:

**Activation mode**
- [ ] Always on
- [ ] Only when the Camera opens (on demand)

**What you saw**
Anything worth knowing: how long it took, whether the settings panel flickered,
whether it worked in some modes and not others.

**Logs (only if something went wrong)**
Run `adb logcat -s VideoBoostAO` while opening the camera, and paste the output:

```
(paste here)
```
