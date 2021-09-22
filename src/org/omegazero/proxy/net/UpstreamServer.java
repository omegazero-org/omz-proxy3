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
import java.net.UnknownHostException;
import java.util.HashSet;
import java.util.Set;

import org.omegazero.common.logging.Logger;

public class UpstreamServer {

	private static final Logger logger = Logger.create();


	private InetAddress address;
	private final int addressTTL;
	private final int plainPort;
	private final int securePort;
	private final Set<String> protocols;

	private long addressExpiration;

	public UpstreamServer(InetAddress address, int plainPort, int securePort) {
		this(address, -1, plainPort, securePort, null);
	}

	public UpstreamServer(InetAddress address, int plainPort, int securePort, Set<String> protocols) {
		this(address, -1, plainPort, securePort, protocols);
	}

	public UpstreamServer(InetAddress address, int addressTTL, int plainPort, int securePort, Set<String> protocols) {
		this.address = address;
		this.addressTTL = addressTTL;
		this.plainPort = plainPort;
		this.securePort = securePort;
		if(protocols != null)
			this.protocols = protocols;
		else{
			this.protocols = new HashSet<String>();
			this.protocols.add("http/1.1");
		}

		if(addressTTL >= 0)
			this.addressExpiration = System.nanoTime() + addressTTL * 1000000000L;
	}


	private void reresolveAddressIfNecessary() {
		if(this.addressTTL < 0)
			return;
		long time = System.nanoTime();
		if(time <= this.addressExpiration)
			return;
		String hostname = this.address.getHostName();
		try{
			this.address = InetAddress.getByName(hostname);
			this.addressExpiration = time + this.addressTTL * 1000000000L;
			if(logger.debug())
				logger.debug("Re-resolved address '", hostname, "': ", this.address.getHostAddress());
		}catch(UnknownHostException e){
			logger.warn("Error while re-resolving address '", hostname, "', using existing address: ", e.toString());
		}
	}


	/**
	 * 
	 * @return The address of this <code>UpstreamServer</code>
	 */
	public InetAddress getAddress() {
		this.reresolveAddressIfNecessary();
		return this.address;
	}

	/**
	 * 
	 * @return The number of seconds the address of a resolved DNS name is valid; negative if configured to be valid forever (default)
	 */
	public int getAddressTTL() {
		return this.addressTTL;
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
