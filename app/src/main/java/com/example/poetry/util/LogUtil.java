package com.example.poetry.util;


import android.util.Log;

/**
 * Logging utility
 */
public class LogUtil {
	private static final String TAG = "Poetry";
	private static final boolean isDebuggable = true;

	private LogUtil() {
		return;
	}

//    public static void setIsDebuggable() {
//
//        LogUtil.isDebuggable = Integer.parseInt(SystemProperties.get("persist.sys.awell.logswitch", "1")) == 1;
//    }
	public static void i(final String message) {
		if (!isDebuggable) return;

		Log.i(TAG, createLog(message, new Throwable().getStackTrace()));
	}

	public static void d(final String message) {
		if (!isDebuggable) return;

		Log.d(TAG, createLog(message, new Throwable().getStackTrace()));
	}

	public static void v(final String message) {
		if (!isDebuggable) return;

		Log.v(TAG, createLog(message, new Throwable().getStackTrace()));
	}

	public static void e(final String message) {
		if (!isDebuggable) return;

		Log.e(TAG, createLog(message, new Throwable().getStackTrace()));
	}

	public static void e(final String message, Throwable e) {
		if (!isDebuggable) return;

		Log.e(TAG, createLog(message, new Throwable().getStackTrace()), e);
	}

	public static void w(final String message) {
		if (!isDebuggable) return;

		Log.w(TAG, createLog(message, new Throwable().getStackTrace()));
	}

	public static void w(final String message, Throwable e) {
		if (!isDebuggable) return;

		Log.w(TAG, createLog(message, new Throwable().getStackTrace()), e);
	}

	/**
	 * 拼出 {@code [File : method : line]:} 前缀。
	 *
	 * <p>栈是从**调用方**取好传进来的（在 i/d/v/e/w 里 {@code new Throwable()}），
	 * 所以 {@code sElements[0]} 是那个日志方法本身，{@code [1]} 才是真正的调用点。
	 * 别把取栈挪进本方法——那样下标要整体挪一位。
	 */
	private static String createLog(String log, StackTraceElement[] sElements) {
		StringBuffer buffer = new StringBuffer();
		buffer.append("[");
		buffer.append(sElements[1].getFileName());
		buffer.append(" : ");
		buffer.append(sElements[1].getMethodName());
		buffer.append(" : ");
		buffer.append(sElements[1].getLineNumber());
		buffer.append("]:");
		buffer.append(log);
		return buffer.toString();
	}
}
