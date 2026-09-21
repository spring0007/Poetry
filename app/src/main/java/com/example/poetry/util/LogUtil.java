package com.example.poetry.util;


import android.util.Log;

/**
 * Logging utility
 */
public class LogUtil {
	private static final String TAG = "Poetry";
	private static final boolean isDebuggable = true;

	private static String methodName = null, filename = null, msg = null;
	private static int lineno = 0;

	private LogUtil() {
		return;
	}

//    public static void setIsDebuggable() {
//
//        LogUtil.isDebuggable = Integer.parseInt(SystemProperties.get("persist.sys.awell.logswitch", "1")) == 1;
//    }
	public static void i(final String message) {
		if (!isDebuggable) return;

		getMethodNames(new Throwable().getStackTrace());
		Log.i(TAG, createLog(message));
	}

	public static void d(final String message) {
		if (!isDebuggable) return;

		getMethodNames(new Throwable().getStackTrace());
		Log.d(TAG, createLog(message));
	}

	public static void v(final String message) {
		if (!isDebuggable) return;

		getMethodNames(new Throwable().getStackTrace());
		Log.v(TAG, createLog(message));
	}

	public static void e(final String message) {
		if (!isDebuggable) return;

		getMethodNames(new Throwable().getStackTrace());
		Log.e(TAG, createLog(message));
	}

	public static void e(final String message, Exception e) {
		if (!isDebuggable) return;

		getMethodNames(new Throwable().getStackTrace());
		Log.e(TAG, createLog(message),e);
	}

	public static void w(final String message) {
		if (!isDebuggable) return;

		getMethodNames(new Throwable().getStackTrace());
		Log.w(TAG, createLog(message));
	}

	private static void getMethodNames(StackTraceElement[] sElements) {
		filename =  sElements[1].getFileName();
		methodName = sElements[1].getMethodName();
		lineno = sElements[1].getLineNumber();
	}

	private static String createLog(String log) {
		StringBuffer buffer = new StringBuffer();
		buffer.append("[");
		buffer.append(filename);
		buffer.append(" : ");
		buffer.append(methodName);
		buffer.append(" : ");
		buffer.append(lineno);
		buffer.append("]:");
		buffer.append(log);
		return buffer.toString();
	}
}
