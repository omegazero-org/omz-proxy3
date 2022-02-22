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

import java.io.IOException;

public class InvalidHTTPMessageException extends IOException {

	private static final long serialVersionUID = 1L;


	public InvalidHTTPMessageException() {
		super();
	}

	public InvalidHTTPMessageException(String msg) {
		super(msg);
	}

	public InvalidHTTPMessageException(String msg, Throwable cause) {
		super(msg, cause);
	}
}
