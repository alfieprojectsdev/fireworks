#!/bin/bash

echo "====================================================="
echo "🔨 Building the latest APK..."
echo "====================================================="

# Sync web assets with Capacitor
npx cap sync android
if [ $? -ne 0 ]; then
    echo "❌ Error: Capacitor sync failed!"
    exit 1
fi

# Build the Android project
cd android || exit
./gradlew assembleDebug
if [ $? -ne 0 ]; then
    echo "❌ Error: Gradle build failed!"
    exit 1
fi
cd ..

echo "✅ Build Successful!"
echo ""

# Define the APK directory relative to the repository root
APK_DIR="android/app/build/outputs/apk/debug"

# Get all local IP addresses of the laptop
LOCAL_IPS=$(hostname -I)

echo "====================================================="
echo "🚀 APK Hosting Server Started!"
echo "====================================================="
echo ""
echo "1. Ensure your phone is connected to the same WiFi as this laptop."
echo "2. Open Chrome (or any browser) on your phone and go to ONE of these links:"
echo ""
for IP in $LOCAL_IPS; do
    echo "   👉  http://$IP:8000  👈"
done
echo ""
echo "3. Tap 'app-debug.apk' from the list to download and install it."
echo ""
echo "====================================================="
echo "Press Ctrl+C when you are done to stop the server."
echo "====================================================="
echo ""

# Navigate to the APK directory and start the server
cd "$APK_DIR" || exit
python3 -m http.server 8000
