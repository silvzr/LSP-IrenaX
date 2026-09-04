package io.github.libxposed.api.errors;

/**
 * Thrown to indicate that the Xposed framework function is broken.
 *
 * <p>This is the API 100 error. It extends the API 101 {@link io.github.libxposed.api.error.XposedFrameworkError}
 * so that modules of either generation can catch the errors thrown by the framework's shared
 * methods: API 100 modules catch {@code errors.XposedFrameworkError}, API 101 modules catch
 * {@code error.XposedFrameworkError}, and both match.</p>
 */
public class XposedFrameworkError extends io.github.libxposed.api.error.XposedFrameworkError {
    public XposedFrameworkError(String message) {
        super(message);
    }

    public XposedFrameworkError(String message, Throwable cause) {
        super(message, cause);
    }

    public XposedFrameworkError(Throwable cause) {
        super(cause);
    }
}