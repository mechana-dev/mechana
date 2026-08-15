/*
 * Copyright (c) 2026 Mark Vita
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
#pragma once

namespace mechana::reverb {

struct Parameters final {
    float wetLevel { 0.35F };
    float dryLevel { 1.0F };
    float preDelayMilliseconds { 20.0F };
    float wetLowCutHertz { 0.0F };
    float wetHighCutHertz { 0.0F };
    bool bypass { false };
};

struct ImpulseResponseParameters final {
    float earlyLevel { 1.0F };
    float lateLevel { 1.0F };
    float attackMilliseconds { 0.0F };
    float decayLengthPercent { 100.0F };
};

} // namespace mechana::reverb
