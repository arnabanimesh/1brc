#!/bin/bash
#
#  Copyright 2023 The original authors
#
#  Licensed under the Apache License, Version 2.0 (the "License");
#  you may not use this file except in compliance with the License.
#  You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
#  limitations under the License.
#

JAVA_OPTS="--enable-preview --enable-native-access=ALL-UNNAMED --add-modules=jdk.incubator.vector"
JAVA_OPTS="$JAVA_OPTS -XX:+UnlockExperimentalVMOptions -XX:+UnlockDiagnosticVMOptions"
JAVA_OPTS="$JAVA_OPTS -XX:-TieredCompilation -XX:MaxInlineSize=10000 -XX:InlineSmallCode=10000 -XX:FreqInlineSize=10000"
JAVA_OPTS="$JAVA_OPTS -XX:-UseCountedLoopSafepoints -XX:LoopStripMiningIter=0 -XX:GuaranteedSafepointInterval=0"
JAVA_OPTS="$JAVA_OPTS -XX:+TrustFinalNonStaticFields -da -dsa -XX:+UseNUMA"
JAVA_OPTS="$JAVA_OPTS -Djdk.incubator.vector.VECTOR_ACCESS_OOB_CHECK=0"

java $JAVA_OPTS --class-path target/average-1.0.0-SNAPSHOT.jar dev.morling.onebrc.CalculateAverage_serkan_ozal
