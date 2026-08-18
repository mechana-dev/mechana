/* Copyright (c) 2026 Mark Vita. Licensed under Apache-2.0. */
#include <mechana/fuzz/OctaveFuzzEngine.h>
#include <array>
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdlib>
#include <iomanip>
#include <iostream>
#include <numeric>
#include <string_view>
#include <vector>

int main(int argc, char** argv) {
    std::size_t cycles = 5;
    double seconds = 2.0;
    std::size_t sampleRate = 48'000;
    for (int i = 1; i + 1 < argc; i += 2) {
        const std::string_view option(argv[i]);
        if (option == "--cycles") cycles = std::strtoull(argv[i + 1], nullptr, 10);
        else if (option == "--seconds") seconds = std::strtod(argv[i + 1], nullptr);
        else if (option == "--sample-rate") sampleRate = std::strtoull(argv[i + 1], nullptr, 10);
    }
    constexpr std::size_t blockSize = 128;
    const auto blocks = static_cast<std::size_t>(std::ceil(seconds * sampleRate / blockSize));
    mechana::fuzz::OctaveFuzzEngine engine;
    engine.prepare(sampleRate, 2);
    mechana::fuzz::Parameters parameters;
    engine.setParameters(parameters);
    std::array<std::array<float, blockSize>, 2> audio {};
    std::array<float*, 2> pointers { audio[0].data(), audio[1].data() };
    std::cout << std::fixed << std::setprecision(3)
              << "Mechana native effect benchmark v1\n"
              << "effect-name=Octave Fuzz effect-id=octave-fuzz sample-rate=" << sampleRate
              << " channels=2 block=" << blockSize << " latency=" << engine.latencySamples() << '\n';
    std::vector<double> loads;
    for (std::size_t cycle = 1; cycle <= cycles; ++cycle) {
        const auto started = std::chrono::steady_clock::now();
        double checksum = 0.0;
        for (std::size_t block = 0; block < blocks; ++block) {
            for (std::size_t frame = 0; frame < blockSize; ++frame)
                audio[0][frame] = audio[1][frame] = 0.2F * std::sin(static_cast<float>((block * blockSize + frame) * 0.031));
            engine.process(pointers.data(), 2, blockSize);
            checksum += audio[0][block % blockSize];
        }
        const auto elapsed = std::chrono::duration<double>(std::chrono::steady_clock::now() - started).count();
        const auto rendered = static_cast<double>(blocks * blockSize) / sampleRate;
        const auto load = elapsed / rendered * 100.0;
        loads.push_back(load);
        std::cout << "cycle " << cycle << " overall=" << elapsed * 1'000.0 << " ms load="
                  << load << "% realtime=" << rendered / elapsed
                  << "x checksum=" << checksum << '\n';
    }
    std::sort(loads.begin(), loads.end());
    const auto sum = std::accumulate(loads.begin(), loads.end(), 0.0);
    std::cout << "SUMMARY load-min=" << loads.front() << "% load-average=" << sum / loads.size()
              << "% load-median=" << loads[loads.size() / 2] << "% load-max=" << loads.back()
              << "% realtime-median=" << 100.0 / loads[loads.size() / 2] << "x\n";
}
