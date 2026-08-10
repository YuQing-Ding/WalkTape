package com.yqdscott.walktape;

/**
 * Genius application credentials supplied by the app owner for this private build.
 *
 * <p>Only {@link #CLIENT_ACCESS_TOKEN} is used by the current read-only lyrics lookup. The
 * client id and secret are retained for a future OAuth flow and are never sent by this build.</p>
 */
final class GeniusCredentials {

    static final String CLIENT_ID =
            "fPWYMB-xb9E-L37LLyv7Ko4cibdbYnSQm8F8zhfzwvefjcPFtMuXJDfM40vhNWLN";
    static final String CLIENT_SECRET =
            "lsvkdlEled4hwRt-8_tS_ngWMzheRB7H9RkCo0M36ohg674aj9Z6Z2UfDlcojOMXwe8_LZKdY5FgKp6raOkdcA";
    static final String CLIENT_ACCESS_TOKEN =
            "nwMmuH7QC6rxbATSjI7WI2r6qBQlOy5t4pYlQewTGjnfgU1JPvFSLh2hqYHzT6CE";

    private GeniusCredentials() {
    }
}
