package com.pexserver.completions;

interface CompletionTask {
    boolean done();
    void step();
    CompletionModel.Command result();
}
