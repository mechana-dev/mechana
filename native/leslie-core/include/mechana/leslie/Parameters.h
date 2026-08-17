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

namespace mechana::leslie {

enum class RotorMode { stopped, slow, fast };

struct Parameters final {
    RotorMode rotorMode { RotorMode::slow };
    float drive { 0.18F };
    float hornLevel { 0.52F };
    float microphoneDistance { 0.35F };
    float stereoWidth { 0.72F };
    float crossoverHertz { 800.0F };
    float wetLevel { 1.0F };
    float dryLevel { 0.0F };
    bool bypass {};
};

} // namespace mechana::leslie

