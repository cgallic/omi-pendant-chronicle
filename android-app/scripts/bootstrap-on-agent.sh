#!/usr/bin/env bash
#
# bootstrap-on-agent.sh — one-shot agent-box setup for Pendant Milestone 1.
#
# Run this AFTER rsync'ing the tree from Windows. It will:
#   1. Verify the tree is at /opt/pendant-android-app
#   2. Copy gradle 8.10 wrapper from /opt/connor-omi-fork (Omi fork has it checked in)
#   3. Generate local.properties (sdk.dir + agent.url + pendant.secret)
#   4. Run `./gradlew :app:assembleDebug`
#   5. Print APK path + sha256 + install instructions
#
# ---- From Windows host (PowerShell or Git Bash): ---------------------------
#
#   # First time only — pick a 32-hex secret, save it on agent:
#   ssh root@agent 'cat > /srv/ai/pendant/.env.pendant-secret' <<< "PENDANT_SECRET=$(openssl rand -hex 16)"
#
#   # Sync + build (every iteration), from the android-app/ directory:
#   rsync -av --delete ./ root@agent:/opt/pendant-android-app/
#   ssh root@agent 'source /srv/ai/pendant/.env.pendant-secret && \
#       export PENDANT_SECRET && \
#       bash /opt/pendant-android-app/scripts/bootstrap-on-agent.sh'
#
#   # Pull APK back to the host:
#   scp root@agent:/opt/pendant-android-app/app/build/outputs/apk/debug/app-debug.apk \
#     ./chronicle-debug.apk
#
# ----------------------------------------------------------------------------

set -euo pipefail

TREE=${TREE:-/opt/pendant-android-app}
OMI_FORK=${OMI_FORK:-/opt/connor-omi-fork}
ANDROID_SDK=${ANDROID_SDK:-/opt/android-sdk}

# Resolve agent tailnet IP (Connor's phone connects via tailnet)
if command -v tailscale >/dev/null 2>&1; then
    AGENT_IP="$(tailscale ip -4 2>/dev/null | head -1 || true)"
fi
AGENT_IP=${AGENT_IP:-$(hostname -I 2>/dev/null | awk '{print $1}')}
AGENT_IP=${AGENT_IP:-127.0.0.1}

echo "==> Tree:      $TREE"
echo "==> Omi fork:  $OMI_FORK"
echo "==> SDK:       $ANDROID_SDK"
echo "==> Agent IP:  $AGENT_IP (will go into local.properties as agent.url)"
echo ""

# --- Step 1: tree present ---------------------------------------------------
if [[ ! -d "$TREE" ]]; then
    echo "ERROR: $TREE not found. rsync the Windows tree first." >&2
    exit 1
fi
if [[ ! -f "$TREE/settings.gradle.kts" ]]; then
    echo "ERROR: $TREE/settings.gradle.kts missing — tree looks incomplete." >&2
    exit 1
fi

# --- Step 2: gradle wrapper -------------------------------------------------
if [[ ! -f "$TREE/gradlew" || ! -f "$TREE/gradle/wrapper/gradle-wrapper.jar" ]]; then
    # Omi fork layout has shifted across revisions; try both.
    WRAPPER_SRC="$OMI_FORK/app/android"
    if [[ ! -f "$WRAPPER_SRC/gradlew" ]]; then
        WRAPPER_SRC="$OMI_FORK/omi/app/android"
    fi
    if [[ ! -f "$WRAPPER_SRC/gradlew" || ! -f "$WRAPPER_SRC/gradle/wrapper/gradle-wrapper.jar" ]]; then
        echo "ERROR: gradle wrapper not found at $WRAPPER_SRC." >&2
        echo "  Alternative: install gradle on agent and run:" >&2
        echo "    cd $TREE && gradle wrapper --gradle-version 8.10" >&2
        exit 1
    fi
    echo "==> Copying gradle wrapper from $WRAPPER_SRC"
    cp "$WRAPPER_SRC/gradlew" "$TREE/"
    cp "$WRAPPER_SRC/gradlew.bat" "$TREE/" 2>/dev/null || true
    mkdir -p "$TREE/gradle/wrapper"
    cp "$WRAPPER_SRC/gradle/wrapper/gradle-wrapper.jar"        "$TREE/gradle/wrapper/"
    cp "$WRAPPER_SRC/gradle/wrapper/gradle-wrapper.properties" "$TREE/gradle/wrapper/"
    chmod +x "$TREE/gradlew"
fi

# --- Step 3: local.properties ----------------------------------------------
LOCAL_PROPS="$TREE/local.properties"
if [[ ! -f "$LOCAL_PROPS" ]]; then
    : "${PENDANT_SECRET:?ERROR: set PENDANT_SECRET env var (32-hex) before running}"
    echo "==> Writing $LOCAL_PROPS"
    cat > "$LOCAL_PROPS" <<EOF
sdk.dir=$ANDROID_SDK
agent.url=http://${AGENT_IP}:8773/raw
pendant.secret=${PENDANT_SECRET}
EOF
    chmod 600 "$LOCAL_PROPS"
else
    echo "==> Keeping existing local.properties (delete to regenerate)"
fi

# --- Step 4: build ----------------------------------------------------------
cd "$TREE"

# Make sure Android env is loaded even in a non-login shell
[[ -f /etc/profile.d/android.sh ]] && source /etc/profile.d/android.sh
[[ -f /etc/profile.d/flutter.sh ]] && source /etc/profile.d/flutter.sh
export ANDROID_HOME=${ANDROID_HOME:-$ANDROID_SDK}
export ANDROID_SDK_ROOT=${ANDROID_SDK_ROOT:-$ANDROID_SDK}
export JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/temurin-21-jdk-amd64}

echo "==> JAVA_HOME=$JAVA_HOME"
echo "==> ANDROID_HOME=$ANDROID_HOME"
echo "==> Running: ./gradlew :app:assembleDebug"
echo ""

./gradlew :app:assembleDebug

# --- Step 5: report ---------------------------------------------------------
APK="$TREE/app/build/outputs/apk/debug/app-debug.apk"
if [[ ! -f "$APK" ]]; then
    echo "ERROR: APK not produced at $APK" >&2
    exit 1
fi

SHA="$(sha256sum "$APK" | cut -d' ' -f1)"
SIZE="$(du -h "$APK" | cut -f1)"

echo ""
echo "============================================================"
echo " APK READY"
echo "============================================================"
echo "  Path:   $APK"
echo "  Size:   $SIZE"
echo "  SHA256: $SHA"
echo ""
echo "Pull to your host:"
echo "  scp root@agent:$APK ./chronicle-debug.apk"
echo ""
echo "Install on the phone (USB-C connected, or 'adb connect <ip>:<port>' first):"
echo "  adb install -r ./chronicle-debug.apk"
echo ""
echo "Start raw sink on agent (separate terminal):"
echo "  cd /srv/ai/pendant && source .env.pendant-secret && \\"
echo "    .venv-raw-sink/bin/uvicorn raw_sink:app --host 0.0.0.0 --port 8773"
echo "(port 8773; 8772 is reserved for the existing stock-Omi webhook receiver)"
echo ""
echo "Watch frames flow (also separate terminal):"
echo "  adb logcat -s OmiBleClient:V PendantFgService:V AgentUploader:V"
echo "============================================================"
