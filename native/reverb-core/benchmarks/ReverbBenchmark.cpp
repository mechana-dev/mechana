/*
 * Copyright (c) 2026 Mark Vita
 * Licensed under the Apache License, Version 2.0. See the repository LICENSE.
 */
#include <mechana/reverb/Parameters.h>
#include <mechana/reverb/ReverbEngine.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstddef>
#include <cstdlib>
#include <iomanip>
#include <iostream>
#include <numeric>
#include <span>
#include <stdexcept>
#include <string>
#include <string_view>
#include <vector>

namespace {
constexpr std::size_t channels = 2;
constexpr std::size_t blockSize = 128;
constexpr double irSeconds = 5.8;

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
            std::cout << "Usage: mechana_reverb_benchmark [--cycles N] [--seconds N] [--sample-rate HZ]\n";
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

std::vector<float> createImpulseResponse(const std::size_t sampleRate) {
    const auto irFrames = static_cast<std::size_t>(std::round(irSeconds * static_cast<double>(sampleRate)));
    std::vector<float> ir(irFrames);
    for (std::size_t frame = 0; frame < ir.size(); ++frame) {
        const auto time = static_cast<double>(frame) / sampleRate;
        const auto decay = std::exp(-time / 1.45);
        const auto reflections = std::sin(frame * 0.017) + 0.43 * std::sin(frame * 0.041);
        ir[frame] = static_cast<float>(reflections * decay * 0.001);
    }
    ir.front() = 0.25F;
    return ir;
}

std::vector<float> createSource(const std::size_t blocks) {
    std::vector<float> source(blocks * blockSize);
    for (std::size_t frame = 0; frame < source.size(); ++frame)
        source[frame] = static_cast<float>((std::sin(frame * 0.031) + 0.27 * std::sin(frame * 0.071)) * 0.1);
    return source;
}

Measurement finishMeasurement(std::vector<double> times, const double checksum) {
    const auto sum = std::accumulate(times.begin(), times.end(), 0.0);
    std::sort(times.begin(), times.end());
    const auto percentileIndex = std::min(times.size() - 1, static_cast<std::size_t>(times.size() * 0.95));
    return { sum / 1'000'000.0, sum / static_cast<double>(times.size()), times[percentileIndex], times.back(),
             checksum };
}

Measurement measure(mechana::reverb::ReverbEngine& engine, const std::span<const float> source,
                    const std::size_t blocks) {
    engine.reset();
    std::vector<std::vector<float>> audio(channels, std::vector<float>(blockSize));
    std::vector<float*> pointers(channels);
    for (std::size_t channel = 0; channel < channels; ++channel)
        pointers[channel] = audio[channel].data();
    mechana::reverb::Parameters parameters;
    parameters.dryLevel = 0.0F;
    parameters.wetLevel = 1.0F;
    std::vector<double> times;
    times.reserve(blocks);
    double checksum = 0.0;
    for (std::size_t block = 0; block < blocks; ++block) {
        const auto input = source.subspan(block * blockSize, blockSize);
        for (auto& channel : audio)
            std::copy(input.begin(), input.end(), channel.begin());
        const auto started = std::chrono::steady_clock::now();
        engine.process(pointers.data(), channels, blockSize, parameters);
        const auto elapsed = std::chrono::duration<double, std::micro>(std::chrono::steady_clock::now() - started);
        times.push_back(elapsed.count());
        checksum += audio[0][block % blockSize];
    }
    return finishMeasurement(std::move(times), checksum);
}

void printMeasurement(const std::size_t cycle, const Measurement& measurement, const double audioSeconds,
                      const std::size_t sampleRate) {
    const auto deadlineMilliseconds = static_cast<double>(blockSize) * 1'000.0 / sampleRate;
    const auto load = measurement.processingSeconds / audioSeconds * 100.0;
    const auto averageMilliseconds = measurement.averageMicroseconds / 1'000.0;
    const auto p95Milliseconds = measurement.p95Microseconds / 1'000.0;
    const auto maximumMilliseconds = measurement.maximumMicroseconds / 1'000.0;
    std::cout << "cycle " << cycle << '\n'
              << "  average: " << averageMilliseconds << " ms/block ("
              << averageMilliseconds / deadlineMilliseconds * 100.0 << "% of deadline)\n"
              << "  p95:     " << p95Milliseconds << " ms/block ("
              << p95Milliseconds / deadlineMilliseconds * 100.0 << "% of deadline)\n"
              << "  maximum: " << maximumMilliseconds << " ms/block ("
              << maximumMilliseconds / deadlineMilliseconds * 100.0 << "% of deadline)\n"
              << "  overall: " << measurement.processingSeconds * 1'000.0 << " ms processing for " << audioSeconds
              << " s audio (load " << load << "%, realtime " << audioSeconds / measurement.processingSeconds
              << "x, checksum " << measurement.checksum << ")\n";
}

double median(std::vector<double> values) {
    std::sort(values.begin(), values.end());
    const auto middle = values.size() / 2;
    if (values.size() % 2 == 0)
        return (values[middle - 1] + values[middle]) / 2.0;
    return values[middle];
}

double average(const std::span<const double> values) {
    return std::accumulate(values.begin(), values.end(), 0.0) / static_cast<double>(values.size());
}
} // namespace

