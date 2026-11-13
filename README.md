# BPAY RC Native Android App (`bpayrc.apk`)

This project builds a 100% Native Android APK for **https://ccpay.bpaypan.co.in** where:
- **Notifications come directly from `BPAY RC`** (No Google Chrome icon, no `ccpay.bpaypan.co.in` domain text).
- **100% Fullscreen Native App** (No browser address bar).
- **Background Notification Service (`BpayNotificationService`)**: Receives Admin Broadcasts and User Transaction updates even when the app is closed.

## How to Build `bpayrc.apk` Automatically (Zero Software Needed)
1. Create a new repository on GitHub (can be Private or Public).
2. Upload/push all files from this ZIP into your GitHub repository.
3. Click the **Actions** tab in your GitHub repository -> select **Build BPAY RC Native APK**.
4. Wait ~90 seconds for the green checkmark, then download **`bpayrc-apk`** from the **Artifacts** section at the bottom of the workflow run.
5. Extract `bpayrc.apk` and upload it in your **Admin Panel -> Routing & Settings -> Android App (bpayrc.apk) Download Button**.
