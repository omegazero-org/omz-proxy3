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
package org.omegazero.proxy.http;

import org.omegazero.http.common.HTTPMessage;
import org.omegazero.http.common.HTTPRequest;
import org.omegazero.http.util.HTTPResponder;
import org.omegazero.http.util.HTTPStatus;
import org.omegazero.net.socket.SocketConnection;

/**
 * HTTP server implementation interface.<br>
 * <br>
 * An instance of a <code>HTTPEngine</code> is associated with one connection by a client.
 */
public interface HTTPEngine extends HTTPResponder {

	/**
	 * Processes the given <b>data</b> received over the connection from the client.
	 * 
	 * @param data Incoming data of a client to process
	 */
	public void processData(byte[] data);

	/**
	 * Called when the connection to the client closes.
	 */
	public void close();

	/**
	 * Returns the {@link SocketConnection} to the client associated with this instance.
	 * 
	 * @return The {@code SocketConnection} to the client
	 */
	public SocketConnection getDownstreamConnection();


	/**
	 * Selects an appropriate {@link HTTPErrdoc} template and generates a response body using it.
	 * <p>
	 * The title is selected using {@link HTTPStatus#getStatusName(int)} based on the given <b>status</b>; if the given <b>status</b> is not defined by {@link HTTPStatus}, behavior
	 * is undefined. The resulting body is sent as a HTTP response with the given <b>status</b> and <b>headers</b>.
	 * 
	 * @param request The request to respond to
	 * @param status The status code of the response
	 * @param message Error message
	 * @param headers Headers to send in the response. See {@link #respond(HTTPMessage, int, byte[], String...)} for more information
	 * @since 3.6.1
	 * @see #respondError(HTTPRequest, int, String, String, String...)
	 */
	public default void respondError(HTTPRequest request, int status, String message, String... headers) {
		this.respondError(request, status, HTTPStatus.getStatusName(status), message, headers);
	}

	/**
	 * Selects an appropriate {@link HTTPErrdoc} template and generates a response body using it.
	 * <p>
	 * The resulting body is sent as a HTTP response with the given <b>status</b> and <b>headers</b>.
	 * 
	 * @param request The request to respond to
	 * @param status The status code of the response
	 * @param title Title of the error message
	 * @param message Error message
	 * @param headers Headers to send in the response. See {@link #respond(HTTPMessage, int, byte[], String...)} for more information
	 * @see HTTPErrdoc#generate(int, String, String, String, String)
	 * @see org.omegazero.proxy.core.Proxy#getErrdocForAccept(String)
	 */
	public void respondError(HTTPRequest request, int status, String title, String message, String... headers);
}
