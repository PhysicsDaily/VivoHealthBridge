# VivoHealthBridge 🌉

Bridge your health data from the **Chinese Vivo Health app** (paired with **Vivo Watch GT2**) into **Android Health Connect**, allowing your metrics to sync automatically to **Google Fit, Strava, Samsung Health**, and other health ecosystems.

---

## 🌟 Features

- 🤖 **Auto-Sync via Accessibility Service**:
  - One tap opens the Vivo Health app.
  - Silently navigates and reads your metrics directly off the screen (Home summary, Sleep details, Heart Rate, SpO2, Stress).
  - Translates and parses metrics formatted in English or Chinese.
  - Automatically writes structured records to Android Health Connect.
- ✍️ **Manual Entry Screen**:
  - Fallback forms to enter any metrics manually with realistic validation.
  - Supports detailed Sleep Stage logging (Deep, Light, REM, Awake).
- 📜 **Local Sync History**:
  - Offline Room database keeps track of every metric synced with timestamp and status.
- ☁️ **Zero-Setup Cloud Build**:
  - GitHub Actions automatically compiles the APK for you in the cloud for free. No Android Studio required!

---

## 🚀 Step 1: Compile the APK via GitHub Actions (No Android Studio Needed!)

1. **Create a new repository on GitHub**:
   - Go to [github.com/new](https://github.com/new) and create a repository (e.g. `VivoHealthBridge`).
2. **Push this project to your repository**:
   Open a terminal in this project directory:
   ```bash
   git init
   git add .
   git commit -m "Initial commit of VivoHealthBridge"
   git branch -M main
   git remote add origin https://github.com/<YOUR_USERNAME>/VivoHealthBridge.git
   git push -u origin main
   ```
3. **Download your APK**:
   - Go to your GitHub repository in your web browser.
   - Click on the **Actions** tab at the top.
   - You will see the **Build Android APK** workflow running. Wait ~3-4 minutes until it finishes with a green checkmark.
   - Click on the completed workflow run.
   - Scroll down to the **Artifacts** section and download `VivoHealthBridge-debug-apk.zip`.
   - Unzip the file to get `app-debug.apk` and transfer it to your **Redmi Note 10S**.

---

## 📱 Step 2: Setup on Redmi Note 10S (MIUI / HyperOS)

Because Xiaomi/MIUI has extra security layers for sideloaded apps and background services, follow these quick steps:

### 1. Install Health Connect
- Ensure the **Health Connect** app (by Google) is installed from the Google Play Store (on Android 14+, it is already integrated into System Settings).

### 2. Install and Open VivoHealthBridge
- Open `app-debug.apk` and tap Install.
- Launch **VivoHealthBridge**.

### 3. Grant Health Connect Permissions
- On the Dashboard, tap **Grant Permissions**.
- Toggle **Allow all** to grant write permissions for Steps, Heart Rate, Sleep, Blood Oxygen, HRV, and Weight.

### 4. Enable the Accessibility Service
- Tap **Enable in Settings** (or go to *Phone Settings -> Accessibility -> Downloaded Apps / Installed Services -> VivoHealthBridge*).
- **MIUI "Restricted Setting" Prompt**:
  - If MIUI displays *"Restricted setting: For your security, this setting is currently unavailable"*, do this:
    1. Go to *Phone Settings -> Apps -> Manage Apps -> VivoHealthBridge*.
    2. Tap the **three dots (⋮)** in the top right corner.
    3. Tap **Allow restricted settings**.
    4. Return to *Accessibility -> VivoHealthBridge* and toggle it **ON**.

### 5. Disable MIUI Battery Restrictions (Crucial for Xiaomi)
- Go to *Phone Settings -> Apps -> Manage Apps -> VivoHealthBridge*.
- Tap **Battery saver** and set it to **No restrictions**.
- Enable **Autostart**.

---

## 🔄 Step 3: How to Use

### Option A: Live Sync (Recommended · Manual Navigation)
1. Open **VivoHealthBridge** and tap **Start Live Sync (Manual)**.
2. The Vivo Health app opens with a compact floating helper pill (`🌉 VivoBridge Live`) on screen.
3. Move through the Vivo Health app at your own pace:
   - View your **Activity rings** (Steps, Calories, Exercise, Stand, Distance).
   - Tap your **Sleep** card, swipe through vitals (Heart Rate, Respiratory, SpO₂, HRV), expand **More analysis**, and scroll through stages (Deep, Light, REM).
4. The floating helper will show green checkmarks as each metric is captured live (`🏃 Steps: ✓`, `😴 Sleep: ✓`, `💓 Vitals: 4/4 ✓`, `📊 Stages: 3/3 ✓`).
5. Tap **🚀 Sync to Health Connect** directly on the floating helper (or switch back to VivoHealthBridge). Everything is committed to Health Connect instantly!

### Option B: Auto-Pilot (Automated Gestures)
- Tap the **🤖 Or use Auto-Pilot** button on the dashboard to have the accessibility service automatically pull down to refresh, scroll, and tap cards for you hands-free.

### Option C: Manual Entry
1. Switch to the **Manual Entry** tab at the bottom.
2. Tap any metric (Heart Rate, Sleep with stages, SpO2, Stress, Steps, Weight).
3. Enter your values and tap **Save**. It writes directly to Health Connect immediately.

---

## 🏃 Connecting to Strava / Google Fit

1. Open your target fitness app (e.g. **Strava** or **Google Fit**).
2. Go to **Settings -> Applications, Services, and Devices**.
3. Select **Health Connect** and toggle **Read** access.
4. Your synced Vivo Health metrics will now populate inside Strava and Google Fit!
