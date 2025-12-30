#!/bin/bash

# ANSI color codes
GREEN='\033[0;32m'
RED='\033[0;31m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${BLUE}🤖 Starting Android Development Build...${NC}"

# Check if a device is connected
if ! adb get-state 1>/dev/null 2>&1; then
    echo -e "${RED}❌ No Android device/emulator found.${NC}"
    echo "Please launch an emulator or connect a device first."
    exit 1
fi

# Check if Gradle is available at the known location
GRADLE="/tmp/gradle-8.2/bin/gradle"
if [ ! -f "$GRADLE" ]; then
    echo -e "${YELLOW}⚠️  Cached Gradle not found. Using wrapper...${NC}"
    GRADLE="./gradlew"
fi

# Build and Install (installDebug handles both)
echo -e "${BLUE}🔨 Building and Installing Debug APK...${NC}"
$GRADLE installDebug

if [ $? -eq 0 ]; then
    echo -e "${GREEN}✅ Build Successful!${NC}"
    
    # Launch the Activity
    echo -e "${BLUE}🚀 Launching App...${NC}"
    adb shell am start -n com.thiyagu.media_server/.SplashActivity
    
    echo -e "${GREEN}✨ App is running on your emulator!${NC}"
    echo "Note: Unlike Expo, 'Hot Reload' requires Android Studio. To see changes, run this script again."
else
    echo -e "${RED}❌ Build Failed. Check the error output above.${NC}"
    exit 1
fi
