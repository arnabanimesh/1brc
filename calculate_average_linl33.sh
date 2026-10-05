#!/bin/sh
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


JAVA_OPTS="-Xrs --enable-preview --add-modules jdk.incubator.vector --enable-native-access=ALL-UNNAMED"
JAVA_OPTS="${JAVA_OPTS} -XX:+UnlockDiagnosticVMOptions -XX:+UnlockExperimentalVMOptions"
JAVA_OPTS="${JAVA_OPTS} -Xms128m -XX:+AlwaysPreTouch -XX:+AlwaysPreTouchStacks"
JAVA_OPTS="${JAVA_OPTS} -XX:+ForceUnreachable -XX:-CompactStrings"
JAVA_OPTS="${JAVA_OPTS} -XX:CodeEntryAlignment=64 -XX:OptoLoopAlignment=64 -XX:MaxLoopPad=16 -XX:ObjectAlignmentInBytes=64"
JAVA_OPTS="${JAVA_OPTS} -XX:-UseProfiledLoopPredicate -XX:LoopStripMiningIter=0 -XX:LoopStripMiningIterShortLoop=0"
JAVA_OPTS="${JAVA_OPTS} -XX:-UseCountedLoopSafepoints -XX:GuaranteedSafepointInterval=0 -XX:AllocatePrefetchStyle=0"
JAVA_OPTS="${JAVA_OPTS} -XX:+TrustFinalNonStaticFields"
JAVA_OPTS="${JAVA_OPTS} -XX:-UseDynamicNumberOfCompilerThreads -XX:-UseDynamicNumberOfGCThreads"
JAVA_OPTS="${JAVA_OPTS} -XX:ArchiveRelocationMode=0 -XX:-UsePerfData -XX:-CheckIntrinsics"
#JAVA_OPTS="${JAVA_OPTS} -XX:+UseZGC -XX:-ZProactive -XX:+ZCollectionIntervalOnly -XX:ZCollectionInterval=0 -XX:-ZUncommit -XX:-ZBufferStoreBarriers -XX:ZIndexDistributorStrategy=1"
JAVA_OPTS="${JAVA_OPTS} -XX:+UseEpsilonGC -XX:-UseCompressedOops"
#JAVA_OPTS="${JAVA_OPTS} -XX:+UseParallelGC -XX:-UseCompressedOops"
#JAVA_OPTS="${JAVA_OPTS} -XX:+UseG1GC -XX:-UseCompressedOops"
JAVA_OPTS="${JAVA_OPTS} -Djdk.incubator.vector.VECTOR_ACCESS_OOB_CHECK=0 -Djava.lang.invoke.VarHandle.VAR_HANDLE_GUARDS=false -Djava.lang.invoke.MethodHandle.DONT_INLINE_THRESHOLD=-1"
JAVA_OPTS="${JAVA_OPTS} -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8"

JAVA_OPTS="${JAVA_OPTS} -Xlog:all=off -XX:SharedArchiveFile=target/CalculateAverage_linl33_dynamic.jsa"

MALLOC_ARENA_MAX=1 java ${JAVA_OPTS} --class-path target/average-1.0.0-SNAPSHOT.jar dev.morling.onebrc.CalculateAverage_linl33
