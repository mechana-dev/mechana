/*
 * Copyright (c) 2026 Mark Vita
 * Licensed under the Apache License, Version 2.0. See the repository LICENSE.
 */
#pragma once

#include <span>

namespace mechana::reverb {
void transform(std::span<float> real, std::span<float> imaginary, bool inverse) noexcept;
}
