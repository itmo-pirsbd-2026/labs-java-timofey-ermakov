#!/usr/bin/env bash
# Capture one profile of ProfileRunner (Windows JVM, JFR recorder) and render
# flamegraphs with async-profiler's jfrconv (its JFR->flamegraph converter).
# Usage: capture-jfr.sh <mode:radius|knn> <seconds> <name-prefix>
# Writes <name-prefix>.jfr into report/profiles/, then html/svg/collapsed renders.
set -euo pipefail

LAB="D:/_ITMOStudy/_Master/HighloadJava/labs-timofey-ermakov/labs-java-timofey-ermakov/lab-1-java-geo-kdtree"
JAVA="C:/Users/admin/.jdks/openjdk-25.0.2/bin/java"
MODE=${1:?mode}
SECS=${2:?seconds}
NAME=${3:?name}
OUT="$LAB/report/profiles"
CP="$LAB/profile-runner/build/libs/profile-runner-1.0-SNAPSHOT.jar;$LAB/build/libs/lab-1-java-geo-kdtree-1.0-SNAPSHOT.jar"
JFRCONV="$LAB/.profiler-tools/async-profiler-4.6-linux-x64/bin/jfrconv"
cd "$LAB"
"$JAVA" -XX:StartFlightRecording="duration=$((SECS + 30))s,filename=$OUT/$NAME.jfr,settings=profile" \
  -cp "$CP" org.timermakov.geo.profile.ProfileRunner --mode "$MODE" --seconds "$SECS" --n 100000 --radius 1000 --k 20

for fmt in html collapsed; do
  "$JAVA" -Xss2m -jar "$JFRCONV" -o "$fmt" "$OUT/$NAME.jfr" "$OUT/$NAME.$fmt"
done
echo "captured: $OUT/$NAME.{jfr,html,collapsed}"
