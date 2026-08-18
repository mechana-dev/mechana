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

#include "Parameters.h"

#include <string_view>

namespace mechana::echo {

using Model = Character;

[[nodiscard]] constexpr std::string_view modelName(const Model model) noexcept {
    switch (model) {
    case Model::neutral:
        return "Neutral Echo";
    case Model::vintageTape:
        return "Vintage Tape";
    case Model::analogMemory:
        return "Analog Memory";
    }
    return "Neutral Echo";
}

[[nodiscard]] constexpr Parameters modelDefaults(const Model model) noexcept {
    Parameters result;
    switch (model) {
    case Model::neutral:
        break;
    case Model::vintageTape:
        result.character = Character::vintageTape;
        result.delayMilliseconds = 375.0F;
        result.feedback = 0.38F;
        result.mix = 0.26F;
        result.feedbackLowCutHertz = 45.0F;
        result.feedbackHighCutHertz = 6'000.0F;
        result.saturation = 0.22F;
        result.modulationRateHertz = 0.55F;
        result.modulationDepthMilliseconds = 1.6F;
        break;
    case Model::analogMemory:
        result.character = Character::analogMemory;
        result.delayMilliseconds = 330.0F;
        result.feedback = 0.36F;
        result.mix = 0.26F;
        result.feedbackLowCutHertz = 80.0F;
        result.feedbackHighCutHertz = 4'500.0F;
        result.saturation = 0.16F;
        result.modulationRateHertz = 0.80F;
        result.modulationDepthMilliseconds = 2.8F;
        break;
    }
    return result;
}

} // namespace mechana::echo
