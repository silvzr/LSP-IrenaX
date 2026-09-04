package io.github.libxposed.api.error;

/**
 * Thrown to indicate that a hook failed due to framework internal error.
 * <p>
 * This inherits from {@link Error} rather than {@link RuntimeException} because hook failures are
 * considered fatal framework bugs. Module developers <b>should not</b> attempt to catch this error
 * to provide fallbacks.
 * </p>
 */
@SuppressWarnings("unused")
public class HookFailedError extends XposedFrameworkError {
    public HookFailedError(String message) {
        super(message);
    }

    public HookFailedError(String message, Throwable cause) {
        super(message, cause);
    }

    public HookFailedError(Throwable cause) {
        super(cause);
    }
}