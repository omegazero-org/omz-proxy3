/*
 * Copyright (C) 2021 omegazero.org, user94729
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Covered Software is provided under this License on an "as is" basis, without warranty of any kind,
 * either expressed, implied, or statutory, including, without limitation, warranties that the Covered Software
 * is free of defects, merchantable, fit for a particular purpose or non-infringing.
 * The entire risk as to the quality and performance of the Covered Software is with You.
 */
package org.omegazero.proxy.http;

import java.io.Serializable;
import java.util.Map;

/**
 * Represents HTTP trailers (headers sent after the request or response body).
 * 
 * @since 3.4.1
 */
public class HTTPMessageTrailers extends HTTPHeaderContainer implements Serializable {

	private static final long serialVersionUID = 1L;

	private final HTTPMessage httpMessage;


	/**
	 * Creates a new {@link HTTPMessageTrailers} instance.<br>
	 * <br>
	 * Note that no deep copy of the <b>trailers</b> will be created, meaning changes made to the given {@link HTTPHeaderContainer} will reflect on this
	 * {@link HTTPMessageTrailers}.
	 * 
	 * @param httpMessage The {@link HTTPMessage} these trailers belong to
	 * @param trailers    The trailers
	 * @since 3.5.1
	 */
	public HTTPMessageTrailers(HTTPMessage httpMessage, HTTPHeaderContainer trailers) {
		super(trailers != null ? trailers.headerFields : null);
		this.httpMessage = httpMessage;
	}

	/**
	 * Creates a new {@link HTTPMessageTrailers} instance.
	 * 
	 * @param httpMessage The {@link HTTPMessage} these trailers belong to
	 * @param trailers    The trailers
	 * @deprecated Since 3.5.1, use {@link #HTTPMessageTrailers(HTTPMessage, HTTPHeaderContainer)} instead.
	 */
	@Deprecated
	public HTTPMessageTrailers(HTTPMessage httpMessage, Map<String, String> trailers) {
		this(httpMessage, HTTPHeaderContainer.fromLegacy(trailers));
	}


	/**
	 * Returns the {@link HTTPMessage} this trailer data belongs to.
	 * 
	 * @return The <code>HTTPMessage</code>
	 */
	public HTTPMessage getHttpMessage() {
		return this.httpMessage;
	}
}
