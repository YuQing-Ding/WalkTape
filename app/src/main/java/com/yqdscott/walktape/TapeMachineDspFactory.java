package com.yqdscott.walktape;

/** Keeps machine selection out of the realtime decoder and makes unsupported IDs fail safe. */
final class TapeMachineDspFactory {
    private TapeMachineDspFactory() {
    }

    static TapeMachineDsp create(TapeMachineProfile profile, int sampleRate) {
        if (profile != null && profile.isAiwaHsJx707()) {
            return new AiwaHsJx707Dsp(sampleRate);
        }
        return new TpsL2Dsp(sampleRate);
    }

    /** Production signal chain: magnetic stock first, then machine transport/head/electronics. */
    static TapeMachineDsp create(TapeMachineProfile profile,
                                 TapeStockProfile tapeProfile,
                                 int sampleRate) {
        TapeMachineProfile selectedMachine = profile == null
                ? TapeMachineProfile.sonyTpsL2Reference()
                : TapeMachineProfile.forId(profile.id);
        TapeStockProfile selectedTape = tapeProfile == null
                ? TapeStockProfile.sonyChf1978()
                : TapeStockProfile.forId(tapeProfile.id);
        TapeMachineDsp machine;
        if (selectedMachine.isAiwaHsJx707()) {
            machine = new AiwaHsJx707Dsp(sampleRate, 0x4a58373037L,
                    true, false, false, true);
        } else {
            machine = new TpsL2Dsp(sampleRate, 0x5450534cL,
                    true, false, false, true);
        }
        return new CassetteSignalChainDsp(
                new TapeMediumDsp(sampleRate, selectedTape), machine);
    }
}
