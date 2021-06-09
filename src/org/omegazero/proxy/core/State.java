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

public enum State {
	NEW(0), PREINIT(1), INIT(2), STARTING(3), RUNNING(4), STOPPING(5), STOPPED(6);

	private final int VALUE;

	private State(int value) {
		this.VALUE = value;
	}

	public int value() {
		return VALUE;
	}
}
