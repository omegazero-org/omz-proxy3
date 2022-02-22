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

import org.omegazero.net.socket.SocketConnection;

/**
 * HTTP server implementation interface.<br>
 * <br>
 * An instance of a <code>HTTPEngine</code> is associated with one connection by a client.
 */
public interface HTTPEngine {

	/**
	 * 
	 * @param data Incoming data of a client to process
	 */
	public void processData(byte[] data);

	public void close();

	/**
	 * 
	 * @return The {@link SocketConnection} to the client associated with this instance
	 */
	public SocketConnection getDownstreamConnection();

	/**
	 * Responds to the given <b>request</b> with the given <b>response</b>, if the request has not already received a response.<br>
	 * <br>
	 * The engine may set additional, edit, or delete any headers in the HTTP response.
	 * 
	 * @param request  The request to respond to
	 * @param response The response
	 */
	public void respond(HTTPMessage request, HTTPMessageData response);

	/**
	 * Responds to the given <b>request</b> with a new HTTP response with the given <b>status</b>, <b>data</b> and <b>headers</b>, if the request has not already received a
	 * response.<br>
	 * <br>
	 * In the <b>headers</b> array, each value at an even index (starting at 0) is a header key (name), followed by the values at odd indices. If the array length is not a
	 * multiple of 2, the last element is ignored.<br>
	 * For example an array like <code>{"x-example", "123", "x-another-header", "value here"}</code> will set two headers in the response with names "x-example" and
	 * "x-another-header" and values "123" and "value here", respectively.<br>
	 * The engine may set additional, edit, or delete any headers in the HTTP response.
	 * 
	 * @param request The request to respond to
	 * @param status  The status code of the response
	 * @param data    The data to send in the response
	 * @param headers Headers to send in the response. See explanation in description
	 */
	public void respond(HTTPMessage request, int status, byte[] data, String... headers);

	/**
	 * Selects an appropriate {@link HTTPErrdoc} template and generates a response body using it. The resulting body is sent as a HTTP response with the given <b>status</b>
	 * and <b>headers</b>.
	 * 
	 * @param request The request to respond to
	 * @param status  The status code of the response
	 * @param title   Title of the error message
	 * @param message Error message
	 * @param headers Headers to send in the response. See {@link #respond(HTTPMessage, int, byte[], String...)} for more information
	 * @see HTTPErrdoc#generate(int, String, String, String, String)
	 * @see org.omegazero.proxy.core.Proxy#getErrdocForAccept(String)
	 */
	public void respondError(HTTPMessage request, int status, String title, String message, String... headers);
}
