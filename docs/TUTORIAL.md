# Video Boost AO: install & setup tutorial

A short, visual walkthrough. It takes about a minute.

## Forward this to anyone you share the app with

> **Video Boost AO** keeps Video Boost on automatically on your Pixel Pro, so
> you never have to flip it on before every video.
>
> When you install it, Android will show two scary-looking warnings. **Both are
> normal for this kind of app and don't mean anything is wrong**. They appear
> for *any* app installed outside the Play Store that automates the screen
> (Tasker, MacroDroid, etc. get the exact same ones):
>
> 1. **"App blocked by Play Protect"**: tap **More details, then Install anyway**.
> 2. **"Restricted setting"** when enabling the accessibility service: open the
>    app's **App info**, tap the **⋮** menu, choose **Allow restricted
>    settings**, confirm with your fingerprint, then enable the service.
>
> The app only reads and taps controls *inside Pixel Camera*. It has no ads, no
> analytics, and its only permission besides accessibility is internet access to
> check for updates. Source code: https://github.com/AgusRomeroL/video-boost-ao
>
> Easiest install with automatic updates: add the repo to
> [Obtainium](https://github.com/ImranR98/Obtainium).

## Why the warnings appear

The whole point of the app is to flip a switch inside Pixel Camera for you. The
only way to do that without root is Android's **accessibility** API. Google
(correctly) treats that API as sensitive, because a malicious app could abuse it
to read the screen. So both Play Protect and the "restricted setting" gate exist
to make you *stop and confirm* before granting it. You are confirming that you
trust this specific app, which you can, because the code is open and it only
touches Pixel Camera. There is no way to remove these prompts without shipping on
the Play Store, which this app avoids on purpose (Play's policy forbids using
accessibility to automate another app's UI).

## Step by step

The app walks you through it. The setup guide stays available inside the app the
whole time.

### 1. Open the app

Fresh install shows a guided **"Let's set it up"** card with the two steps. The
master switch stays off until the accessibility service is enabled.

![Guided setup](tutorial-1-setup.png)

### 2. Tap "Open Accessibility settings"

The app jumps you straight to Accessibility with **Video Boost Always-On**
highlighted. Turn it on and confirm.

![Accessibility, highlighted](tutorial-2-accessibility.png)

### 3. If it's greyed out ("Restricted setting")

On some installs Android greys the entry out and, if you tap it, shows this. It's
the standard block for apps installed outside the Play Store, not a problem with
this app. Tap **Close**, then lift the restriction next.

![Restricted setting dialog](tutorial-3-restricted-dialog.png)

### 4. Allow restricted settings

Back in the app tap **Open App info**. On the App info screen, tap the **⋮** menu
(top right) and choose **Allow restricted settings**, then confirm with your
fingerprint or PIN. Now repeat step 2 and the toggle will work.

![App info](tutorial-4-app-info.png)

### 5. Done

Once the service is on, the app shows **Always-on**: Video Boost turns on by
itself every time you open the Camera in video mode. The setup guide collapses to
a checkmark you can reopen any time, and the master switch lets you pause the
feature without touching accessibility settings.

![Always-on](tutorial-5-active.png)

Open Pixel Camera in video mode and you'll see Video Boost switch on by itself.

---

*Screens captured on a Pixel 7 (Android 16). The app works on Pixel Pro models
that actually have Video Boost: Pixel 8 Pro and newer Pro models.*
