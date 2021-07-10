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

import java.io.Serializable;

public class HTTPMessageData implements Serializable {

	private static final long serialVersionUID = 1L;

	private final HTTPMessage httpMessage;
	private final boolean lastPacket;

	private byte[] data;

	public HTTPMessageData(HTTPMessage httpMessage, byte[] data) {
		this(httpMessage, false, data);
	}

	public HTTPMessageData(HTTPMessage httpMessage, boolean lastPacket, byte[] data) {
		this.httpMessage = httpMessage;
		this.lastPacket = lastPacket;
		this.data = data;
	}


	public byte[] getData() {
		return this.data;
	}

	public boolean isLastPacket() {
		return this.lastPacket;
	}

	public void setData(byte[] data) {
		if(!this.httpMessage.isChunkedTransfer() && this.data.length != data.length)
			throw new UnsupportedOperationException("HTTP message body chunk size must be the same size if transfer is not chunked");
		this.data = data;
	}

	public HTTPMessage getHttpMessage() {
		return this.httpMessage;
	}
}
