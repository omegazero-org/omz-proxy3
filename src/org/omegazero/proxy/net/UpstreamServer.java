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
package org.omegazero.proxy.net;

import java.net.InetAddress;
import java.util.HashSet;
import java.util.Set;

public class UpstreamServer {

	private final InetAddress address;
	private final int plainPort;
	private final int securePort;
	private final Set<String> protocols;

	public UpstreamServer(InetAddress address, int plainPort, int securePort) {
		this(address, plainPort, securePort, null);
	}

	public UpstreamServer(InetAddress address, int plainPort, int securePort, Set<String> protocols) {
		this.address = address;
		this.plainPort = plainPort;
		this.securePort = securePort;
		if(protocols != null)
			this.protocols = protocols;
		else{
			this.protocols = new HashSet<String>();
			this.protocols.add("http/1.1");
		}
	}


	/**
	 * 
	 * @return The address of this <code>UpstreamServer</code>
	 */
	public InetAddress getAddress() {
		return this.address;
	}

	/**
	 * 
	 * @return The port on which this <code>UpstreamServer</code> is listening for plaintext connections
	 */
	public int getPlainPort() {
		return this.plainPort;
	}

	/**
	 * 
	 * @return The port on which this <code>UpstreamServer</code> is listening for encrypted connections
	 */
	public int getSecurePort() {
		return this.securePort;
	}

	/**
	 * 
	 * @param proto The name of the protocol
	 * @return <code>true</code> if this <code>UpstreamServer</code> was configured to support the given protocol name
	 * @since 3.3.1
	 */
	public boolean isProtocolSupported(String proto) {
		return this.protocols.contains(proto);
	}

	@Override
	public int hashCode() {
		return this.address.hashCode() + 36575 * this.plainPort + 136575 * this.securePort;
	}

	@Override
	public boolean equals(Object o) {
		if(o == null || !(o instanceof UpstreamServer))
			return false;
		UpstreamServer u = (UpstreamServer) o;
		return u.address.equals(this.address) && u.plainPort == this.plainPort && u.securePort == this.securePort;
	}

	@Override
	public String toString() {
		return this.address + ":" + this.plainPort + "/" + this.securePort;
	}
}
