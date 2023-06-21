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

import org.omegazero.common.logging.Logger;
import org.omegazero.common.runtime.ApplicationWrapper;

public class ProxyMain {

	private static final Logger logger = Logger.create();


	public static void main(String[] pargs) {
		propertyRenamed("org.omegazero.proxy.shutdownTimeout", "org.omegazero.common.runtime.shutdownTimeout");
		System.setProperty("org.omegazero.common.event.taskWorker", "org.omegazero.proxy.core.Proxy::getServerWorker()");

		ApplicationWrapper.init(Proxy::new);
		ApplicationWrapper.start(pargs);
	}


	private static void propertyRenamed(String o, String n) {
		String v = System.getProperty(o);
		if(v != null){
			System.setProperty(n, v);
			logger.warn("System property ", o, " was renamed to ", n, ", please use the new name instead (the value was applied anyway)");
		}
	}
}
