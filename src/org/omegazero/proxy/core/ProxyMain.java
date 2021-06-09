/*
 * Copyright (C) 2021 omegazero.org
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Covered Software is provided under this License on an "as is" basis, without warranty of any kind,
 * either expressed, implied, or statutory, including, without limitation, warranties that the Covered Software
 * is free of defects, merchantable, fit for a particular purpose or non-infringing.
 * The entire risk as to the quality and performance of the Covered Software is with You.
 */
package org.omegazero.proxy.core;

import java.lang.Thread.UncaughtExceptionHandler;

import org.omegazero.common.OmzLib;
import org.omegazero.common.event.Tasks;
import org.omegazero.common.logging.Logger;
import org.omegazero.common.logging.LoggerUtil;
import org.omegazero.common.util.Args;
import org.omegazero.common.util.Util;

public class ProxyMain {

	private static Logger logger = LoggerUtil.createLogger();

	private static Proxy proxy;

	private static final int shutdownTimeout = 2000;
	private static boolean shuttingDown = false;

	public static void main(String[] pargs) {
		Args args = Args.parse(pargs);

		LoggerUtil.redirectStandardOutputStreams();

		String logFile = args.getValueOrDefault("logFile", "log");
		LoggerUtil.init(LoggerUtil.resolveLogLevel(args.getValue("logLevel")), logFile.equals("null") ? null : logFile);

		Util.onClose(ProxyMain::shutdown);

		OmzLib.printBrand();
		Thread.setDefaultUncaughtExceptionHandler(new UncaughtExceptionHandler(){

			private final boolean exitOnDoubleFault = args.getBooleanOrDefault("exitOnDoubleFault", true);
			private final byte[] vmerrMsg = "Virtual Machine Error\n".getBytes();
			private final byte[] dfMsg = "Uncaught error in exception handler\n".getBytes();

			@Override
			public void uncaughtException(Thread t, Throwable err) {
				try{
					logger.fatal("Uncaught exception in thread '", t.getName(), "': ", err);
					ProxyMain.shutdown();
				}catch(VirtualMachineError e){ // things have really gotten out of hand now
					handleError(e, vmerrMsg);
					throw e;
				}catch(Throwable e){
					handleError(e, dfMsg);
					throw e;
				}
			}

			private void handleError(Throwable err, byte[] msg) {
				try{
					System.setErr(LoggerUtil.sysErr);
					err.printStackTrace();
				}catch(Throwable e){
					for(int i = 0; i < vmerrMsg.length; i++)
						LoggerUtil.sysOut.write(vmerrMsg[i]);
				}
				// exceptions thrown in the uncaught exception handler dont cause the VM to exit, even if it is a OOM error, so just exit manually
				// (because everything is definitely in a very broken state and it is easier for supervisor programs to detect that there is a problem when exiting)
				// exitOnDoubleFault option can be set to false for debugging
				if(exitOnDoubleFault)
					Runtime.getRuntime().halt(3);
			}
		});

		proxy = new Proxy();
		try{
			Thread.currentThread().setName("InitializationThread");
			proxy.init(args);
		}catch(Throwable e){
			logger.fatal("Error during proxy initialization: ", e);
		}
	}

	protected static synchronized void shutdown() {
		try{
			// this function may be called multiple times unintentionally, for example when this method is called explicitly (through shutdown()),
			// it shuts down all non-daemon threads, causing shutdown hooks to execute, one of which (at least, in Util.onClose) will also call this method
			if(shuttingDown)
				return;
			shuttingDown = true;

			logger.info("Shutting down");

			try{
				if(proxy != null)
					proxy.close();
			}catch(Throwable e){
				logger.fatal("Error while closing proxy: ", e);
			}
			Tasks.timeout((args) -> {
				ProxyMain.closeTimeout();
			}, 2000).daemon();
			LoggerUtil.close();

			Tasks.exit();
		}catch(Exception e){
			logger.fatal("Error while shutting down", e);
		}finally{
			if(!Util.waitForNonDaemonThreads(shutdownTimeout))
				ProxyMain.closeTimeout();
		}
	}

	private static void closeTimeout() {
		try{
			logger.warn("A non-daemon thread has not exited " + shutdownTimeout + " milliseconds after an exit request was issued, JVM will be forcibly terminated");
			for(Thread t : Thread.getAllStackTraces().keySet()){
				if(!t.isDaemon() && !"DestroyJavaVM".equals(t.getName())){
					logger.warn("Still running thread (stack trace below): " + t.getName());
					for(StackTraceElement ste : t.getStackTrace())
						logger.warn("    " + ste);
					break;
				}
			}
		}catch(Throwable e){
			e.printStackTrace();
		}finally{
			Runtime.getRuntime().halt(2);
		}
	}
}
