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

	public void respond(HTTPMessage request, HTTPMessage response);

	public void respond(HTTPMessage request, int status, byte[] data, String... headers);

	public void respondError(HTTPMessage request, int status, String title, String message, String... headers);
}
