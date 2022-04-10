/*
 * Copyright (C) 2022 omegazero.org, user94729
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.omegazero.proxy.http;

import org.omegazero.http.common.HTTPHeaderContainer;
import org.omegazero.http.common.HTTPResponse;

/**
 * A {@link HTTPResponse} that stores the initial response line values.
 * <p>
 * The {@code getInitial*} methods in this class are not affected by their corresponding {@code set*} methods.
 * 
 * @since 3.6.1
 */
public class ProxyHTTPResponse extends HTTPResponse {

	private static final long serialVersionUID = 1L;


	private final int initialStatus;
	private final String initialHttpVersion;

	/**
	 * See {@link HTTPResponse#HTTPResponse(int, String, HTTPHeaderContainer)}
	 * 
	 * @param status The HTTP response status code
	 * @param version The HTTP version
	 * @param headers The HTTP headers
	 */
	public ProxyHTTPResponse(int status, String version, HTTPHeaderContainer headers) {
		super(status, version, headers);
		this.initialStatus = status;
		this.initialHttpVersion = version;
	}

	/**
	 * See {@link HTTPResponse#HTTPResponse(HTTPResponse)}
	 * 
	 * @param response The {@code ProxyHTTPResponse} to copy from
	 */
	public ProxyHTTPResponse(ProxyHTTPResponse response) {
		super(response);
		this.initialStatus = response.initialStatus;
		this.initialHttpVersion = response.initialHttpVersion;
	}


	/**
	 * Returns the initial HTTP response status code. See {@link #getStatus()}.
	 * 
	 * @return The initial HTTP response status code
	 */
	public int getInitialStatus() {
		return this.initialStatus;
	}

	/**
	 * Returns the initial HTTP version. See {@link #getHttpVersion()}.
	 * 
	 * @return The initial HTTP version
	 */
	public String getInitialHttpVersion() {
		return this.initialHttpVersion;
	}
}
