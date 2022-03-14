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

/**
 * Contains configuration variables for {@link org.omegazero.proxy.http.HTTPEngine}s.
 * 
 * @since 3.3.1
 */
public class HTTPEngineConfig extends ConfigObject {

	private static final long serialVersionUID = 1L;


	private transient boolean disableDefaultRequestLog;
	private transient int upstreamConnectionTimeout;
	private transient boolean enableHeaders;
	private transient int maxHeaderSize;

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
		this.maxHeaderSize = super.optInt("maxHeaderSize", 8192);
	}


	/**
	 * Returns whether the default request log messages should be disabled.
	 * 
	 * @return {@code true} to disable the default request log messages
	 */
	public boolean isDisableDefaultRequestLog() {
		return this.disableDefaultRequestLog;
	}

	/**
	 * Returns the maximum time in milliseconds to wait until a connection to an upstream server is established before the connection attempt should be cancelled and an error
	 * be reported.
	 * 
	 * @return The upstream connection timeout
	 */
	public int getUpstreamConnectionTimeout() {
		return this.upstreamConnectionTimeout;
	}

	/**
	 * Returns whether a set of default headers should be added to proxied HTTP messages.
	 * 
	 * @return {@code true} to add default HTTP headers
	 */
	public boolean isEnableHeaders() {
		return this.enableHeaders;
	}

	/**
	 * Returns the maximum size of a HTTP message header (the start line and all headers) in bytes.
	 * 
	 * @return The maximum HTTP message size
	 */
	public int getMaxHeaderSize() {
		return this.maxHeaderSize;
	}


	private void readObject(java.io.ObjectInputStream in) throws IOException, ClassNotFoundException {
		in.defaultReadObject();
		this.readDefaults();
	}
}
