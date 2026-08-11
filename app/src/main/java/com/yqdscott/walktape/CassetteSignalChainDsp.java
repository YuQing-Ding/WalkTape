package com.yqdscott.walktape;

/** Runs the chosen magnetic stock through the chosen physical playback machine. */
final class CassetteSignalChainDsp implements TapeMachineDsp {
    private final TapeMediumDsp tape;
    private final TapeMachineDsp machine;

    CassetteSignalChainDsp(TapeMediumDsp tape, TapeMachineDsp machine) {
        if (tape == null || machine == null) {
            throw new IllegalArgumentException("Tape and machine are required");
        }
        this.tape = tape;
        this.machine = machine;
    }

    TapeStockProfile tapeProfile() {
        return tape.profile();
    }

    TapeMachineDsp machineRenderer() {
        return machine;
    }

    @Override
    public void setHighTape(boolean enabled) {
        machine.setHighTape(enabled);
    }

    @Override
    public void reset() {
        tape.reset();
        machine.reset();
    }

    @Override
    public void process(float[] stereo, int frameCount) {
        tape.process(stereo, frameCount);
        machine.process(stereo, frameCount);
    }
}