int main(const int argc, char** argv) {
    try {
        const auto options = parseOptions(argc, argv);
        const auto blocks = std::max<std::size_t>(1, static_cast<std::size_t>(
                                                         std::ceil(options.seconds * options.sampleRate / blockSize)));
        const auto audioSeconds = static_cast<double>(blocks * blockSize) / options.sampleRate;
        const auto ir = createImpulseResponse(options.sampleRate);
        const auto source = createSource(blocks);
        std::cout << std::fixed << std::setprecision(3);
        std::cout << "Mechana native effect benchmark v1\n"
                  << "effect-name=Convolution Reverb effect-id=audio-convolution-reverb architecture="
                  << architecture() << " sample-rate=" << options.sampleRate << " channels=" << channels
                  << " block=" << blockSize << " deadline-ms="
                  << static_cast<double>(blockSize) * 1'000.0 / options.sampleRate << " ir-frames=" << ir.size()
                  << " ir-seconds=" << static_cast<double>(ir.size()) / options.sampleRate << " cycle-audio-seconds="
                  << audioSeconds << " cycles=" << options.cycles << '\n';

        mechana::reverb::ReverbEngine engine;
        const auto preparationStarted = std::chrono::steady_clock::now();
        engine.prepare(options.sampleRate, channels, blockSize);
        engine.setImpulseResponse(std::vector<std::vector<float>>(channels, ir));
        const auto preparation =
            std::chrono::duration<double>(std::chrono::steady_clock::now() - preparationStarted).count();
        std::cout << "preparation=" << preparation * 1'000.0 << " ms\n";
        (void) measure(engine, source, std::min<std::size_t>(blocks, 32));

        std::vector<double> loads;
        for (std::size_t cycle = 1; cycle <= options.cycles; ++cycle) {
            const auto measurement = measure(engine, source, blocks);
            loads.push_back(measurement.processingSeconds / audioSeconds * 100.0);
            printMeasurement(cycle, measurement, audioSeconds, options.sampleRate);
        }
        const auto medianLoad = median(loads);
        const auto [minimumLoad, maximumLoad] = std::minmax_element(loads.begin(), loads.end());
        std::cout << "SUMMARY load-min=" << *minimumLoad << "% load-average=" << average(loads)
                  << "% load-median=" << medianLoad << "% load-max=" << *maximumLoad
                  << "% realtime-median=" << 100.0 / medianLoad << "x\n";
        return 0;
    } catch (const std::exception& error) {
        std::cerr << "benchmark error: " << error.what() << '\n';
        return 1;
    }
}
