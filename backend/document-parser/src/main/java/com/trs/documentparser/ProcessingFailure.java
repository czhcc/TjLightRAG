package com.trs.documentparser;

import lombok.Getter;

/**
 * Safe, user-visible failure code without raw parser input or exception text.
 *
 * @author czhcc
 */
@Getter
public class ProcessingFailure extends RuntimeException {
    private final String code;

    /**
     * Initializes ProcessingFailure for the document pipeline.
     *
     * @author czhcc
     */
    public ProcessingFailure(String code) {
        super(code);
        this.code = code;
    }
}
