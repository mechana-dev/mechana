/*
 * Copyright (c) 2026 Mark Vita
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied.
 */
#pragma once

namespace mechana::echo {

enum class Character { neutral, vintageTape, analogMemory };

struct Parameters final {
    Character character { Character::neutral };
    float delayMilliseconds { 350.0F };
    float feedback { 0.35F };
    float mix { 0.35F };
    float feedbackLowCutHertz { 0.0F };
    float feedbackHighCutHertz { 0.0F };
    float saturation { 0.0F };
    float modulationRateHertz { 0.0F };
    float modulationDepthMilliseconds { 0.0F };
    bool pingPong { false };
    bool bypass { false };
};

} // namespace mechana::echo
