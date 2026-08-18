/*
 * Copyright (c) 2026 Mark Vita
 * Licensed under the Apache License, Version 2.0. See the repository LICENSE.
 */
#include <mechana/leslie/LeslieEngine.h>
#include <mechana/leslie/Models.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdlib>
#include <iomanip>
#include <iostream>
#include <numeric>
#include <stdexcept>
#include <string>
#include <string_view>
#include <vector>

namespace {
constexpr std::size_t channels = 2;
constexpr std::size_t blockSize = 128;

struct Options final {
    std::size_t cycles { 5 };
    double seconds { 2.0 };
    std::size_t sampleRate { 48'000 };
};

struct Measurement final {
    double processingSeconds {};
    double averageMicroseconds {};
    double p95Microseconds {};
    double maximumMicroseconds {};
    double checksum {};
};

std::size_t positiveInteger(const std::string_view text, const std::string_view name) {
    const auto value = std::strtoull(std::string(text).c_str(), nullptr, 10);
    if (value == 0)
        throw std::invalid_argument(std::string(name) + " must be greater than zero");
    return static_cast<std::size_t>(value);
}

double positiveDouble(const std::string_view text, const std::string_view name) {
    const auto value = std::strtod(std::string(text).c_str(), nullptr);
    if (!(value > 0.0))
        throw std::invalid_argument(std::string(name) + " must be greater than zero");
    return value;
}

Options parseOptions(const int argc, char** argv) {
    Options options;
    for (int index = 1; index < argc; ++index) {
        const std::string_view argument(argv[index]);
        if (argument == "--cycles" && index + 1 < argc)
            options.cycles = positiveInteger(argv[++index], "cycles");
        else if (argument == "--seconds" && index + 1 < argc)
            options.seconds = positiveDouble(argv[++index], "seconds");
        else if (argument == "--sample-rate" && index + 1 < argc)
            options.sampleRate = positiveInteger(argv[++index], "sample rate");
        else if (argument == "--help") {
            std::cout << "Usage: mechana_leslie_benchmark [--cycles N] [--seconds N] [--sample-rate HZ]\n";
            std::exit(0);
        } else
            throw std::invalid_argument("unknown or incomplete option: " + std::string(argument));
    }
    return options;
}

std::string architecture() {
#if defined(__arm64__)
    return "arm64";
#elif defined(__x86_64__)
    return "x86_64";
#else
    return "unknown";
#endif
}

Measurement measure(mechana::leslie::LeslieEngine& engine, const std::size_t blocks,
                    const mechana::leslie::Parameters& parameters) {
    engine.reset();
    std::vector<std::vector<float>> audio(channels, std::vector<float>(blockSize));
    std::vector<float*> pointers(channels);
    for (std::size_t channel = 0; channel < channels; ++channel)
        pointers[channel] = audio[channel].data();
    std::vector<double> times;
    times.reserve(blocks);
    double checksum = 0.0;
    for (std::size_t block = 0; block < blocks; ++block) {
        for (std::size_t frame = 0; frame < blockSize; ++frame) {
            const auto position = static_cast<double>(block * blockSize + frame);
            const auto source = static_cast<float>((std::sin(position * 0.031) + 0.27 * std::sin(position * 0.071)) * 0.1);
            for (auto& channel : audio)
                channel[frame] = source;
        }
        const auto started = std::chrono::steady_clock::now();
        engine.process(pointers.data(), channels, blockSize, parameters);
        const auto elapsed = std::chrono::duration<double, std::micro>(std::chrono::steady_clock::now() - started);
        times.push_back(elapsed.count());
        checksum += audio[0][block % blockSize];
    }
    const auto sum = std::accumulate(times.begin(), times.end(), 0.0);
    std::sort(times.begin(), times.end());
    const auto p95 = std::min(times.size() - 1, static_cast<std::size_t>(times.size() * 0.95));
    return { sum / 1'000'000.0, sum / static_cast<double>(times.size()), times[p95], times.back(), checksum };
}

double median(std::vector<double> values) {
    std::sort(values.begin(), values.end());
    const auto middle = values.size() / 2;
    return values.size() % 2 == 0 ? (values[middle - 1] + values[middle]) / 2.0 : values[middle];
}
} // namespace

int main(const int argc, char** argv) {
    try {
        const auto options = parseOptions(argc, argv);
        const auto blocks = std::max<std::size_t>(
            1, static_cast<std::size_t>(std::ceil(options.seconds * options.sampleRate / blockSize)));
        const auto audioSeconds = static_cast<double>(blocks * blockSize) / options.sampleRate;
        auto parameters = mechana::leslie::classicCabinetDefaults();
        parameters.rotorMode = mechana::leslie::RotorMode::fast;
        std::cout << std::fixed << std::setprecision(3)
                  << "Mechana native effect benchmark v1\n"
                  << "effect-name=Modeled_Leslie effect-id=modeled-leslie model=Classic_Cabinet architecture="
                  << architecture() << " sample-rate=" << options.sampleRate << " channels=" << channels
                  << " block=" << blockSize << " deadline-ms="
                  << static_cast<double>(blockSize) * 1'000.0 / options.sampleRate
                  << " cycle-audio-seconds=" << audioSeconds << " cycles=" << options.cycles << '\n';

        mechana::leslie::LeslieEngine engine;
        const auto preparationStarted = std::chrono::steady_clock::now();
        engine.prepare(options.sampleRate, channels, blockSize);
        const auto preparation = std::chrono::duration<double>(std::chrono::steady_clock::now() - preparationStarted);
        std::cout << "preparation=" << preparation.count() * 1'000.0 << " ms\n";
        (void) measure(engine, std::min<std::size_t>(blocks, 32), parameters);

        std::vector<double> loads;
        for (std::size_t cycle = 1; cycle <= options.cycles; ++cycle) {
            const auto measurement = measure(engine, blocks, parameters);
            const auto deadlineMilliseconds = static_cast<double>(blockSize) * 1'000.0 / options.sampleRate;
            const auto averageMilliseconds = measurement.averageMicroseconds / 1'000.0;
            const auto p95Milliseconds = measurement.p95Microseconds / 1'000.0;
            const auto maximumMilliseconds = measurement.maximumMicroseconds / 1'000.0;
            const auto load = measurement.processingSeconds / audioSeconds * 100.0;
            loads.push_back(load);
            std::cout << "cycle " << cycle << '\n'
                      << "  average: " << averageMilliseconds << " ms/block ("
                      << averageMilliseconds / deadlineMilliseconds * 100.0 << "% of deadline)\n"
                      << "  p95:     " << p95Milliseconds << " ms/block ("
                      << p95Milliseconds / deadlineMilliseconds * 100.0 << "% of deadline)\n"
                      << "  maximum: " << maximumMilliseconds << " ms/block ("
                      << maximumMilliseconds / deadlineMilliseconds * 100.0 << "% of deadline)\n"
                      << "  overall: " << measurement.processingSeconds * 1'000.0 << " ms processing for "
                      << audioSeconds << " s audio (load " << load << "%, realtime "
                      << audioSeconds / measurement.processingSeconds << "x, checksum " << measurement.checksum << ")\n";
        }
        const auto [minimumLoad, maximumLoad] = std::minmax_element(loads.begin(), loads.end());
        const auto averageLoad = std::accumulate(loads.begin(), loads.end(), 0.0) / static_cast<double>(loads.size());
        const auto medianLoad = median(loads);
        std::cout << "SUMMARY load-min=" << *minimumLoad << "% load-average=" << averageLoad
                  << "% load-median=" << medianLoad << "% load-max=" << *maximumLoad
                  << "% realtime-median=" << 100.0 / medianLoad << "x\n";
        return 0;
    } catch (const std::exception& error) {
        std::cerr << "benchmark error: " << error.what() << '\n';
        return 1;
    }
}
