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

#include <algorithm>
#include <cmath>

namespace mechana::echo {

// Mechana Feedback is a musical control, not a raw loop multiplier. The gentle
// curve gives useful resolution through ordinary decays while retaining a strict
// sub-unity ceiling. 36% maps to about 0.419 (-7.6 dB per unfiltered generation).
[[nodiscard]] inline float feedbackCoefficient(const float feedback) noexcept {
    constexpr float maximum = 0.98F;
    constexpr float curve = 0.85F;
    const auto normalized = std::clamp(std::abs(feedback), 0.0F, maximum) / maximum;
    const auto coefficient = maximum * std::pow(normalized, curve);
    return std::copysign(coefficient, feedback);
}

} // namespace mechana::echo
