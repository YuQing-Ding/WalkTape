package com.yqdscott.walktape;

/** Keeps machine selection out of the realtime decoder and makes unsupported IDs fail safe. */
final class TapeMachineDspFactory {
    private TapeMachineDspFactory() {
    }

    static TapeMachineDsp create(TapeMachineProfile profile, int sampleRate) {
        if (profile != null && profile.isSonyWmD6c()) {
            return new SonyWmD6cDsp(sampleRate);
        }
        if (profile != null && profile.isAiwaHsJx707()) {
            return new AiwaHsJx707Dsp(sampleRate);
        }
        if (profile != null && profile.isSonyWmF2015()) {
            return new SonyWmF2015Dsp(sampleRate);
        }
        return new TpsL2Dsp(sampleRate);
    }

    /** Production signal chain: optional NR encode, magnetic stock, NR decode, then machine. */
    static TapeMachineDsp create(TapeMachineProfile profile,
                                 TapeStockProfile tapeProfile,
                                 int sampleRate) {
        return create(profile, tapeProfile, MachineConditionProfile.calibrated(), sampleRate);
    }

    /** Production signal chain with an optional, healthy-unit tolerance profile. */
    static TapeMachineDsp create(TapeMachineProfile profile,
                                 TapeStockProfile tapeProfile,
                                 MachineConditionProfile conditionProfile,
                                 int sampleRate) {
        TapeMachineProfile selectedMachine = profile == null
                ? TapeMachineProfile.sonyTpsL2Reference()
                : TapeMachineProfile.forId(profile.id);
        TapeStockProfile selectedTape = tapeProfile == null
                ? TapeStockProfile.sonyChf1978()
                : TapeStockProfile.forId(tapeProfile.id);
        MachineConditionProfile selectedCondition = conditionProfile == null
                ? MachineConditionProfile.calibrated()
                : MachineConditionProfile.forId(conditionProfile.id);
        TapeMachineDsp machine;
        long unitSeed;
        if (selectedMachine.isSonyWmD6c()) {
            unitSeed = 0x534f4e5944364320L;
            machine = new SonyWmD6cDsp(sampleRate, 0x574d443643313938L,
                    true, true, selectedTape.isHighPosition());
        } else if (selectedMachine.isAiwaHsJx707()) {
            unitSeed = 0x414957414a583730L;
            machine = new AiwaHsJx707Dsp(sampleRate, 0x4a58373037L,
                    true, false, false, true);
        } else if (selectedMachine.isSonyWmF2015()) {
            unitSeed = 0x534f4e5946323031L;
            machine = new SonyWmF2015Dsp(sampleRate, 0x574d4632303135L,
                    true, true, selectedCondition, unitSeed);
        } else {
            unitSeed = 0x534f4e595450534cL;
            machine = new TpsL2Dsp(sampleRate, 0x5450534cL,
                    true, false, false, true);
        }
        // The F2015 folds the same condition constants into its one physical transport delay.
        // Cascading a second delay line was both less physical and too costly for 24/192 ALAC.
        MachineImperfectionDsp imperfection = selectedCondition.isCalibrated()
                || selectedMachine.isSonyWmF2015()
                ? null
                : new MachineImperfectionDsp(sampleRate, selectedCondition, unitSeed);
        return new CassetteSignalChainDsp(
                new TapeMediumDsp(sampleRate, selectedTape), imperfection, machine,
                new DolbyNoiseReductionDsp(sampleRate, selectedMachine.supportsDolbyBC()));
    }
}
