package com.yqdscott.walktape;

/** Runs the chosen magnetic stock through the chosen physical playback machine. */
final class CassetteSignalChainDsp implements TapeMachineDsp {
    private final TapeMediumDsp tape;
    private final MachineImperfectionDsp imperfection;
    private final TapeMachineDsp machine;
    private final DolbyNoiseReductionDsp dolby;

    CassetteSignalChainDsp(TapeMediumDsp tape,
                           MachineImperfectionDsp imperfection,
                           TapeMachineDsp machine,
                           DolbyNoiseReductionDsp dolby) {
        if (tape == null || machine == null || dolby == null) {
            throw new IllegalArgumentException("Tape, Dolby and machine are required");
        }
        this.tape = tape;
        this.imperfection = imperfection;
        this.machine = machine;
        this.dolby = dolby;
    }

    TapeStockProfile tapeProfile() {
        return tape.profile();
    }

    TapeMachineDsp machineRenderer() {
        return machine;
    }

    MachineImperfectionDsp imperfectionRenderer() {
        return imperfection;
    }

    @Override
    public void setHighTape(boolean enabled) {
        machine.setHighTape(enabled);
    }

    @Override
    public void setDolbyMode(DolbyMode mode) {
        dolby.setMode(mode);
    }

    DolbyMode dolbyMode() {
        return dolby.mode();
    }

    @Override
    public void reset() {
        dolby.reset();
        tape.reset();
        if (imperfection != null) {
            imperfection.reset();
        }
        machine.reset();
    }

    @Override
    public void process(float[] stereo, int frameCount) {
        DolbyMode blockMode = dolby.beginBlock();
        dolby.encode(stereo, frameCount, blockMode);
        tape.process(stereo, frameCount);
        dolby.decode(stereo, frameCount, blockMode);
        if (imperfection != null) {
            imperfection.process(stereo, frameCount);
        }
        machine.process(stereo, frameCount);
    }
}
