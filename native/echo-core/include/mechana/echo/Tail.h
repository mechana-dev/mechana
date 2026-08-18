/*
 * Copyright (c) 2026 Mark Vita
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
#pragma once

#include "Parameters.h"
#include "Feedback.h"

#include <algorithm>
#include <cmath>

namespace mechana::echo {

[[nodiscard]] inline double reportedTailSeconds(const Parameters& parameters) noexcept {
    constexpr auto inaudibleAmplitude = 0.00001;
    constexpr auto maximumTailSeconds = 30.0;
    if (parameters.bypass || parameters.mix <= 0.0F)
        return 0.0;

    const auto delay = std::max(0.0, static_cast<double>(parameters.delayMilliseconds) / 1000.0);
    const auto feedback = std::abs(static_cast<double>(feedbackCoefficient(parameters.feedback)));
    if (feedback <= inaudibleAmplitude)
        return std::min(maximumTailSeconds, delay * 2.0);

    const auto repeatsToInaudible = std::ceil(std::log(inaudibleAmplitude) / std::log(feedback));
    return std::min(maximumTailSeconds, delay * (repeatsToInaudible + 1.0));
}

} // namespace mechana::echo
