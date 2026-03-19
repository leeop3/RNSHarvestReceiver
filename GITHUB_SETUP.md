# GitHub Build Setup Guide

This project uses **GitHub Actions** to build the APK entirely in the cloud.
You do not need Android Studio, WSL, or any Android SDK on your machine.

---

## What you need on your machine

| Tool | Purpose | Download |
|------|---------|----------|
| Git | Push code to GitHub | https://git-scm.com/downloads |
| A GitHub account | Free | https://github.com |

That's it. The build happens on GitHub's servers.

---

## Step 1 — Create a new GitHub repository

1. Go to https://github.com/new
2. Repository name: `RNSHarvestReceiver`
3. Set to **Private** (recommended — this is field operations software)
4. **Do NOT** tick "Add a README" or any other options
5. Click **Create repository**
6. Copy the repository URL shown (looks like `https://github.com/YOUR_USERNAME/RNSHarvestReceiver.git`)

---

## Step 2 — Push the project to GitHub

Open a terminal (Command Prompt / PowerShell on Windows, Terminal on Mac/Linux)
and run these commands. Replace `YOUR_USERNAME` with your GitHub username.

```bash
# Navigate into the project folder (wherever you extracted the zip)
cd RNSHarvestReceiver

# Initialise git and push
git init
git add .
git commit -m "Initial commit: RNS Harvest Receiver"
git branch -M main
git remote add origin https://github.com/YOUR_USERNAME/RNSHarvestReceiver.git
git push -u origin main
```

When prompted, enter your GitHub username and a **Personal Access Token**
(NOT your password — see note below).

> **Getting a Personal Access Token:**
> GitHub Settings → Developer settings → Personal access tokens → Tokens (classic)
> → Generate new token → tick `repo` scope → Generate → copy the token.
> Use this token as the password when git asks.

---

## Step 3 — Watch the build run

1. Go to your repository on GitHub
2. Click the **Actions** tab at the top
3. You will see a workflow called **"Build APK"** running automatically
4. Click on it to watch the live build logs
5. The build takes about **3–5 minutes**

The workflow runs these jobs in order:
```
Unit Tests  →  Build Debug APK
                    ↓
              Build Release APK  (main/master branch only)
```

---

## Step 4 — Download the APK

Once the build succeeds (green checkmark ✅):

1. Click on the completed workflow run
2. Scroll down to the **Artifacts** section at the bottom
3. Click **`RNSHarvestReceiver-debug-1`** to download a ZIP file
4. Unzip it — inside is `RNSHarvestReceiver-debug-1.apk`

### Install on Android

1. Transfer the APK to your Android device (USB cable, email, or WhatsApp)
2. On the Android device: **Settings → Security** (or Privacy on newer Android)
3. Enable **Install unknown apps** for Files / Chrome / your file manager
4. Open the APK file and tap **Install**
5. Open **RNS Harvest Receiver**

---

## Step 5 (Optional) — Create a Release with a download link

To create a permanent, shareable download link:

```bash
# Tag a version
git tag v1.0.0
git push origin v1.0.0
```

GitHub Actions will automatically:
1. Build the APK
2. Create a **GitHub Release** at `https://github.com/YOUR_USERNAME/RNSHarvestReceiver/releases`
3. Attach the APK as a downloadable file

Anyone with access to the repository can then download the APK directly from
the Releases page.

---

## Every time you make code changes

```bash
# Make your changes, then:
git add .
git commit -m "Description of what you changed"
git push
```

GitHub Actions automatically rebuilds the APK on every push. New APK artifacts
appear in the Actions tab within minutes.

---

## Setting up signed Release APKs (Optional)

A signed APK can be installed without enabling "Install unknown apps" in some
cases, and is required for Google Play. To set this up:

### Generate a keystore (run once on any machine with Java)

```bash
keytool -genkey -v \
  -keystore harvest-release.keystore \
  -alias harvest \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000
```

### Add secrets to GitHub

Go to your repository → **Settings → Secrets and variables → Actions → New secret**

| Secret name      | Value                                               |
|------------------|-----------------------------------------------------|
| `KEYSTORE_BASE64`| `base64 harvest-release.keystore` (copy the output)|
| `KEY_ALIAS`      | `harvest`                                           |
| `KEY_PASSWORD`   | The key password you chose                          |
| `STORE_PASSWORD` | The store password you chose                        |

On Windows, encode the keystore with:
```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("harvest-release.keystore")) | clip
```

Once these secrets are set, every push to `main` will produce a signed
release APK in addition to the debug APK.

---

## Troubleshooting

### Build fails with "gradlew: Permission denied"
This means the `gradlew` file lost its executable bit. Fix it:
```bash
git update-index --chmod=+x gradlew
git commit -m "fix: restore gradlew executable permission"
git push
```

### Build fails with "SDK location not found"
The `local.properties` file should NOT be committed (it's in `.gitignore`).
GitHub Actions uses its own Android SDK — no `local.properties` needed.

### Build fails with Kotlin/Gradle version errors
Check the **Actions** tab for the full error. Most version errors are fixed by
updating `compileSdk` or the Kotlin version in `app/build.gradle`.

### APK installs but crashes immediately
Enable **USB debugging** on the Android device, connect via USB, and run:
```bash
adb logcat | grep -E "RNSHarvestReceiver|FATAL|AndroidRuntime"
```
This shows the crash reason. Share the log if you need help debugging.
