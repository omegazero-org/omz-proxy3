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
package org.omegazero.proxy.config;

import java.io.IOException;
import java.util.Map;

import org.omegazero.common.config.ConfigObject;

public class HTTPEngineConfig extends ConfigObject {

	private static final long serialVersionUID = 1L;


	private transient boolean disableDefaultRequestLog;
	private transient int upstreamConnectionTimeout;
	private transient boolean enableHeaders;

	public HTTPEngineConfig(ConfigObject co) {
		this(co.copyData());
	}

	public HTTPEngineConfig(Map<String, Object> data) {
		super(data);

		this.readDefaults();
	}

	private void readDefaults() {
		this.disableDefaultRequestLog = super.optBoolean("disableDefaultRequestLog", false);
		this.upstreamConnectionTimeout = super.optInt("upstreamConnectionTimeout", 30) * 1000;
		this.enableHeaders = super.optBoolean("enableHeaders", true);
	}


	public boolean isDisableDefaultRequestLog() {
		return this.disableDefaultRequestLog;
	}

	/**
	 * 
	 * @return The maximum time in milliseconds to wait until a connection to an upstream server is established before the connection attempt should be cancelled and an error
	 *         be reported
	 */
	public int getUpstreamConnectionTimeout() {
		return this.upstreamConnectionTimeout;
	}

	/**
	 * 
	 * @return <code>true</code> if this headers should be added to proxied HTTP messages
	 */
	public boolean isEnableHeaders() {
		return this.enableHeaders;
	}


	private void readObject(java.io.ObjectInputStream in) throws IOException, ClassNotFoundException {
		in.defaultReadObject();
		this.readDefaults();
	}
}
