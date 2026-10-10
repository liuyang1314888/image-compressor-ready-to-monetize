# Image Compressor App

This project is a small, monetizable Android app that lets users:
- choose multiple images
- compress them to smaller JPEG files
- save them back to the device gallery
- optionally remove ads with a one-time in-app purchase

## How to use
1. Open the project in Android Studio.
2. Ensure Android SDK 33 is installed.
3. Plug in a device or start an emulator.
4. Press Run.
5. Use the Google test AdMob IDs already included in the source to verify ads work.

## Required changes before publishing
- Replace the test AdMob app ID and ad units with your own values in `AndroidManifest.xml` and `activity_main.xml`.
- Create a Play Console app and a one-time in-app product named `remove_ads`.
- Replace the default test IDs and test purchase flow before publishing.

## Build steps
- In Android Studio: Build > Generate Signed Bundle/APK
- Or from command line:
  - ./gradlew assembleRelease

## Monetization plan
- AdMob banner + interstitial
- One-time in-app purchase for "Remove Ads"
- Additional premium features can be added later

## Important notes
- The app uses local image processing and then writes compressed images to the gallery.
- For privacy and Google Play compliance, publish a real privacy policy before rollout.

## Privacy policy template
See `privacy_policy.md`.

## Play Store listing draft
See `play-store-listing.md`.
