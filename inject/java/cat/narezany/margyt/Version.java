package cat.narezany.margyt;

/**
 * Written by the build. Do not edit.
 *
 * MOD is this repository's VERSION file; TIKTOK is what the apk this
 * was built from calls itself. The first is compared against the
 * repository to know whether there is an update; the second is there
 * so a person reporting something can say which TikTok it happened on.
 *
 * TEST marks a diagnostic build: it carries the current account id faintly on
 * screen. Local mod settings are available in both test and release builds;
 * server-backed claims keep their own validation.
 */
final class Version {

    private Version() {}

    static final String MOD = "1.24";
    static final String TIKTOK = "46.9.42";
    static final boolean TEST = false;
}
